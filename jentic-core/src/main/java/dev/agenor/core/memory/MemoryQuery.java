package dev.agenor.core.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Query parameters for searching memory entries.
 *
 * <p>Example:
 * <pre>{@code
 * MemoryQuery query = MemoryQuery.builder()
 *     .text("customer order")
 *     .scope(MemoryScope.LONG_TERM)
 *     .ownerId("order-agent")
 *     .filter("status", "completed")
 *     .limit(10)
 *     .build();
 * List<MemoryEntry> results = memoryStore.search(query).join();
 * }</pre>
 *
 * @param text    optional full-text or substring filter; null means no text constraint
 * @param scope   memory scope to search ({@link MemoryScope#SHORT_TERM} or
 *                {@link MemoryScope#LONG_TERM}); must not be null
 * @param ownerId optional filter by owning agent ID; null means any owner
 * @param filters immutable map of metadata key/value filters; empty if null
 * @param limit   maximum number of results to return; must be between 1 and 1000
 * @since 0.6.0
 */
public record MemoryQuery(
    String text,
    MemoryScope scope,
    String ownerId,
    Map<String, Object> filters,
    int limit
) {

    /**
     * Compact constructor with validation and defensive copying.
     */
    public MemoryQuery {
        Objects.requireNonNull(scope, "Scope cannot be null");

        // Defensive copy
        filters = filters != null ? Map.copyOf(filters) : Map.of();

        // Validation
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive, got: " + limit);
        }

        if (limit > 1000) {
            throw new IllegalArgumentException("Limit cannot exceed 1000, got: " + limit);
        }
    }

    /**
     * Checks if this query has a text filter.
     *
     * @return true if text search is specified
     */
    public boolean hasTextFilter() {
        return text != null && !text.isBlank();
    }

    /**
     * Checks if this query has an owner filter.
     *
     * @return true if owner ID is specified
     */
    public boolean hasOwnerFilter() {
        return ownerId != null && !ownerId.isBlank();
    }

    /**
     * Checks if this query has metadata filters.
     *
     * @return true if any filters are specified
     */
    public boolean hasMetadataFilters() {
        return !filters.isEmpty();
    }

    /**
     * Gets a specific filter value cast to the specified type.
     *
     * @param key the filter key
     * @param type the expected type
     * @param <T> the type parameter
     * @return the filter value, or null if not found
     * @throws ClassCastException if the value cannot be cast to the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> T getFilter(String key, Class<T> type) {
        Object value = filters.get(key);
        return value != null ? (T) value : null;
    }

    /**
     * Creates a new builder for constructing queries.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating MemoryQuery instances.
     */
    public static class Builder {
        private String text;
        private MemoryScope scope = MemoryScope.SHORT_TERM;
        private String ownerId;
        private final Map<String, Object> filters = new HashMap<>();
        private int limit = 10;

        /**
         * Sets the text search term.
         *
         * @param text the search text
         * @return this builder
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * Sets the memory scope to search in.
         *
         * @param scope the memory scope
         * @return this builder
         */
        public Builder scope(MemoryScope scope) {
            this.scope = scope;
            return this;
        }

        /**
         * Sets the owner ID filter.
         *
         * @param ownerId the owner agent ID
         * @return this builder
         */
        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        /**
         * Adds a metadata filter.
         *
         * @param key the metadata key to filter by
         * @param value the expected value
         * @return this builder
         */
        public Builder filter(String key, Object value) {
            this.filters.put(key, value);
            return this;
        }

        /**
         * Sets multiple metadata filters at once.
         *
         * @param filters map of filter entries
         * @return this builder
         */
        public Builder filters(Map<String, Object> filters) {
            this.filters.putAll(filters);
            return this;
        }

        /**
         * Sets the maximum number of results to return.
         *
         * @param limit the result limit (1-1000)
         * @return this builder
         */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * Builds the MemoryQuery instance.
         *
         * @return a new immutable MemoryQuery
         */
        public MemoryQuery build() {
            return new MemoryQuery(
                text,
                scope,
                ownerId,
                filters,
                limit
            );
        }
    }
}
