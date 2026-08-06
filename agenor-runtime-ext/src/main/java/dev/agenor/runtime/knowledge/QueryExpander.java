package dev.agenor.runtime.knowledge;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Expands search queries with synonyms to improve recall.
 *
 * <p>Synonym groups are registered at construction time (or lazily via
 * {@link #addSynonyms}). When {@link #expand} is called, every token in
 * the query that matches a known term is augmented with all its synonyms.
 * The expanded query can then be passed to {@link dev.agenor.core.knowledge.KnowledgeStore#search}.
 *
 * <p>Example:
 * <pre>{@code
 * QueryExpander expander = new QueryExpander();
 * expander.addSynonyms("refund", "return", "money back");
 * expander.addSynonyms("cancel", "cancellation", "void");
 *
 * String expanded = expander.expand("I want a refund");
 * // → "i want a refund return money back"
 *
 * List<KnowledgeDocument<...>> docs = store.search(expanded, 5);
 * }</pre>
 *
 * <p>Synonym matching is case-insensitive; the expanded query is lowercase.
 * This class is not thread-safe. If synonym groups are registered concurrently,
 * external synchronisation is required.
 */
public class QueryExpander {

    private final Map<String, Set<String>> synonymMap = new HashMap<>();

    /**
     * Registers a synonym group.
     *
     * <p>Each term in the group is mapped to all other terms in the group.
     * Terms are stored and matched in lower case. Calling this method multiple
     * times with overlapping terms is allowed; new synonyms are merged into
     * the existing group.
     *
     * @param terms two or more synonymous terms (non-null, non-empty)
     */
    public void addSynonyms(String... terms) {
        Set<String> group = new LinkedHashSet<>();
        for (String t : terms) group.add(t.toLowerCase());
        for (String term : group) {
            synonymMap.computeIfAbsent(term, k -> new LinkedHashSet<>()).addAll(group);
        }
    }

    /**
     * Expands the query by appending synonyms for every recognised token.
     *
     * <p>The original tokens are preserved at the start of the result, followed
     * by any added synonyms. Duplicate tokens are deduplicated (insertion order
     * preserved).
     *
     * @param query original query string (may be {@code null} or blank)
     * @return expanded query as a space-separated string;
     *         returns {@code query} unchanged if it is {@code null} or blank
     */
    public String expand(String query) {
        if (query == null || query.isBlank()) return query;

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : query.toLowerCase().split("\\s+")) {
            tokens.add(token);
            Set<String> synonyms = synonymMap.get(token);
            if (synonyms != null) tokens.addAll(synonyms);
        }
        return String.join(" ", tokens);
    }
}
