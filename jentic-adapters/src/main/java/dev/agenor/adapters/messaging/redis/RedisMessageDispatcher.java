package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.TransportEndpoint;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.exceptions.AgentNotFoundException;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Redis Streams implementation of {@link MessageDispatcher}.
 *
 * <p>Composes {@link RedisTopicPublisher} (topic pub/sub) and
 * {@link RedisMessageTransport} (inter-node point-to-point) into the single
 * {@link MessageDispatcher} interface expected by
 * {@code AgenorRuntime.Builder.messageDispatcher(...)}.
 *
 * <p>Obtain instances via {@link RedisMessagingFactory#messageDispatcher()} or
 * {@link RedisMessagingFactory#messageDispatcher(Supplier)}.
 *
 * <h2>sendTo routing</h2>
 * <ol>
 *   <li><b>Local fast-path</b>: if the target agent called {@link #subscribeRecipient}
 *       on this dispatcher instance (same JVM), the message is delivered directly to
 *       its handler — no Redis hop.</li>
 *   <li><b>Remote path</b>: if an {@link AgentResolver} supplier is configured, the
 *       dispatcher resolves {@code receiverId → AgentEndpoint → nodeId} and writes to
 *       {@code <prefix>:node:<nodeId>} via {@link RedisMessageTransport}. The target
 *       node's consumer loop picks it up and routes it locally.</li>
 * </ol>
 *
 * @since 0.21.0
 * @see RedisMessagingFactory
 */
public final class RedisMessageDispatcher implements MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageDispatcher.class);

    private final RedisTopicPublisher  topicPublisher;
    private final RedisMessageTransport messageTransport;
    private final Supplier<AgentResolver> resolverSupplier;
    private final RedisMessagingConfig config;

    // agentId → handler for local routing of messages arriving on the node stream
    private final Map<String, MessageHandler> directHandlers = new ConcurrentHashMap<>();

    // Node-stream subscription — started lazily on first subscribeRecipient call
    private volatile Subscription nodeStreamSubscription;
    private final Object nodeStreamLock = new Object();

    RedisMessageDispatcher(RedisTopicPublisher topicPublisher,
                           RedisMessageTransport messageTransport,
                           Supplier<AgentResolver> resolverSupplier,
                           RedisMessagingConfig config) {
        this.topicPublisher   = Objects.requireNonNull(topicPublisher,   "topicPublisher");
        this.messageTransport = Objects.requireNonNull(messageTransport, "messageTransport");
        this.resolverSupplier = resolverSupplier != null ? resolverSupplier : () -> null;
        this.config           = Objects.requireNonNull(config,           "config");
    }

    // -------------------------------------------------------------------------
    // TopicPublisher
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> publish(Message msg) {
        return topicPublisher.publish(msg);
    }

    // -------------------------------------------------------------------------
    // TopicSubscriber
    // -------------------------------------------------------------------------

    @Override
    public Subscription subscribeTopic(String topic, MessageHandler handler) {
        return topicPublisher.subscribeTopic(topic, handler);
    }

    // -------------------------------------------------------------------------
    // DirectMessenger
    // -------------------------------------------------------------------------

    /**
     * Sends {@code msg} directly to the agent identified by {@link Message#receiverId()}.
     *
     * <p>Local fast-path: if the recipient has registered via {@link #subscribeRecipient}
     * on this instance, the message is delivered without going through Redis.
     * Remote path: resolves the endpoint via {@link AgentResolver} and delegates
     * to {@link RedisMessageTransport#send}.
     *
     * @throws IllegalArgumentException if {@code msg.receiverId()} is null or blank
     * @throws AgentNotFoundException   if the recipient is neither local nor resolvable
     */
    @Override
    public CompletableFuture<Void> sendTo(Message msg) {
        Objects.requireNonNull(msg, "msg");
        var receiverId = msg.receiverId();
        if (receiverId == null || receiverId.isBlank()) {
            throw new IllegalArgumentException("receiverId must be set for sendTo");
        }

        // Local fast-path: agent lives on this node
        var localHandler = directHandlers.get(receiverId);
        if (localHandler != null) {
            return localHandler.handle(msg);
        }

        // Remote path: resolve via AgentResolver then write to node stream
        var resolver = resolverSupplier.get();
        if (resolver == null) {
            return CompletableFuture.failedFuture(new AgentNotFoundException(receiverId,
                    "Agent '" + receiverId + "' has no local handler and no AgentResolver is configured"));
        }

        return resolver.resolveEndpoint(receiverId).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.failedFuture(new AgentNotFoundException(receiverId));
            }
            var ep = opt.get();
            var dest = new TransportEndpoint(ep.transportType(), ep.nodeId(), ep.transportProps());
            return messageTransport.send(dest, msg);
        });
    }

    // -------------------------------------------------------------------------
    // DirectReceiver
    // -------------------------------------------------------------------------

    /**
     * Registers {@code handler} to receive direct messages addressed to {@code localAgentId}.
     *
     * <p>On the first call this method starts a consumer loop on the local node stream
     * ({@code <prefix>:node:<nodeId>}). Subsequent calls add handlers to the local
     * routing table; the single consumer loop dispatches each incoming message to the
     * handler matching {@code receiverId}.
     *
     * @return a subscription whose {@code unsubscribe()} removes the handler from the
     *         routing table (the node-stream consumer loop continues running)
     */
    @Override
    public Subscription subscribeRecipient(String localAgentId, MessageHandler handler) {
        Objects.requireNonNull(localAgentId, "localAgentId");
        Objects.requireNonNull(handler,      "handler");
        if (localAgentId.isBlank()) {
            throw new IllegalArgumentException("localAgentId must not be blank");
        }

        directHandlers.put(localAgentId, handler);
        ensureNodeStreamRunning();

        return Subscription.of(localAgentId, () -> directHandlers.remove(localAgentId));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void ensureNodeStreamRunning() {
        if (nodeStreamSubscription == null) {
            synchronized (nodeStreamLock) {
                if (nodeStreamSubscription == null) {
                    var localEndpoint = new TransportEndpoint("redis", config.nodeId(), Map.of());
                    nodeStreamSubscription = messageTransport.subscribe(
                            localEndpoint, this::routeToLocalAgent);
                    log.debug("Node-stream consumer started for node '{}'", config.nodeId());
                }
            }
        }
    }

    private CompletableFuture<Void> routeToLocalAgent(Message msg) {
        var receiverId = msg.receiverId();
        if (receiverId == null) {
            log.warn("Direct message {} arrived with null receiverId — dropping", msg.id());
            return CompletableFuture.completedFuture(null);
        }
        var handler = directHandlers.get(receiverId);
        if (handler == null) {
            log.warn("No local handler for receiverId='{}' — dropping message {}", receiverId, msg.id());
            return CompletableFuture.completedFuture(null);
        }
        return handler.handle(msg);
    }
}
