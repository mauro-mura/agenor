package dev.agenor.core.knowledge;

import java.util.List;
import java.util.Optional;

/**
 * Storage and retrieval interface for {@link KnowledgeDocument} instances.
 *
 * <p>Implementations may use keyword matching ({@code InMemoryKnowledgeStore}),
 * dense-vector similarity, or hybrid strategies. The contract is intentionally
 * minimal so all backends can be substituted transparently.
 *
 * <p>Example usage:
 * <pre>{@code
 * KnowledgeStore<SupportIntent> store = new InMemoryKnowledgeStore<>();
 * store.add(new KnowledgeDocument<>("faq-001", "Return policy", "...",
 *     SupportIntent.RETURNS, Set.of("return", "refund")));
 *
 * List<KnowledgeDocument<SupportIntent>> results = store.search("refund", 3);
 * }</pre>
 *
 * @param <C> category type used to partition documents (e.g. an enum or {@code String})
 */
public interface KnowledgeStore<C> {

    /**
     * Adds or replaces a document.
     *
     * <p>If a document with the same {@code id} already exists it is
     * silently replaced (last-write-wins semantics).
     *
     * @param document document to store (non-null)
     */
    void add(KnowledgeDocument<C> document);

    /**
     * Returns the document with the given ID, or {@link Optional#empty()} if
     * no such document exists.
     *
     * @param id document identifier (non-null)
     * @return matching document or empty
     */
    Optional<KnowledgeDocument<C>> getById(String id);

    /**
     * Returns up to {@code topK} documents ordered by descending relevance to
     * {@code query} across all categories.
     *
     * @param query search query (non-null)
     * @param topK  maximum number of results to return ({@code > 0})
     * @return ordered list of matching documents; never {@code null}
     */
    List<KnowledgeDocument<C>> search(String query, int topK);

    /**
     * Returns up to {@code topK} documents restricted to {@code category},
     * ordered by descending relevance to {@code query}.
     *
     * @param query    search query (non-null)
     * @param category category filter (non-null)
     * @param topK     maximum number of results to return ({@code > 0})
     * @return ordered list of matching documents; never {@code null}
     */
    List<KnowledgeDocument<C>> searchByCategory(String query, C category, int topK);

    /**
     * Returns all documents belonging to {@code category} in unspecified order.
     *
     * @param category category filter (non-null)
     * @return list of documents; never {@code null}, may be empty
     */
    List<KnowledgeDocument<C>> getByCategory(C category);

    /**
     * Returns the total number of documents currently stored.
     *
     * @return document count ({@code >= 0})
     */
    int size();
}
