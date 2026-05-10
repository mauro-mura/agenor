package dev.jentic.adapters.messaging.redis;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable configuration for the Redis messaging adapter.
 *
 * <p>Use {@link RedisMessagingFactory#builder()} to construct instances.
 *
 * @param uri                     Redis connection URI (e.g. {@code redis://localhost:6379})
 * @param nodeId                  Unique identifier for this JVM node; auto-generated if blank
 * @param consumerGroupPrefix     Prefix for Redis consumer group and key names (default: {@code jentic})
 * @param readBlockTimeoutMs      How long {@code XREADGROUP BLOCK} waits before returning empty
 * @param maxStreamLength         Maximum entries per stream before approximate trimming
 * @param pendingEntriesTimeoutMs Idle time before a pending entry is eligible for redelivery
 * @param maxDeliveryAttempts     Failed delivery attempts before a message is moved to the DLQ
 * @since 0.21.0
 */
public record RedisMessagingConfig(
        String uri,
        String nodeId,
        String consumerGroupPrefix,
        long readBlockTimeoutMs,
        int maxStreamLength,
        long pendingEntriesTimeoutMs,
        int maxDeliveryAttempts
) {
    public RedisMessagingConfig {
        Objects.requireNonNull(uri, "uri");
        if (nodeId == null || nodeId.isBlank())           nodeId = UUID.randomUUID().toString();
        if (consumerGroupPrefix == null || consumerGroupPrefix.isBlank()) consumerGroupPrefix = "jentic";
        if (readBlockTimeoutMs <= 0)      readBlockTimeoutMs = 2_000L;
        if (maxStreamLength <= 0)         maxStreamLength    = 100_000;
        if (pendingEntriesTimeoutMs <= 0) pendingEntriesTimeoutMs = 30_000L;
        if (maxDeliveryAttempts <= 0)     maxDeliveryAttempts = 3;
    }

    /** Stream key for a named topic. */
    String topicStreamKey(String topic) {
        return consumerGroupPrefix + ":topic:" + topic;
    }

    /** Stream key for a node's inbound point-to-point queue. */
    String nodeStreamKey(String nId) {
        return consumerGroupPrefix + ":node:" + nId;
    }

    /** DLQ stream key for a given source stream. */
    String dlqKey(String sourceStreamKey) {
        return sourceStreamKey + ":dlq";
    }

    /** Consumer group name for a topic subscription identified by {@code subscriptionId}. */
    String topicConsumerGroup(String subscriptionId) {
        return consumerGroupPrefix + ":cg:" + subscriptionId;
    }

    /** Consumer group name for the local node's inbound stream (single group per node). */
    String nodeConsumerGroup() {
        return consumerGroupPrefix + ":cg:node";
    }

    /** Consumer name used in all consumer groups on this node. */
    String consumerName() {
        return consumerGroupPrefix + ":consumer:" + nodeId;
    }
}
