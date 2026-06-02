package dev.agenor.examples.support.knowledge;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import dev.agenor.examples.support.model.SupportIntent;
import dev.langchain4j.model.embedding.EmbeddingModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link KnowledgeStore} combining TF-IDF and vector embeddings.
 *
 * <p>Score = (tfidfWeight * tfidfScore) + (embeddingWeight * embeddingScore).
 * Falls back to TF-IDF only when no {@link EmbeddingModel} is supplied.
 *
 * <p>For new code prefer wiring {@code EmbeddingProvider} from
 * {@code agenor-adapters} via {@code EmbeddingProviderFactory}; this class
 * retains the example-local {@code EmbeddingModel} abstraction for
 * backwards compatibility within the support example.
 */
public class HybridKnowledgeStore implements KnowledgeStore<SupportIntent> {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeStore.class);

    private final Map<String, KnowledgeDocument<SupportIntent>> documents = new ConcurrentHashMap<>();
    private final TFIDFScorer tfidfScorer = new TFIDFScorer();
    private final InMemoryVectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final double tfidfWeight;
    private final double embeddingWeight;

    private boolean tfidfIndexBuilt = false;
    private boolean embeddingsIndexed = false;

    /** Creates a hybrid store with default weights (40% TF-IDF, 60% embeddings). */
    public HybridKnowledgeStore(EmbeddingModel embeddingModel, int dimensions) {
        this(embeddingModel, dimensions, 0.4, 0.6);
    }

    /** Creates a hybrid store with custom weights. */
    public HybridKnowledgeStore(EmbeddingModel embeddingModel, int dimensions,
                                 double tfidfWeight, double embeddingWeight) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = new InMemoryVectorStore(dimensions);
        this.tfidfWeight = tfidfWeight;
        this.embeddingWeight = embeddingWeight;
    }

    /** Creates a TF-IDF only store (no embedding model). */
    public HybridKnowledgeStore() {
        this.embeddingModel = null;
        this.vectorStore = null;
        this.tfidfWeight = 1.0;
        this.embeddingWeight = 0.0;
    }

    @Override
    public void add(KnowledgeDocument<SupportIntent> document) {
        documents.put(document.id(), document);
        tfidfScorer.indexDocument(document.id(), buildIndexableContent(document));
        tfidfIndexBuilt = false;
        embeddingsIndexed = false;
    }

    @Override
    public Optional<KnowledgeDocument<SupportIntent>> getById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public List<KnowledgeDocument<SupportIntent>> search(String query, int topK) {
        ensureIndexBuilt();
        if (query == null || query.isBlank()) return List.of();

        Map<String, Double> tfidfScores = tfidfScorer.scoreQuery(query);
        Map<String, Double> embeddingScores = embeddingSearch(query, topK * 2);

        Set<String> candidates = new HashSet<>();
        candidates.addAll(tfidfScores.keySet());
        candidates.addAll(embeddingScores.keySet());

        return candidates.stream()
            .map(id -> {
                KnowledgeDocument<SupportIntent> doc = documents.get(id);
                if (doc == null) return null;
                double score = (tfidfWeight * tfidfScores.getOrDefault(id, 0.0))
                             + (embeddingWeight * embeddingScores.getOrDefault(id, 0.0));
                return new ScoredResult(doc, score);
            })
            .filter(Objects::nonNull)
            .filter(r -> r.score() > 0.0)
            .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
            .limit(topK)
            .map(ScoredResult::document)
            .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeDocument<SupportIntent>> searchByCategory(
            String query, SupportIntent category, int topK) {
        return search(query, topK * 2).stream()
            .filter(doc -> doc.category() == category)
            .limit(topK)
            .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeDocument<SupportIntent>> getByCategory(SupportIntent category) {
        return documents.values().stream()
            .filter(doc -> doc.category() == category)
            .collect(Collectors.toList());
    }

    public boolean isEmbeddingsEnabled() {
        return embeddingModel != null;
    }

    @Override
    public int size() {
        return documents.size();
    }

    /**
     * Builds TF-IDF index and (if an embedding model is present) computes
     * embeddings for all documents. Call after all documents have been added.
     */
    public void buildIndex() {
        tfidfScorer.buildIndex();
        tfidfIndexBuilt = true;

        if (embeddingModel != null && vectorStore != null) {
            for (KnowledgeDocument<SupportIntent> doc : documents.values()) {
                try {
                    float[] embedding = embeddingModel.embed(buildIndexableContent(doc)).content().vector();
                    vectorStore.store(doc.id(), embedding);
                } catch (Exception e) {
                    log.warn("Failed to embed document {}: {}", doc.id(), e.getMessage());
                }
            }
            embeddingsIndexed = true;
            log.debug("Hybrid index built: {} documents", documents.size());
        }
    }

    private void ensureIndexBuilt() {
        if (!tfidfIndexBuilt || (embeddingModel != null && !embeddingsIndexed)) buildIndex();
    }

    private Map<String, Double> embeddingSearch(String query, int topK) {
        if (embeddingModel == null || vectorStore == null || !embeddingsIndexed) {
            return Map.of();
        }
        try {
            float[] queryEmbedding = embeddingModel.embed(query).content().vector();
            return vectorStore.search(queryEmbedding, topK).stream()
                .collect(Collectors.toMap(
                    InMemoryVectorStore.SearchResult::id,
                    InMemoryVectorStore.SearchResult::score));
        } catch (Exception e) {
            log.warn("Embedding search failed, falling back to TF-IDF only: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildIndexableContent(KnowledgeDocument<SupportIntent> doc) {
        return doc.title() + " " + doc.content() + " " + String.join(" ", doc.keywords());
    }

    private record ScoredResult(KnowledgeDocument<SupportIntent> document, double score) {}
}
