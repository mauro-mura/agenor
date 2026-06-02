package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.messaging.TopicPublisher;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.SpanStatus;
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
 * Redis Streams implementation of {@link TopicPublisher} and {@link TopicSubscriber}.
 *
 * <p>Each topic maps to a Redis Stream with key {@code <prefix>:topic:<topicName>}.
 * Each call to {@link #subscribeTopic} creates an independent consumer group so the
 * subscriber receives every message published to that topic (fan-out).
 *
 * <p>Requires Lettuce ({@code io.lettuce:lettuce-core}) on the classpath — see ADR-021.
 *
 * @since 0.21.0
 */
public final class RedisTopicPublisher implements TopicPublisher, TopicSubscriber, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisTopicPublisher.class);

    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final RedisStreamClient streamClient;
    private final RedisMessagingConfig config;
    private final AgenorTelemetry telemetry;

    // subscriptionId → ConsumerLoop
    private final Map<String, ConsumerLoop> activeLoops = new ConcurrentHashMap<>();

    RedisTopicPublisher(RedisStreamClient streamClient, RedisMessagingConfig config,
                        AgenorTelemetry telemetry) {
        this.streamClient = Objects.requireNonNull(streamClient, "streamClient");
        this.config       = Objects.requireNonNull(config, "config");
        this.telemetry    = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    // -------------------------------------------------------------------------
    // TopicPublisher
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> publish(Message msg) {
        Objects.requireNonNull(msg, "msg");
        var topic = msg.topic();
        if (topic == null || topic.isBlank())
            throw new IllegalArgumentException("msg.topic() must not be null or empty");

        return CompletableFuture.runAsync(() -> {
            var span = telemetry.spanBuilder("message.publish")
                    .setAttribute("message.topic",  topic)
                    .setAttribute("message.id",     orEmpty(msg.id()))
                    .setAttribute("agent.sender",   orEmpty(msg.senderId()))
                    .setAttribute("transport.type", "redis")
                    .startSpan();
            try {
                var streamKey = config.topicStreamKey(topic);
                var entryId   = streamClient.xadd(streamKey, MessageCodec.encode(msg));
                log.debug("Published msg {} to stream '{}' (entry {})", msg.id(), streamKey, entryId);
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
    // TopicSubscriber
    // -------------------------------------------------------------------------

    @Override
    public Subscription subscribeTopic(String topic, MessageHandler handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        if (topic.isBlank()) throw new IllegalArgumentException("topic must not be empty");

        var subscriptionId  = "redis-topic-" + UUID.randomUUID();
        var streamKey       = config.topicStreamKey(topic);
        var consumerGroup   = config.topicConsumerGroup(subscriptionId);
        var consumerName    = config.consumerName();

        var loop = new ConsumerLoop(streamKey, consumerGroup, consumerName,
        		handler, streamClient, config, telemetry);
        loop.start();
        activeLoops.put(subscriptionId, loop);

        log.debug("Subscribed to topic '{}' via stream '{}', group '{}' (sub: {})",
                topic, streamKey, consumerGroup, subscriptionId);

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
            log.debug("Cancelled topic subscription {}", id);
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
