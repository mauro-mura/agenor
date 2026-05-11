package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.directory.AgentResolver;
import dev.jentic.core.telemetry.JenticTelemetry;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Factory for creating Redis messaging adapter instances.
 *
 * <p>Use the fluent {@link #builder()} to configure and build a pair of
 * {@link RedisTopicPublisher} and {@link RedisMessageTransport} that share
 * a single Lettuce connection lifecycle:
 *
 * <pre>{@code
 * var factory = RedisMessagingFactory.builder()
 *     .uri("redis://localhost:6379")
 *     .build();
 *
 * RedisTopicPublisher  publisher  = factory.topicPublisher();
 * RedisMessageTransport transport = factory.messageTransport();
 *
 * // Wire into JenticRuntime.Builder ...
 *
 * // On shutdown:
 * factory.close();
 * }</pre>
 *
 * <p>Requires Lettuce ({@code io.lettuce:lettuce-core}) on the classpath — see ADR-021.
 *
 * @since 0.21.0
 */
public final class RedisMessagingFactory implements AutoCloseable {

    private final RedisStreamClient streamClient;
    private final RedisTopicPublisher topicPublisher;
    private final RedisMessageTransport messageTransport;
    private final RedisMessagingConfig config;

    private RedisMessagingFactory(RedisStreamClient streamClient,
                                  RedisTopicPublisher topicPublisher,
                                  RedisMessageTransport messageTransport,
                                  RedisMessagingConfig config) {
        this.streamClient    = streamClient;
        this.topicPublisher  = topicPublisher;
        this.messageTransport = messageTransport;
        this.config           = config;
    }

    /**
     * Returns a {@link RedisMessageDispatcher} for single-JVM deployments.
     *
     * <p>The dispatcher handles same-node {@code sendTo} calls via a local handler
     * map (no Redis hop). Cross-node delivery requires an {@link AgentResolver} —
     * use {@link #messageDispatcher(Supplier)} for multi-node setups.
     *
     * @return a new dispatcher backed by this factory's streams; never {@code null}
     * @since 0.21.0
     */
    public RedisMessageDispatcher messageDispatcher() {
        return new RedisMessageDispatcher(topicPublisher, messageTransport, null, config);
    }

    /**
     * Returns a {@link RedisMessageDispatcher} that uses the supplied
     * {@link AgentResolver} for cross-node {@code sendTo} routing.
     *
     * <p>The resolver is fetched lazily on each {@code sendTo} call, so it is safe
     * to pass a {@code Supplier} that resolves to a bean created after this factory
     * (e.g. an {@code ObjectProvider} in a Spring context).
     *
     * @param resolverSupplier supplier for the {@link AgentResolver}; may return
     *                         {@code null} if cross-node delivery is not needed
     * @return a new dispatcher; never {@code null}
     * @since 0.21.0
     */
    public RedisMessageDispatcher messageDispatcher(Supplier<AgentResolver> resolverSupplier) {
        return new RedisMessageDispatcher(topicPublisher, messageTransport, resolverSupplier, config);
    }

    /**
     * Returns the {@link RedisTopicPublisher} implementing {@code TopicPublisher}
     * and {@code TopicSubscriber}.
     *
     * @return never {@code null}
     */
    public RedisTopicPublisher topicPublisher() { return topicPublisher; }

    /**
     * Returns the {@link RedisMessageTransport} implementing {@code MessageTransport}.
     *
     * @return never {@code null}
     */
    public RedisMessageTransport messageTransport() { return messageTransport; }

    /**
     * Returns the {@link RedisMessagingConfig} in use by this factory.
     *
     * @return never {@code null}
     */
    public RedisMessagingConfig config() { return config; }

    /**
     * Stops all active consumer loops and closes the Lettuce connection.
     * Call this on application shutdown.
     */
    @Override
    public void close() {
        topicPublisher.close();
        messageTransport.close();
        streamClient.close();
    }

    /**
     * Returns a new {@link Builder}.
     *
     * @return a new builder; never {@code null}
     */
    public static Builder builder() { return new Builder(); }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link RedisMessagingFactory}.
     */
    public static final class Builder {

        private String uri;
        private String nodeId;
        private String consumerGroupPrefix;
        private long readBlockTimeoutMs;
        private int maxStreamLength;
        private long pendingEntriesTimeoutMs;
        private int maxDeliveryAttempts;
        private JenticTelemetry telemetry;

        private Builder() {}

        /**
         * Redis connection URI (required).
         *
         * @param uri e.g. {@code redis://localhost:6379} or {@code rediss://host:6380}
         * @return {@code this}
         */
        public Builder uri(String uri) {
            this.uri = Objects.requireNonNull(uri, "uri");
            return this;
        }

        /**
         * Unique identifier for this JVM node. Auto-generated if not set.
         *
         * @param nodeId non-blank node identifier
         * @return {@code this}
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * Prefix for Redis stream keys and consumer group names (default: {@code jentic}).
         *
         * @param prefix non-blank prefix
         * @return {@code this}
         */
        public Builder consumerGroupPrefix(String prefix) {
            this.consumerGroupPrefix = prefix;
            return this;
        }

        /**
         * How long {@code XREADGROUP BLOCK} waits before returning empty (default: 2 000 ms).
         *
         * @param ms positive duration in milliseconds
         * @return {@code this}
         */
        public Builder readBlockTimeoutMs(long ms) {
            this.readBlockTimeoutMs = ms;
            return this;
        }

        /**
         * Maximum stream length before approximate trimming (default: 100 000).
         *
         * @param maxLen positive integer
         * @return {@code this}
         */
        public Builder maxStreamLength(int maxLen) {
            this.maxStreamLength = maxLen;
            return this;
        }

        /**
         * Idle time (ms) before an unacknowledged entry is eligible for redelivery (default: 30 000 ms).
         *
         * @param ms positive duration in milliseconds
         * @return {@code this}
         */
        public Builder pendingEntriesTimeoutMs(long ms) {
            this.pendingEntriesTimeoutMs = ms;
            return this;
        }

        /**
         * Number of delivery failures before a message is moved to the DLQ (default: 3).
         *
         * @param attempts positive integer
         * @return {@code this}
         */
        public Builder maxDeliveryAttempts(int attempts) {
            this.maxDeliveryAttempts = attempts;
            return this;
        }

        /**
         * Telemetry instance for OTel spans (default: noop).
         *
         * @param telemetry telemetry; {@code null} treated as noop
         * @return {@code this}
         */
        public Builder telemetry(JenticTelemetry telemetry) {
            this.telemetry = telemetry;
            return this;
        }

        /**
         * Builds a {@link RedisMessagingFactory}, connecting to Redis immediately.
         *
         * @return a started factory; caller must call {@link RedisMessagingFactory#close()} on shutdown
         * @throws NullPointerException if {@link #uri} was not set
         */
        public RedisMessagingFactory build() {
            var cfg = new RedisMessagingConfig(
                    uri, nodeId, consumerGroupPrefix,
                    readBlockTimeoutMs, maxStreamLength,
                    pendingEntriesTimeoutMs, maxDeliveryAttempts
            );
            var client    = new RedisStreamClient(cfg);
            client.start();
            var publisher  = new RedisTopicPublisher(client, cfg, telemetry);
            var transport  = new RedisMessageTransport(client, cfg, telemetry);
            return new RedisMessagingFactory(client, publisher, transport, cfg);
        }
    }
}
