package dev.agenor.core.memory;

import java.time.Instant;
import java.util.*;

/**
 * Represents a single memory entry stored in the memory system.
 *
 * <p>Each entry contains:
 * <ul>
 *   <li><b>content</b>: The actual memory content (text, data, etc.)</li>
 *   <li><b>metadata</b>: Additional attributes for filtering and organization</li>
 *   <li><b>createdAt</b>: Timestamp when the memory was created</li>
 *   <li><b>expiresAt</b>: Optional expiration time for automatic cleanup</li>
 *   <li><b>ownerId</b>: Agent that created this memory</li>
 *   <li><b>sharedWith</b>: Agents that have access to this memory</li>
 *   <li><b>tokenCount</b>: estimated token count for LLM context</li>
 * </ul>
 *
 * <p>Memory entries are immutable and thread-safe.
 *
 * <p>Example usage:
 * <pre>{@code
 * MemoryEntry entry = MemoryEntry.builder("Order #12345 processed successfully")
 *     .ownerId("order-processor-agent")
 *     .metadata("orderId", "12345")
 *     .metadata("status", "completed")
 *     .expiresAt(Instant.now().plus(Duration.ofDays(30)))
 *     .sharedWith("shipping-agent", "billing-agent")
 *     .build();
 * }</pre>
 *
 * @param content the actual memory content (text, data, etc.)
 * @param metadata additional attributes for filtering and organization
 * @param createdAt timestamp when the memory was created
 * @param expiresAt optional expiration time for automatic cleanup
 * @param ownerId agent that created this memory
 * @param sharedWith agents that have access to this memory
 * @param tokenCount estimated token count for LLM context
 *
 * @since 0.6.0
 */
public record MemoryEntry(
    String content,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant expiresAt,
    String ownerId,
    Set<String> sharedWith,
    int tokenCount
) {

    /**
     * Compact constructor with validation and defensive copying.
     */
    public MemoryEntry {
        Objects.requireNonNull(content, "Content cannot be null");
        Objects.requireNonNull(createdAt, "Created timestamp cannot be null");

        // Defensive copies to ensure immutability
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        sharedWith = sharedWith != null ? Set.copyOf(sharedWith) : Set.of();

        // Validation
        if (content.isBlank()) {
            throw new IllegalArgumentException("Content cannot be blank");
        }

        if (expiresAt != null && expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Expiration time cannot be before creation time");
        }

        if (tokenCount < 0) {
            throw new IllegalArgumentException("Token count cannot be negative");
        }
    }

    /**
     * Checks if this memory entry has expired.
     *
     * @return true if expired, false otherwise
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this memory is owned by the specified agent.
     *
     * @param agentId the agent ID to check
     * @return true if this agent owns the memory
     */
    public boolean isOwnedBy(String agentId) {
        return ownerId != null && ownerId.equals(agentId);
    }

    /**
     * Checks if this memory is shared with the specified agent.
     *
     * @param agentId the agent ID to check
     * @return true if this agent has access to the memory
     */
    public boolean isSharedWith(String agentId) {
        return sharedWith.contains(agentId);
    }

    /**
     * Checks if the specified agent can access this memory.
     *
     * @param agentId the agent ID to check
     * @return true if the agent is owner or has shared access
     */
    public boolean canAccess(String agentId) {
        return isOwnedBy(agentId) || isSharedWith(agentId);
    }

    /**
     * Gets a metadata value cast to the specified type.
     *
     * @param key the metadata key
     * @param type the expected type
     * @param <T> the type parameter
     * @return the metadata value, or null if not found
     * @throws ClassCastException if the value cannot be cast to the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = metadata.get(key);
        return value != null ? (T) value : null;
    }

    /**
     * Creates a new builder for constructing memory entries.
     *
     * @param content the memory content
     * @return a new builder instance
     */
    public static Builder builder(String content) {
        return new Builder(content);
    }

    /**
     * Builder for creating MemoryEntry instances.
     */
    public static class Builder {
        private final String content;
        private final Map<String, Object> metadata = new HashMap<>();
        private Instant createdAt = Instant.now();
        private Instant expiresAt;
        private String ownerId;
        private final Set<String> sharedWith = new HashSet<>();
        private int tokenCount = 0;

        private Builder(String content) {
            this.content = content;
        }

        /**
         * Adds a metadata entry.
         *
         * @param key the metadata key
         * @param value the metadata value
         * @return this builder
         */
        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Sets multiple metadata entries at once.
         *
         * @param metadata map of metadata entries
         * @return this builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        /**
         * Sets the creation timestamp.
         *
         * @param createdAt the creation timestamp
         * @return this builder
         */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * Sets the expiration timestamp.
         *
         * @param expiresAt the expiration timestamp
         * @return this builder
         */
        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /**
         * Sets the owner agent ID.
         *
         * @param ownerId the owner agent ID
         * @return this builder
         */
        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        /**
         * Adds agents that this memory is shared with.
         *
         * @param agentIds the agent IDs to share with
         * @return this builder
         */
        public Builder sharedWith(String... agentIds) {
            this.sharedWith.addAll(Arrays.asList(agentIds));
            return this;
        }

        /**
         * Adds a collection of agents that this memory is shared with.
         *
         * @param agentIds the agent IDs to share with
         * @return this builder
         */
        public Builder sharedWith(Collection<String> agentIds) {
            this.sharedWith.addAll(agentIds);
            return this;
        }

        /**
         * Sets the estimated token count for LLM context.
         *
         * @param tokenCount the estimated token count (must be >= 0)
         * @return this builder
         * @throws IllegalArgumentException if tokenCount is negative
         */
        public Builder tokenCount(int tokenCount) {  // ✅ ADDED in Day 2
            if (tokenCount < 0) {
                throw new IllegalArgumentException("Token count cannot be negative");
            }
            this.tokenCount = tokenCount;
            return this;
        }

        /**
         * Builds the MemoryEntry instance.
         *
         * @return a new immutable MemoryEntry
         */
        public MemoryEntry build() {
            return new MemoryEntry(
                content,
                metadata,
                createdAt,
                expiresAt,
                ownerId,
                sharedWith,
                tokenCount
            );
        }
    }
}
