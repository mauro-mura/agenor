package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.deadletter.DeadLetter;
import dev.agenor.core.deadletter.DeadLetterQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link DeadLetterQueue} backed by the dead-letter streams the Redis adapter already writes.
 *
 * <p>Nothing about when a message is dead-lettered changes here: the consumer loop still
 * redelivers an unacknowledged entry until {@code maxDeliveryAttempts} is exhausted. What
 * changes is that the result stopped being write-only. Before 0.32.0 the entries went to
 * {@code <stream>:dlq}, {@code dlqKey} was package-private, and the documentation said plainly
 * that there was no way to read them back — the adapter's own integration test had to
 * reassemble the Redis key from string literals and call {@code XLEN} on its own connection.
 *
 * <p><strong>Reads span the whole deployment.</strong> {@link #recent(int)} scans the keyspace
 * for every {@code <prefix>:*:dlq} stream and merges them newest-first, so it shows what any
 * node gave up on rather than only this one. That is the point of a durable dead-letter store
 * and it is also its cost: this is an operator path, not a hot one.
 *
 * <p>Entries carry the reason and the attempt count as of 0.32.0. One written by an earlier
 * version has neither, and is read back with the attempt count this deployment is configured
 * for and a reason that says where the gap comes from — a guess that announces itself rather
 * than one that reads like a fact.
 *
 * @since 0.32.0
 */
public final class RedisDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisDeadLetterQueue.class);

    static final String FIELD_SOURCE_STREAM = "dlq_source_stream";
    static final String FIELD_SOURCE_ID     = "dlq_source_id";
    static final String FIELD_REASON        = "dlq_reason";
    static final String FIELD_ATTEMPTS      = "dlq_attempts";
    static final String FIELD_AT            = "dlq_at";

    private static final String REASON_UNKNOWN =
            "unknown (entry predates the reason being recorded)";

    private final RedisStreamClient client;
    private final RedisMessagingConfig config;

    RedisDeadLetterQueue(RedisStreamClient client, RedisMessagingConfig config) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void record(DeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter");
        record(sourceStreamFor(deadLetter.message()), null, deadLetter);
    }

    /**
     * Writes an entry against a stream the caller already knows.
     *
     * <p>The consumer loop reads one stream and knows its key exactly, so it passes it rather
     * than letting {@link #sourceStreamFor(Message)} derive it a second time and risk the two
     * disagreeing. {@code sourceEntryId} preserves which Redis entry this was.
     */
    void record(String sourceStreamKey, String sourceEntryId, DeadLetter deadLetter) {
        var fields = new HashMap<>(MessageCodec.encode(deadLetter.message()));
        fields.put(FIELD_SOURCE_STREAM, sourceStreamKey);
        if (sourceEntryId != null) fields.put(FIELD_SOURCE_ID, sourceEntryId);
        fields.put(FIELD_REASON,   deadLetter.reason());
        fields.put(FIELD_ATTEMPTS, Integer.toString(deadLetter.attempts()));
        fields.put(FIELD_AT,       deadLetter.deadLetteredAt().toString());

        var dlqKey = config.dlqKey(sourceStreamKey);
        try {
            client.xaddDlq(dlqKey, fields);
            log.warn("Dead-lettered message {} from '{}' to '{}' after {} attempt(s): {}",
                    deadLetter.message().id(), sourceStreamKey, dlqKey,
                    deadLetter.attempts(), deadLetter.reason());
        } catch (Exception e) {
            // The message is already lost; a throw here would only lose the record of it too.
            log.error("Failed to write message {} to DLQ '{}': {}",
                    deadLetter.message().id(), dlqKey, e.getMessage(), e);
        }
    }

    @Override
    public List<DeadLetter> recent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive, was " + limit);

        var out = new ArrayList<DeadLetter>();
        for (var dlqKey : client.scanDlqKeys()) {
            for (var entry : client.xrevrange(dlqKey, limit)) {
                var decoded = decode(entry.getBody(), dlqKey);
                if (decoded != null) out.add(decoded);
            }
        }
        out.sort(Comparator.comparing(DeadLetter::deadLetteredAt).reversed());
        return Collections.unmodifiableList(out.size() > limit ? out.subList(0, limit) : out);
    }

    /**
     * Which stream a message would have been consumed from.
     *
     * <p>The same rule the publisher applies when it sends: a message addressed to an agent
     * travels through the receiving node's stream, and one without a recipient through its
     * topic's.
     */
    private String sourceStreamFor(Message message) {
        return message.receiverId() != null && !message.receiverId().isBlank()
                ? config.nodeStreamKey(config.nodeId())
                : config.topicStreamKey(message.topic() == null ? "" : message.topic());
    }

    private DeadLetter decode(Map<String, String> body, String dlqKey) {
        try {
            var message  = MessageCodec.decode(body);
            var reason   = body.getOrDefault(FIELD_REASON, REASON_UNKNOWN);
            var attempts = parseAttempts(body.get(FIELD_ATTEMPTS));
            var at       = parseInstant(body.get(FIELD_AT), message);
            return new DeadLetter(message, reason, message.receiverId(), attempts, at);
        } catch (Exception e) {
            // One unreadable entry must not hide the rest of the queue.
            log.warn("Skipping unreadable dead-letter entry in '{}': {}", dlqKey, e.getMessage());
            return null;
        }
    }

    private int parseAttempts(String raw) {
        if (raw == null || raw.isBlank()) return config.maxDeliveryAttempts();
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return config.maxDeliveryAttempts();
        }
    }

    private Instant parseInstant(String raw, Message message) {
        if (raw != null && !raw.isBlank()) {
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                // fall through to the message's own timestamp
            }
        }
        return message.timestamp() != null ? message.timestamp() : Instant.EPOCH;
    }
}
