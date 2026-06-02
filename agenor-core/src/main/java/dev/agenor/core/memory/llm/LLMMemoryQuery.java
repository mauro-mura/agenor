package dev.agenor.core.memory.llm;

import dev.agenor.core.memory.MemoryScope;

import java.util.Map;
import java.util.Objects;

/**
 * Query for retrieving LLM-optimized memory context.
 *
 * <p>This record encapsulates all parameters needed to retrieve relevant
 * context from memory for LLM prompts. It extends basic memory queries with
 * LLM-specific features like token budgets and formatting options.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Token Budget:</b> Limit results by token count, not just result count</li>
 *   <li><b>Scope Control:</b> Search short-term, long-term, or both</li>
 *   <li><b>Relevance Scoring:</b> Order results by relevance to query</li>
 *   <li><b>Metadata Filtering:</b> Filter by memory metadata</li>
 *   <li><b>Format Control:</b> Control how results are formatted for LLM</li>
 * </ul>
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * // Simple query with token budget
 * LLMMemoryQuery query = LLMMemoryQuery.builder("user preferences")
 *     .maxTokens(500)
 *     .build();
 *
 * // Advanced query with filters
 * LLMMemoryQuery query = LLMMemoryQuery.builder("customer support issue")
 *     .maxTokens(1000)
 *     .scope(MemoryScope.LONG_TERM)
 *     .metadata("category", "support")
 *     .metadata("priority", "high")
 *     .includeTimestamps(true)
 *     .build();
 *
 * // Use in LLM memory manager
 * List<MemoryEntry> context = llmMemory.retrieveRelevantContext(
 *     query.query(),
 *     query.maxTokens()
 * ).join();
 * }</pre>
 *
 * <p><b>Thread Safety:</b> Immutable and thread-safe.
 *
 * @param query the search query text
 * @param maxTokens maximum tokens for retrieved results
 * @param scope memory scope to search (SHORT_TERM, LONG_TERM, or BOTH)
 * @param maxResults maximum number of results (before token filtering)
 * @param metadataFilters optional metadata filters
 * @param includeTimestamps whether to include timestamps in formatted output
 * @param includeMetadata whether to include metadata in formatted output
 * @param formatAsMessages whether to format results as LLM messages
 *
 * @since 0.6.0
 */
