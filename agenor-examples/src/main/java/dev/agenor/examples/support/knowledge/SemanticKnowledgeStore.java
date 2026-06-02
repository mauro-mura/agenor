package dev.agenor.examples.support.knowledge;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import dev.agenor.examples.support.model.SupportIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link KnowledgeStore} implementation with TF-IDF based semantic search.
 * Provides better relevance ranking than the keyword-only
 * {@code InMemoryKnowledgeStore}.
 *
 * <p>Call {@link #buildIndex()} after all documents have been added to
 * pre-compute IDF weights; the index is also built lazily on the first search.
 */
public class SemanticKnowledgeStore implements KnowledgeStore<SupportIntent> {

    private static final Logger log = LoggerFactory.getLogger(SemanticKnowledgeStore.class);

    private final Map<String, KnowledgeDocument<SupportIntent>> documents = new ConcurrentHashMap<>();
    private final TFIDFScorer tfidfScorer = new TFIDFScorer();
    private boolean indexBuilt = false;

    @Override
    public void add(KnowledgeDocument<SupportIntent> document) {
        documents.put(document.id(), document);
        tfidfScorer.indexDocument(document.id(), buildIndexableContent(document));
        indexBuilt = false;
    }

    @Override
    public Optional<KnowledgeDocument<SupportIntent>> getById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public List<KnowledgeDocument<SupportIntent>> search(String query, int maxResults) {
        ensureIndexBuilt();
        if (query == null || query.isBlank()) return List.of();

        List<TFIDFScorer.ScoredDocument> tfidfResults = tfidfScorer.search(query, maxResults * 2);

        List<ScoredResult> results = new ArrayList<>();
        Set<String> foundIds = new HashSet<>();

        for (TFIDFScorer.ScoredDocument scored : tfidfResults) {
            KnowledgeDocument<SupportIntent> doc = documents.get(scored.docId());
            if (doc != null) {
                double combined = (scored.score() * 0.7) + (doc.relevanceScore(query) * 0.3);
                results.add(new ScoredResult(doc, combined));
                foundIds.add(doc.id());
            }
        }

        // Include keyword-only matches not captured by TF-IDF
        for (KnowledgeDocument<SupportIntent> doc : documents.values()) {
            if (!foundIds.contains(doc.id())) {
                double kw = doc.relevanceScore(query);
                if (kw > 0.1) results.add(new ScoredResult(doc, kw * 0.3));
            }
        }

        return results.stream()
            .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
            .limit(maxResults)
            .map(ScoredResult::document)
            .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeDocument<SupportIntent>> searchByCategory(
            String query, SupportIntent category, int maxResults) {
        ensureIndexBuilt();

        List<KnowledgeDocument<SupportIntent>> categoryDocs = documents.values().stream()
            .filter(doc -> doc.category() == category)
            .collect(Collectors.toList());

        if (query == null || query.isBlank()) {
            return categoryDocs.stream().limit(maxResults).collect(Collectors.toList());
        }

        Map<String, Double> scores = tfidfScorer.scoreQuery(query);
        return categoryDocs.stream()
            .map(doc -> new ScoredResult(doc, scores.getOrDefault(doc.id(), 0.0)))
            .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
            .limit(maxResults)
            .map(ScoredResult::document)
            .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeDocument<SupportIntent>> getByCategory(SupportIntent category) {
        return documents.values().stream()
            .filter(doc -> doc.category() == category)
            .collect(Collectors.toList());
    }

    @Override
    public int size() {
        return documents.size();
    }

    /** Pre-computes IDF weights. Called automatically on first search if not invoked explicitly. */
    public void buildIndex() {
        tfidfScorer.buildIndex();
        indexBuilt = true;
        log.debug("TF-IDF index built for {} documents", documents.size());
    }

    private void ensureIndexBuilt() {
        if (!indexBuilt) buildIndex();
    }

    private String buildIndexableContent(KnowledgeDocument<SupportIntent> doc) {
        return doc.title() + " " + doc.content() + " " + String.join(" ", doc.keywords());
    }

    private record ScoredResult(KnowledgeDocument<SupportIntent> document, double score) {}
}
