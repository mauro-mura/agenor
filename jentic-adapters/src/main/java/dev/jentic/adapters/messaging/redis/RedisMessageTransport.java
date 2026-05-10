package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.TransportEndpoint;
import dev.jentic.core.messaging.MessageTransport;
import dev.jentic.core.messaging.Subscription;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Redis Streams implementation of {@link MessageTransport}.
 *
 * <p>Point-to-point delivery uses a per-node stream with key
 * {@code <prefix>:node:<nodeId>}. The dispatcher resolves the recipient's
 * {@link dev.jentic.core.AgentEndpoint}, extracts the target node ID from
 * {@link TransportEndpoint#address()}, and calls {@link #send} to write to that
 * node's inbound stream. On the receiving side, {@link #subscribe} starts a
 * consumer loop that reads from the local node stream and invokes the handler.
 *
 * <p>Requires Lettuce ({@code io.lettuce:lettuce-core}) on the classpath — see ADR-021.
 *
 * @since 0.21.0
 */
public final class RedisMessageTransport implements MessageTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageTransport.class);

    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final RedisStreamClient streamClient;
    private final RedisMessagingConfig config;
    private final JenticTelemetry telemetry;

    // subscriptionId → ConsumerLoop
    private final Map<String, ConsumerLoop> activeLoops = new ConcurrentHashMap<>();

    RedisMessageTransport(RedisStreamClient streamClient, RedisMessagingConfig config,
                          JenticTelemetry telemetry) {
        this.streamClient = Objects.requireNonNull(streamClient, "streamClient");
        this.config       = Objects.requireNonNull(config, "config");
        this.telemetry    = telemetry != null ? telemetry : JenticTelemetry.noop();
    }

    // -------------------------------------------------------------------------
    // MessageTransport — send
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> send(TransportEndpoint dest, Message msg) {
        Objects.requireNonNull(dest, "dest");
        Objects.requireNonNull(msg, "msg");

        return CompletableFuture.runAsync(() -> {
            var span = telemetry.spanBuilder("transport.send")
                    .setAttribute("transport.type",     "redis")
                    .setAttribute("transport.endpoint", dest.address())
                    .setAttribute("message.id",         orEmpty(msg.id()))
                    .setAttribute("agent.sender",       orEmpty(msg.senderId()))
                    .startSpan();
            try {
                var streamKey = config.nodeStreamKey(dest.address());
                var entryId   = streamClient.xadd(streamKey, MessageCodec.encode(msg));
                log.debug("Sent msg {} to node stream '{}' (entry {})", msg.id(), streamKey, entryId);
                span.setStatus(SpanStatus.OK);
            } catch (Exception e) {
                span.recordException(e).setStatus(SpanStatus.ERROR);
                throw e;
            } finally {
                span.end();
            }
        }, VIRTUAL_EXECUTOR);
    }

    // -------------------------------------------------------------------------
    // MessageTransport — subscribe
    // -------------------------------------------------------------------------

    /**
     * Registers a handler for messages arriving at {@code local}.
     *
     * <p>{@link TransportEndpoint#address()} must be the node ID of this JVM (i.e.
     * {@code config.nodeId()}). The transport creates a single consumer group
     * ({@code <prefix>:cg:node}) on the node's inbound stream; all agents on this
     * node share that group so each message is processed exactly once per node.
     *
     * @param local   the local node endpoint; {@code address()} must equal {@link RedisMessagingConfig#nodeId()}
     * @param handler the handler to invoke for each incoming direct message
     * @return a subscription handle
     */
    @Override
    public Subscription subscribe(TransportEndpoint local, MessageHandler handler) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(handler, "handler");

        var nodeId        = local.address();
        var subscriptionId = "redis-transport-" + UUID.randomUUID();
        var streamKey      = config.nodeStreamKey(nodeId);
        var consumerGroup  = config.nodeConsumerGroup();
        var consumerName   = config.consumerName();

        var loop = new ConsumerLoop(streamKey, consumerGroup, consumerName, handler, streamClient, config);
        loop.start();
        activeLoops.put(subscriptionId, loop);

        log.debug("Subscribed node transport on stream '{}', group '{}' (sub: {})",
                streamKey, consumerGroup, subscriptionId);

        return Subscription.of(subscriptionId, () -> cancelSubscription(subscriptionId));
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        activeLoops.values().forEach(ConsumerLoop::stop);
        activeLoops.clear();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void cancelSubscription(String id) {
        var loop = activeLoops.remove(id);
        if (loop != null) {
            loop.stop();
            log.debug("Cancelled transport subscription {}", id);
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
