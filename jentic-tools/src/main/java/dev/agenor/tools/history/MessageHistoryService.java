package dev.agenor.tools.history;

import dev.agenor.core.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Thread-safe service for storing and querying recent message history.
 *
 * <p>Uses a ring buffer implementation with configurable max size.
 * When the buffer is full, oldest messages are automatically evicted.
 *
 * <p>All operations are thread-safe and can be called concurrently.
 *
 * @since 0.4.0
 */
public class MessageHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MessageHistoryService.class);

    public static final int DEFAULT_MAX_SIZE = 1000;
    public static final int MIN_SIZE = 10;
    public static final int MAX_SIZE = 100_000;

    private final ConcurrentLinkedDeque<StoredMessage> messages;
    private final int maxSize;
    private final AtomicInteger currentSize;
    private final ReentrantLock storeLock = new ReentrantLock();

    /**
     * Stored message wrapper with additional metadata.
     */
    public record StoredMessage(
            String id,
            String topic,
            String senderId,
            String receiverId,
            String correlationId,
            Object payload,
            Map<String, String> headers,
            Instant timestamp,
            Instant storedAt
    ) {
        /**
         * Creates a StoredMessage from a Message.
         */
        public static StoredMessage from(Message message) {
            return new StoredMessage(
                    message.id(),
                    message.topic(),
                    message.senderId(),
                    message.receiverId(),
                    message.correlationId(),
                    message.content(),
                    message.headers(),
                    message.timestamp(),
                    Instant.now()
            );
        }

        /**
         * Converts to a Map for JSON serialization.
         */
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put("topic", topic);
            map.put("senderId", senderId);
            map.put("receiverId", receiverId);
            map.put("correlationId", correlationId);
            map.put("payload", payload);
            map.put("headers", headers);
            map.put("timestamp", timestamp != null ? timestamp.toString() : null);
            map.put("storedAt", storedAt != null ? storedAt.toString() : null);
            return map;
        }
    }

    /**
     * Creates a MessageHistoryService with default max size.
     */
    public MessageHistoryService() {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * Creates a MessageHistoryService with specified max size.
     *
     * @param maxSize maximum number of messages to store
     * @throws IllegalArgumentException if maxSize is out of valid range
     */
    public MessageHistoryService(int maxSize) {
        if (maxSize < MIN_SIZE || maxSize > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "maxSize must be between " + MIN_SIZE + " and " + MAX_SIZE + ", got: " + maxSize);
        }
        this.maxSize = maxSize;
        this.messages = new ConcurrentLinkedDeque<>();
        this.currentSize = new AtomicInteger(0);
        log.info("MessageHistoryService initialized with maxSize={}", maxSize);
    }

    /**
     * Stores a message in the history buffer.
     *
     * <p>If the buffer is full, the oldest message is evicted.
     *
     * @param message the message to store, must not be null
     * @throws NullPointerException if message is null
     */
    public void store(Message message) {
        Objects.requireNonNull(message, "message cannot be null");

        var stored = StoredMessage.from(message);
        storeLock.lock();
        try {
            messages.addFirst(stored);
            int size = currentSize.incrementAndGet();
            if (size > maxSize) {
                var removed = messages.pollLast();
                if (removed != null) {
                    size = currentSize.decrementAndGet();
                    log.trace("Evicted oldest message: {}", removed.id());
                }
            }
            log.debug("Stored message: id={}, topic={}, size={}", message.id(), message.topic(), size);
        } finally {
            storeLock.unlock();
        }
    }

    /**
     * Gets the most recent messages.
     *
     * @param limit maximum number of messages to return
     * @return list of recent messages, newest first
     */
    public List<StoredMessage> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return messages.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Gets messages filtered by exact topic match.
     *
     * @param topic the topic to filter by
     * @return list of messages matching the topic, newest first
     */
    public List<StoredMessage> getByTopic(String topic) {
        if (topic == null || topic.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(m -> topic.equals(m.topic()))
                .collect(Collectors.toList());
    }

    /**
     * Gets messages filtered by topic pattern (supports wildcards).
     *
     * <p>Pattern syntax:
     * <ul>
     *   <li>{@code *} matches any sequence of characters</li>
     *   <li>{@code ?} matches any single character</li>
     * </ul>
     *
     * @param topicPattern the topic pattern (e.g., "orders.*", "events.?.created")
     * @return list of messages matching the pattern, newest first
     */
    public List<StoredMessage> findByTopicPattern(String topicPattern) {
        if (topicPattern == null || topicPattern.isEmpty()) {
            return List.of();
        }

        var regex = topicPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        var pattern = Pattern.compile("^" + regex + "$");

        return messages.stream()
                .filter(m -> m.topic() != null && pattern.matcher(m.topic()).matches())
                .collect(Collectors.toList());
    }

    /**
     * Gets messages filtered by sender ID.
     *
     * @param senderId the sender ID to filter by
     * @return list of messages from the sender, newest first
     */
    public List<StoredMessage> findBySender(String senderId) {
        if (senderId == null || senderId.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(m -> senderId.equals(m.senderId()))
                .collect(Collectors.toList());
    }

    /**
     * Gets messages within a time range.
     *
     * @param from start of range (inclusive), null for no lower bound
     * @param to end of range (inclusive), null for no upper bound
     * @return list of messages in the time range, newest first
     */
    public List<StoredMessage> findByTimeRange(Instant from, Instant to) {
        return messages.stream()
                .filter(m -> {
                    if (m.timestamp() == null) return false;
                    if (from != null && m.timestamp().isBefore(from)) return false;
                    if (to != null && m.timestamp().isAfter(to)) return false;
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Gets messages matching a custom predicate.
     *
     * @param predicate the filter predicate
     * @return list of matching messages, newest first
     */
    public List<StoredMessage> findByFilter(Predicate<StoredMessage> predicate) {
        Objects.requireNonNull(predicate, "predicate cannot be null");
        return messages.stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    /**
     * Gets a specific message by ID.
     *
     * @param messageId the message ID
     * @return the message if found, empty otherwise
     */
    public Optional<StoredMessage> findById(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return Optional.empty();
        }
        return messages.stream()
                .filter(m -> messageId.equals(m.id()))
                .findFirst();
    }

    /**
     * Clears all stored messages.
     */
    public void clear() {
        storeLock.lock();
        try {
            messages.clear();
            currentSize.set(0);
        } finally {
            storeLock.unlock();
        }
        log.info("Message history cleared");
    }

    /**
     * Returns the current number of stored messages.
     */
    public int size() {
        return currentSize.get();
    }

    /**
     * Returns the maximum capacity.
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Checks if the history is empty.
     */
    public boolean isEmpty() {
        return currentSize.get() == 0;
    }
}