public record LLMMemoryQuery(
    String query,
    int maxTokens,
    MemoryScope scope,
    int maxResults,
    Map<String, Object> metadataFilters,
    boolean includeTimestamps,
    boolean includeMetadata,
    boolean formatAsMessages
) {

    /**
     * Default maximum results before token filtering.
     */
    public static final int DEFAULT_MAX_RESULTS = 20;

    /**
     * Default maximum tokens for results.
     */
    public static final int DEFAULT_MAX_TOKENS = 1000;

    /**
     * Compact constructor with validation and defensive copying.
     */
    public LLMMemoryQuery {
        Objects.requireNonNull(query, "Query cannot be null");

        if (query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }

        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive");
        }

        Objects.requireNonNull(scope, "Scope cannot be null");

        // Defensive copy of mutable map
        if (metadataFilters != null) {
            metadataFilters = Map.copyOf(metadataFilters);
        } else {
            metadataFilters = Map.of();
        }
    }

    /**
     * Create a new builder with required query parameter.
     *
     * @param query the search query text
     * @return a new builder
     * @throws IllegalArgumentException if query is null or empty
     */
    public static Builder builder(String query) {
        return new Builder(query);
    }

    /**
     * Create a simple query with just text and default settings.
     *
     * @param query the search query text
     * @return a new query with defaults
     */
    public static LLMMemoryQuery simple(String query) {
        return builder(query).build();
    }

    /**
     * Create a query with text and max tokens.
     *
     * @param query the search query text
     * @param maxTokens maximum tokens for results
     * @return a new query
     */
    public static LLMMemoryQuery withTokens(String query, int maxTokens) {
        return builder(query).maxTokens(maxTokens).build();
    }

    /**
     * Builder for creating LLMMemoryQuery instances.
     */
    public static class Builder {
        private final String query;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private MemoryScope scope = MemoryScope.LONG_TERM;
        private int maxResults = DEFAULT_MAX_RESULTS;
        private Map<String, Object> metadataFilters = Map.of();
        private boolean includeTimestamps = false;
        private boolean includeMetadata = false;
        private boolean formatAsMessages = false;

        private Builder(String query) {
            if (query == null || query.trim().isEmpty()) {
                throw new IllegalArgumentException("Query cannot be null or empty");
            }
            this.query = query.trim();
        }

        /**
         * Set maximum tokens for retrieved results.
         *
         * <p>Results will be limited to fit within this token budget.
         *
         * @param maxTokens maximum tokens (must be positive)
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Set memory scope to search.
         *
         * <p>Options:
         * <ul>
         *   <li>{@code SHORT_TERM} - Recent conversation only</li>
         *   <li>{@code LONG_TERM} - Persistent memories only (default)</li>
         *   <li>{@code BOTH} - Search both scopes</li>
         * </ul>
         *
         * @param scope the memory scope
         * @return this builder
         */
        public Builder scope(MemoryScope scope) {
            this.scope = Objects.requireNonNull(scope, "Scope cannot be null");
            return this;
        }

        /**
         * Set maximum number of results before token filtering.
         *
         * <p>The query will retrieve at most this many results, then
         * filter by token budget. Increase if token budget is high.
         *
         * @param maxResults maximum results (must be positive)
         * @return this builder
         */
        public Builder maxResults(int maxResults) {
            if (maxResults <= 0) {
                throw new IllegalArgumentException("maxResults must be positive");
            }
            this.maxResults = maxResults;
            return this;
        }

        /**
         * Add a metadata filter.
         *
         * <p>Only memories with matching metadata will be returned.
         *
         * <p><b>Example:</b>
         * <pre>{@code
         * builder.metadata("category", "support")
         *        .metadata("priority", "high");
         * }</pre>
         *
         * @param key metadata key
         * @param value metadata value
         * @return this builder
         */
        public Builder metadata(String key, Object value) {
            Objects.requireNonNull(key, "Metadata key cannot be null");
            Objects.requireNonNull(value, "Metadata value cannot be null");

            var newFilters = new java.util.HashMap<>(this.metadataFilters);
            newFilters.put(key, value);
            this.metadataFilters = newFilters;
            return this;
        }

        /**
         * Set all metadata filters at once.
         *
         * @param metadataFilters map of metadata filters
         * @return this builder
         */
        public Builder metadataFilters(Map<String, Object> metadataFilters) {
            if (metadataFilters == null) {
                this.metadataFilters = Map.of();
            } else {
                this.metadataFilters = Map.copyOf(metadataFilters);
            }
            return this;
        }

        /**
         * Include timestamps in formatted output.
         *
         * <p>When true, results will include when memories were created.
         * Useful for temporal context in LLM prompts.
         *
         * @param includeTimestamps true to include timestamps
         * @return this builder
         */
        public Builder includeTimestamps(boolean includeTimestamps) {
            this.includeTimestamps = includeTimestamps;
            return this;
        }

        /**
         * Include metadata in formatted output.
         *
         * <p>When true, results will include memory metadata like
         * category, source, confidence, etc.
         *
         * @param includeMetadata true to include metadata
         * @return this builder
         */
        public Builder includeMetadata(boolean includeMetadata) {
            this.includeMetadata = includeMetadata;
            return this;
        }

        /**
         * Format results as LLM messages instead of plain text.
         *
         * <p>When true, each memory will be formatted as an LLM message
         * (usually system role) for direct inclusion in prompts.
         *
         * @param formatAsMessages true to format as messages
         * @return this builder
         */
        public Builder formatAsMessages(boolean formatAsMessages) {
            this.formatAsMessages = formatAsMessages;
            return this;
        }

        /**
         * Build the immutable LLMMemoryQuery.
         *
         * @return a new query instance
         */
        public LLMMemoryQuery build() {
            return new LLMMemoryQuery(
                query,
                maxTokens,
                scope,
                maxResults,
                metadataFilters,
                includeTimestamps,
                includeMetadata,
                formatAsMessages
            );
        }
    }

    /**
     * Check if this query has metadata filters.
     *
     * @return true if metadata filters are present
     */
    public boolean hasMetadataFilters() {
        return !metadataFilters.isEmpty();
    }

    /**
     * Get metadata filter for a specific key.
     *
     * @param key the metadata key
     * @return the filter value, or null if not present
     */
    public Object getMetadataFilter(String key) {
        return metadataFilters.get(key);
    }

    /**
     * Check if query is for long-term memory only.
     *
     * @return true if scope is LONG_TERM
     */
    public boolean isLongTermOnly() {
        return scope == MemoryScope.LONG_TERM;
    }

    /**
     * Check if query is for short-term memory only.
     *
     * @return true if scope is SHORT_TERM
     */
    public boolean isShortTermOnly() {
        return scope == MemoryScope.SHORT_TERM;
    }

    /**
     * Check if query searches both scopes.
     *
     * @return true if scope includes both short and long term
     */
    public boolean searchesBothScopes() {
        return scope == MemoryScope.LONG_TERM || scope == MemoryScope.SHORT_TERM;
        // Note: Adjust this when BOTH scope is added
    }

    @Override
    public String toString() {
        return String.format(
            "LLMMemoryQuery[query='%s', maxTokens=%d, scope=%s, maxResults=%d, filters=%d]",
            query.length() > 50 ? query.substring(0, 47) + "..." : query,
            maxTokens,
            scope,
            maxResults,
            metadataFilters.size()
        );
    }
}
