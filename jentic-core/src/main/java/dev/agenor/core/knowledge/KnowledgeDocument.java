package dev.agenor.core.knowledge;

import java.util.List;
import java.util.Set;

/**
 * Immutable document stored in a {@link KnowledgeStore}.
 *
 * <p>A document captures a discrete unit of knowledge (an article, FAQ entry,
 * policy paragraph, etc.) together with metadata that enables retrieval.
 * The generic parameter {@code C} allows callers to use any domain-specific
 * category type (typically an enum) without coupling the framework to a
 * particular ontology.
 *
 * <p>Use the compact constructor when {@code relatedIds} is not needed:
 * <pre>{@code
 * var doc = new KnowledgeDocument<>(
 *     "faq-001", "Return policy", "You can return items within 30 days...",
 *     SupportIntent.RETURNS, Set.of("return", "refund", "policy"));
 * }</pre>
 *
 * @param id         unique document identifier (non-null, non-blank)
 * @param title      human-readable title used in keyword scoring and display
 * @param content    full text content of the document
 * @param category   domain-specific category; used to partition documents in
 *                   {@link KnowledgeStore#searchByCategory} and
 *                   {@link KnowledgeStore#getByCategory}
 * @param keywords   set of terms that characterise the document; used by
 *                   {@link #relevanceScore} for keyword-based ranking
 * @param relatedIds IDs of semantically related documents (may be empty)
 * @param <C>        category type (e.g. an enum or {@code String})
 */
public record KnowledgeDocument<C>(
    String id,
    String title,
    String content,
    C category,
    Set<String> keywords,
    List<String> relatedIds
) {
    /**
     * Compact constructor without related documents.
     *
     * @param id       unique document identifier
     * @param title    human-readable title
     * @param content  full text content
     * @param category domain category
     * @param keywords characterising terms
     */
    public KnowledgeDocument(String id, String title, String content,
            C category, Set<String> keywords) {
        this(id, title, content, category, keywords, List.of());
    }

    /**
     * Computes a keyword-based relevance score for the given query.
     *
     * <p>Scoring rules:
     * <ul>
     *   <li>Each query token matching the {@code title} contributes 2 points.</li>
     *   <li>Each query token matching a keyword contributes 1 point.</li>
     *   <li>Each query token found in {@code content} contributes 1 point.</li>
     * </ul>
     * The raw match count is divided by the theoretical maximum to produce a
     * normalised score in {@code [0.0, 1.0]}.
     *
     * <p>This is a utility method consumed by {@code InMemoryKnowledgeStore}.
     * It is <em>not</em> part of the {@link KnowledgeStore} retrieval contract;
     * vector-backed stores use their own scoring mechanism.
     *
     * @param query the search query (may be {@code null} or blank)
     * @return normalised relevance score in {@code [0.0, 1.0]};
     *         {@code 0.0} when {@code query} is null or blank
     */
    public double relevanceScore(String query) {
        if (query == null || query.isBlank()) return 0.0;

        String[] terms = query.toLowerCase().split("\\s+");
        int matches = 0;
        int totalKeywords = keywords.size();

        for (String term : terms) {
            if (title.toLowerCase().contains(term)) matches += 2;
            for (String kw : keywords) {
                if (kw.toLowerCase().contains(term) || term.contains(kw.toLowerCase())) matches++;
            }
            if (content.toLowerCase().contains(term)) matches++;
        }

        int maxPossible = terms.length * (2 + totalKeywords + 1);
        return maxPossible > 0 ? (double) matches / maxPossible : 0.0;
    }
}
