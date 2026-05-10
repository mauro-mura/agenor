package dev.jentic.adapters.messaging.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("RedisMessagingConfig")
class RedisMessagingConfigTest {

    @Test
    @DisplayName("null URI throws NullPointerException")
    void constructor_nullUri_throws() {
        assertThatNullPointerException().isThrownBy(() ->
                new RedisMessagingConfig(null, null, null, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("blank nodeId is replaced with a generated UUID")
    void constructor_blankNodeId_generated() {
        var cfg = new RedisMessagingConfig("redis://localhost", "", null, 0, 0, 0, 0);
        assertThat(cfg.nodeId()).isNotBlank();
    }

    @Test
    @DisplayName("null nodeId is replaced with a generated UUID")
    void constructor_nullNodeId_generated() {
        var cfg = new RedisMessagingConfig("redis://localhost", null, null, 0, 0, 0, 0);
        assertThat(cfg.nodeId()).isNotBlank();
    }

    @Test
    @DisplayName("blank consumerGroupPrefix defaults to 'jentic'")
    void constructor_blankPrefix_defaultsToJentic() {
        var cfg = new RedisMessagingConfig("redis://localhost", "node-1", "", 0, 0, 0, 0);
        assertThat(cfg.consumerGroupPrefix()).isEqualTo("jentic");
    }

    @Test
    @DisplayName("non-positive readBlockTimeoutMs defaults to 2000")
    void constructor_nonPositiveBlockTimeout_defaults() {
        var cfg = new RedisMessagingConfig("redis://localhost", "n", "p", 0, 0, 0, 0);
        assertThat(cfg.readBlockTimeoutMs()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("non-positive maxStreamLength defaults to 100000")
    void constructor_nonPositiveMaxStreamLength_defaults() {
        var cfg = new RedisMessagingConfig("redis://localhost", "n", "p", 1, 0, 0, 0);
        assertThat(cfg.maxStreamLength()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("non-positive pendingEntriesTimeoutMs defaults to 30000")
    void constructor_nonPositivePendingTimeout_defaults() {
        var cfg = new RedisMessagingConfig("redis://localhost", "n", "p", 1, 1, 0, 0);
        assertThat(cfg.pendingEntriesTimeoutMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("non-positive maxDeliveryAttempts defaults to 3")
    void constructor_nonPositiveMaxAttempts_defaults() {
        var cfg = new RedisMessagingConfig("redis://localhost", "n", "p", 1, 1, 1, 0);
        assertThat(cfg.maxDeliveryAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("explicit values are preserved when positive")
    void constructor_explicitPositiveValues_preserved() {
        var cfg = new RedisMessagingConfig("redis://localhost", "my-node", "myapp", 500, 5_000, 10_000, 5);
        assertThat(cfg.readBlockTimeoutMs()).isEqualTo(500L);
        assertThat(cfg.maxStreamLength()).isEqualTo(5_000);
        assertThat(cfg.pendingEntriesTimeoutMs()).isEqualTo(10_000L);
        assertThat(cfg.maxDeliveryAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("topicStreamKey uses prefix and topic name")
    void topicStreamKey_correctPattern() {
        var cfg = config("myapp", "node-1");
        assertThat(cfg.topicStreamKey("orders.created")).isEqualTo("myapp:topic:orders.created");
    }

    @Test
    @DisplayName("nodeStreamKey uses prefix and nodeId")
    void nodeStreamKey_correctPattern() {
        var cfg = config("myapp", "node-42");
        assertThat(cfg.nodeStreamKey("node-42")).isEqualTo("myapp:node:node-42");
    }

    @Test
    @DisplayName("dlqKey appends :dlq to source stream key")
    void dlqKey_appendsSuffix() {
        var cfg = config("jentic", "n");
        assertThat(cfg.dlqKey("jentic:topic:orders")).isEqualTo("jentic:topic:orders:dlq");
    }

    @Test
    @DisplayName("topicConsumerGroup includes prefix and subscriptionId")
    void topicConsumerGroup_correctPattern() {
        var cfg = config("jentic", "n");
        assertThat(cfg.topicConsumerGroup("sub-123")).isEqualTo("jentic:cg:sub-123");
    }

    @Test
    @DisplayName("nodeConsumerGroup is fixed within the prefix")
    void nodeConsumerGroup_correctPattern() {
        var cfg = config("myapp", "n");
        assertThat(cfg.nodeConsumerGroup()).isEqualTo("myapp:cg:node");
    }

    @Test
    @DisplayName("consumerName includes prefix and nodeId")
    void consumerName_correctPattern() {
        var cfg = config("myapp", "node-7");
        assertThat(cfg.consumerName()).isEqualTo("myapp:consumer:node-7");
    }

    // -------------------------------------------------------------------------

    private static RedisMessagingConfig config(String prefix, String nodeId) {
        return new RedisMessagingConfig("redis://localhost", nodeId, prefix, 100, 1000, 1000, 3);
    }
}
