package dev.agenor.adapters.messaging.redis;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages a Lettuce {@link RedisClient} and provides high-level stream operations
 * used by {@link RedisTopicPublisher} and {@link RedisMessageTransport}.
 *
 * <p>A single shared write connection handles non-blocking commands (XADD, XACK,
 * XGROUP CREATE). Each consumer loop receives its own connection for blocking XREADGROUP.
 */
final class RedisStreamClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamClient.class);

    private final RedisMessagingConfig config;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> writeConn;

    RedisStreamClient(RedisMessagingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    void start() {
        redisClient = RedisClient.create(config.uri());
        writeConn   = redisClient.connect();
        log.info("Redis messaging client connected to {}", config.uri());
    }

    @Override
    public void close() {
        if (writeConn != null)  { try { writeConn.close();   } catch (Exception e) { log.warn("Error closing write connection", e); } }
        if (redisClient != null){ try { redisClient.shutdown(); } catch (Exception e) { log.warn("Error shutting down Redis client", e); } }
        log.info("Redis messaging client closed");
    }

    /** Creates a dedicated connection for a blocking consumer loop. Caller is responsible for closing it. */
    StatefulRedisConnection<String, String> newConsumerConnection() {
        return redisClient.connect();
    }

    /**
     * Creates the consumer group on {@code streamKey} if it does not already exist,
     * using the shared write connection.
     *
     * <p>The group starts at offset {@code $} — only messages published after group
     * creation are delivered. MKSTREAM ensures the stream itself is created if absent.
     *
     * <p>This method is intentionally synchronous so that callers can rely on the
     * group existing before publishing; avoids a race between subscribe and publish.
     */
    void ensureConsumerGroup(String streamKey, String group) {
        try {
            writeConn.sync().xgroupCreate(
                    XReadArgs.StreamOffset.latest(streamKey),
                    group,
                    XGroupCreateArgs.Builder.mkstream()
            );
            log.debug("Created consumer group '{}' on stream '{}'", group, streamKey);
        } catch (RedisBusyException e) {
            log.trace("Consumer group '{}' already exists on '{}'", group, streamKey);
        }
    }

    /**
     * Appends a message to {@code streamKey} with approximate MAXLEN trimming.
     *
     * @return the Redis stream entry ID assigned by the server
     */
    String xadd(String streamKey, Map<String, String> fields) {
        var args = XAddArgs.Builder.maxlen(config.maxStreamLength()).approximateTrimming(true);
        return writeConn.sync().xadd(streamKey, args, fields);
    }

    /** Acknowledges a stream entry, removing it from the consumer group's PEL. */
    void xack(String streamKey, String consumerGroup, String streamEntryId) {
        writeConn.sync().xack(streamKey, consumerGroup, streamEntryId);
    }

    /**
     * Appends an entry to a dead-letter stream, with the same approximate MAXLEN trimming
     * every other stream gets.
     *
     * <p>The DLQ used to be the one stream written with a bare {@code XADD}, so it was also
     * the one stream nobody drains and nothing bounds. {@code maxStreamLength} is documented
     * as a per-stream limit and now actually is one.
     *
     * @return the entry ID assigned by the server
     */
    String xaddDlq(String dlqKey, Map<String, String> fields) {
        var args = XAddArgs.Builder.maxlen(config.maxStreamLength()).approximateTrimming(true);
        return writeConn.sync().xadd(dlqKey, args, fields);
    }

    /**
     * Returns up to {@code count} entries of {@code streamKey}, newest first.
     *
     * <p>Missing streams read back empty rather than failing: a deployment that has never
     * dead-lettered anything has no DLQ key at all.
     */
    List<StreamMessage<String, String>> xrevrange(String streamKey, int count) {
        try {
            return writeConn.sync().xrevrange(streamKey, Range.unbounded(), Limit.from(count));
        } catch (Exception e) {
            log.warn("Could not read stream '{}': {}", streamKey, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns every dead-letter stream key in the keyspace, across all nodes and topics.
     *
     * <p>A {@code SCAN} rather than a tracked set, because the point of a durable dead-letter
     * stream is seeing what <em>another</em> node gave up on, which a per-process set cannot
     * know. It is a keyspace scan and belongs on an operator path, not a hot one.
     */
    List<String> scanDlqKeys() {
        var keys = new ArrayList<String>();
        try {
            var args = ScanArgs.Builder.matches(config.consumerGroupPrefix() + ":*:dlq").limit(256);
            var cursor = writeConn.sync().scan(args);
            while (true) {
                keys.addAll(cursor.getKeys());
                if (cursor.isFinished()) break;
                cursor = writeConn.sync().scan(cursor, args);
            }
        } catch (Exception e) {
            log.warn("Could not scan for dead-letter streams: {}", e.getMessage());
        }
        return keys;
    }
}
