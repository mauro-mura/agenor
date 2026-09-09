package dev.agenor.examples.support.knowledge;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import dev.agenor.examples.support.model.SupportIntent;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link KnowledgeStore} combining TF-IDF and vector embeddings.
 *
 * <p>Score = (tfidfWeight * tfidfScore) + (embeddingWeight * embeddingScore).
 * Falls back to TF-IDF only when no {@link EmbeddingProvider} is supplied, which is what
 * happens when neither Ollama nor an API key is available - so this example runs either way.
 *
 * <p>The provider comes from {@code EmbeddingProviderFactory} in {@code agenor-adapters}, so
 * swapping OpenAI for a local Ollama is a factory call and nothing here changes.
 */
public class HybridKnowledgeStore implements KnowledgeStore<SupportIntent> {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeStore.class);

    private final Map<String, KnowledgeDocument<SupportIntent>> documents = new ConcurrentHashMap<>();
    private final TFIDFScorer tfidfScorer = new TFIDFScorer();
    private final InMemoryVectorStore vectorStore;
    private final EmbeddingProvider embeddings;
    private final double tfidfWeight;
    private final double embeddingWeight;

    private boolean tfidfIndexBuilt = false;
    private boolean embeddingsIndexed = false;

    /** Creates a hybrid store with default weights (40% TF-IDF, 60% embeddings). */
    public HybridKnowledgeStore(EmbeddingProvider embeddings) {
        this(embeddings, 0.4, 0.6);
    }

    /**
     * Creates a hybrid store with custom weights.
     *
     * <p>The vector store is sized from {@link EmbeddingProvider#dimensions()}. There is no
     * dimension parameter because there is nothing for a caller to get wrong: the provider
     * already knows how wide its vectors are.
     */
    public HybridKnowledgeStore(EmbeddingProvider embeddings,
                                 double tfidfWeight, double embeddingWeight) {
        this.embeddings = embeddings;
        this.vectorStore = new InMemoryVectorStore(embeddings.dimensions());
        this.tfidfWeight = tfidfWeight;
        this.embeddingWeight = embeddingWeight;
    }

    /** Creates a TF-IDF only store (no embedding provider). */
    public HybridKnowledgeStore() {
        this.embeddings = null;
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

    /**
     * Whether embeddings are actually contributing to search, which is not the same as whether
     * a provider was supplied: an unreachable backend leaves this store on TF-IDF, and saying
     * otherwise would misreport what the answers came from. Meaningful after {@link #buildIndex}.
     */
    public boolean isEmbeddingsEnabled() {
        return embeddings != null && embeddingsIndexed;
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

        if (embeddings != null && vectorStore != null) {
            // One batched call for the whole corpus rather than one request per document -
            // what embedAll is for, and the reason the provider interface has it.
            var docs = List.copyOf(documents.values());
            try {
                var vectors = embeddings.embedAll(
                        docs.stream().map(this::buildIndexableContent).toList()).join();
                for (int i = 0; i < docs.size(); i++) {
                    vectorStore.store(docs.get(i).id(), vectors.get(i));
                }
                embeddingsIndexed = true;
                log.debug("Hybrid index built: {} documents", documents.size());
            } catch (Exception e) {
                log.warn("Staying on TF-IDF: {}", explain(e));
            }
        }
    }

    private void ensureIndexBuilt() {
        if (!tfidfIndexBuilt || (embeddings != null && !embeddingsIndexed)) buildIndex();
    }

    private Map<String, Double> embeddingSearch(String query, int topK) {
        if (embeddings == null || vectorStore == null || !embeddingsIndexed) {
            return Map.of();
        }
        try {
            float[] queryEmbedding = embeddings.embed(query).join();
            return vectorStore.search(queryEmbedding, topK).stream()
                .collect(Collectors.toMap(
                    InMemoryVectorStore.SearchResult::id,
                    InMemoryVectorStore.SearchResult::score));
        } catch (Exception e) {
            log.warn("Embedding search failed, falling back to TF-IDF only: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Turns a failed embedding call into something a developer can act on.
     *
     * <p>The two cases that look identical from the outside need opposite responses: a daemon
     * that is not running, and a model that was never pulled. {@code EmbeddingException}
     * classifies them, so this can say which one happened instead of printing a stack trace.
     */
    private String explain(Throwable e) {
        var cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                ? e.getCause() : e;
        if (cause instanceof EmbeddingException ee) {
            return switch (ee.getErrorType()) {
                case NETWORK -> "no embedding backend answered - start Ollama, or run with "
                        + "EMBEDDING_BACKEND=none to stop trying";
                case MODEL_NOT_FOUND -> "the embedding model is not installed - run `ollama pull "
                        + embeddings.modelId() + "`";
                case AUTHENTICATION -> "the embedding provider rejected the credentials";
                case RATE_LIMIT -> "the embedding provider is throttling; try again later";
                default -> ee.getErrorType() + " - " + ee.getMessage();
            };
        }
        return String.valueOf(e.getMessage());
    }

    private String buildIndexableContent(KnowledgeDocument<SupportIntent> doc) {
        return doc.title() + " " + doc.content() + " " + String.join(" ", doc.keywords());
    }

    private record ScoredResult(KnowledgeDocument<SupportIntent> document, double score) {}
}
