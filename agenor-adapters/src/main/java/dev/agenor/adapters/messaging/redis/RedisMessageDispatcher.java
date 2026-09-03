package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.TransportEndpoint;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.deadletter.DeadLetter;
import dev.agenor.core.deadletter.DeadLetterQueue;
import dev.agenor.core.exceptions.AgentNotFoundException;
import dev.agenor.core.messaging.LocalEndpointProvider;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
public final class RedisMessageDispatcher implements MessageDispatcher, LocalEndpointProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageDispatcher.class);

    private final RedisTopicPublisher  topicPublisher;
    private final RedisMessageTransport messageTransport;
    private final Supplier<AgentResolver> resolverSupplier;
    private final RedisMessagingConfig config;
    private final DeadLetterQueue deadLetters;

    // agentId → handlers for local routing of messages arriving on the node stream.
    // A list, not a single handler: an agent subscribes more than once to its own
    // recipient channel (BaseAgent.autoSubscribeDirectMessages plus, when present,
    // DialogueCapability), and every subscriber must receive the message — the same
    // contract InMemoryMessageDispatcher provides.
    private final Map<String, List<MessageHandler>> directHandlers = new ConcurrentHashMap<>();

    // Node-stream subscription — started lazily on first subscribeRecipient call
    private volatile Subscription nodeStreamSubscription;
    private final Object nodeStreamLock = new Object();

    RedisMessageDispatcher(RedisTopicPublisher topicPublisher,
                           RedisMessageTransport messageTransport,
                           Supplier<AgentResolver> resolverSupplier,
                           RedisMessagingConfig config) {
        this(topicPublisher, messageTransport, resolverSupplier, config, null);
    }

    RedisMessageDispatcher(RedisTopicPublisher topicPublisher,
                           RedisMessageTransport messageTransport,
                           Supplier<AgentResolver> resolverSupplier,
                           RedisMessagingConfig config,
                           DeadLetterQueue deadLetters) {
        this.topicPublisher   = Objects.requireNonNull(topicPublisher,   "topicPublisher");
        this.messageTransport = Objects.requireNonNull(messageTransport, "messageTransport");
        this.resolverSupplier = resolverSupplier != null ? resolverSupplier : () -> null;
        this.config           = Objects.requireNonNull(config,           "config");
        this.deadLetters      = deadLetters != null ? deadLetters : DeadLetterQueue.noop();
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

        // Local fast-path: agent lives on this node. Redis is never touched, so there is no
        // stream entry to leave unacknowledged and none of the redelivery machinery applies -
        // this is the one delivery path on this transport that has to dead-letter for itself.
        // The returned future still fails, so the sender learns too.
        var localHandlers = directHandlers.get(receiverId);
        if (localHandlers != null && !localHandlers.isEmpty()) {
            return deliverLocally(localHandlers, msg).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    var cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause()
                            : failure;
                    deadLetters.record(DeadLetter.of(msg, receiverId, cause));
                }
            });
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
    // LocalEndpointProvider
    // -------------------------------------------------------------------------

    /**
     * Advertises this node's Redis stream as the way to reach agents registered here.
     *
     * <p>The {@code nodeId} is the one this dispatcher's own consumer loop listens on
     * ({@code <prefix>:node:<nodeId>}), which is what makes the advertised endpoint truthful:
     * a peer routing to it via {@link #sendTo(Message)} writes to the very stream this instance
     * reads. Deriving it from any other source — a separate runtime setting, say — would let the
     * two drift, and the symptom of a drift is a message that disappears with no error.
     *
     * @return always present; the transport type is {@code "redis"}
     */
    @Override
    public Optional<AgentEndpoint> localEndpoint() {
        return Optional.of(new AgentEndpoint(config.nodeId(), "redis", Map.of()));
    }

    // -------------------------------------------------------------------------
    // DirectReceiver
    // -------------------------------------------------------------------------

    /**
     * Registers {@code handler} to receive direct messages addressed to {@code localAgentId}.
     *
     * <p>On the first call this method starts a consumer loop on the local node stream
     * ({@code <prefix>:node:<nodeId>}). Subsequent calls add handlers to the local
     * routing table; the single consumer loop dispatches each incoming message to
     * <strong>every</strong> handler registered for its {@code receiverId}.
     *
     * <p>Registering a second handler for the same agent does not replace the first —
     * an agent normally has more than one (its own direct-message subscription and, when
     * dialogue is enabled, {@code DialogueCapability}'s). This matches the behaviour of
     * the in-memory dispatcher.
     *
     * @param localAgentId the agent id to route to, must not be null or blank
     * @param handler      the handler to register, must not be null
     * @return a subscription whose {@code unsubscribe()} removes <em>this</em> handler
     *         from the routing table, leaving any others in place (the node-stream
     *         consumer loop continues running)
     * @throws NullPointerException     if localAgentId or handler is null
     * @throws IllegalArgumentException if localAgentId is blank
     */
    @Override
    public Subscription subscribeRecipient(String localAgentId, MessageHandler handler) {
        Objects.requireNonNull(localAgentId, "localAgentId");
        Objects.requireNonNull(handler,      "handler");
        if (localAgentId.isBlank()) {
            throw new IllegalArgumentException("localAgentId must not be blank");
        }

        directHandlers.computeIfAbsent(localAgentId, id -> new CopyOnWriteArrayList<>()).add(handler);
        ensureNodeStreamRunning();

        return Subscription.of(localAgentId, () -> directHandlers.computeIfPresent(
                localAgentId,
                (id, handlers) -> {
                    handlers.remove(handler);
                    // Drop the entry entirely once the last handler goes, so sendTo falls
                    // back to the resolver instead of matching an empty local route.
                    return handlers.isEmpty() ? null : handlers;
                }));
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

    /**
     * Routes an entry off the node stream to the agent it names.
     *
     * <p>An undeliverable message completes <em>exceptionally</em>. That is the whole fix: a
     * completed future is how the consumer loop is told to acknowledge, so returning one here
     * used to acknowledge a message nobody had handled and drop it — silently, on the one
     * transport that has a dead-letter stream, and in direct contradiction of the adapter's
     * own documented claim that the chain runs all the way to the agent's code.
     *
     * <p>Failing instead puts the entry back through the ordinary path: it stays in the PEL,
     * is redelivered after {@code pendingEntriesTimeoutMs}, and reaches the DLQ only once
     * {@code maxDeliveryAttempts} is exhausted. The redelivery is not incidental — an agent
     * that has not finished subscribing is a real race at startup, and this gives it the same
     * window every other transient failure gets.
     */
    private CompletableFuture<Void> routeToLocalAgent(Message msg) {
        var receiverId = msg.receiverId();
        if (receiverId == null) {
            log.warn("Direct message {} arrived with null receiverId", msg.id());
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("direct message has no receiverId"));
        }
        var handlers = directHandlers.get(receiverId);
        if (handlers == null || handlers.isEmpty()) {
            log.warn("No local handler for receiverId='{}' for message {}", receiverId, msg.id());
            return CompletableFuture.failedFuture(new AgentNotFoundException(receiverId));
        }
        return deliverLocally(handlers, msg);
    }

    /**
     * Invokes every handler subscribed for a local agent and completes when all of them do.
     *
     * <p>Each handler is invoked even if an earlier one fails, so one misbehaving subscriber
     * cannot suppress delivery to the others; the returned future still reports the failure.
     *
     * @param handlers the subscribed handlers, must not be null or empty
     * @param msg      the message to deliver, must not be null
     * @return a future completing when every handler has completed
     */
    private CompletableFuture<Void> deliverLocally(List<MessageHandler> handlers, Message msg) {
        // Iterate, not index: the list is copy-on-write, so only its iterator is a
        // stable snapshot against a concurrent unsubscribe.
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var handler : handlers) {
            try {
                futures.add(handler.handle(msg));
            } catch (RuntimeException e) {
                futures.add(CompletableFuture.failedFuture(e));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
}
