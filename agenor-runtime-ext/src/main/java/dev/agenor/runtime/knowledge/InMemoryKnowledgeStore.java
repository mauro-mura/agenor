package dev.agenor.runtime.knowledge;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link KnowledgeStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Retrieval is powered by {@link KnowledgeDocument#relevanceScore}, which
 * performs keyword matching on title, keywords, and content. This is suitable
 * for development, testing, and small single-JVM knowledge bases where vector
 * similarity is not required.
 *
 * <p>For production RAG use cases with dense-vector search, use an
 * embedding-backed store from {@code agenor-adapters}.
 *
 * <p>This implementation is thread-safe.
 *
 * <p>Example:
 * <pre>{@code
 * KnowledgeStore<SupportIntent> store = new InMemoryKnowledgeStore<>();
 * store.add(new KnowledgeDocument<>("faq-001", "Return policy",
 *     "Items can be returned within 30 days.",
 *     SupportIntent.RETURNS, Set.of("return", "refund")));
 *
 * store.search("refund", 3).forEach(doc -> System.out.println(doc.title()));
 * }</pre>
 *
 * @param <C> category type
 */
public class InMemoryKnowledgeStore<C> implements KnowledgeStore<C> {

    private final Map<String, KnowledgeDocument<C>> documents = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(KnowledgeDocument<C> document) {
        documents.put(document.id(), document);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<KnowledgeDocument<C>> getById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Documents with a {@link KnowledgeDocument#relevanceScore} of {@code 0.0}
     * are excluded from the results.
     */
    @Override
    public List<KnowledgeDocument<C>> search(String query, int topK) {
        return documents.values().stream()
            .map(doc -> new Scored<>(doc, doc.relevanceScore(query)))
            .filter(s -> s.score() > 0.0)
            .sorted(Comparator.comparingDouble(Scored<C>::score).reversed())
            .limit(topK)
            .map(Scored::doc)
            .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Documents with a {@link KnowledgeDocument#relevanceScore} of {@code 0.0}
     * are excluded from the results.
     */
    @Override
    public List<KnowledgeDocument<C>> searchByCategory(String query, C category, int topK) {
        return documents.values().stream()
            .filter(doc -> category.equals(doc.category()))
            .map(doc -> new Scored<>(doc, doc.relevanceScore(query)))
            .filter(s -> s.score() > 0.0)
            .sorted(Comparator.comparingDouble(Scored<C>::score).reversed())
            .limit(topK)
            .map(Scored::doc)
            .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<KnowledgeDocument<C>> getByCategory(C category) {
        return documents.values().stream()
            .filter(doc -> category.equals(doc.category()))
            .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return documents.size();
    }

    private record Scored<C>(KnowledgeDocument<C> doc, double score) {}
}
