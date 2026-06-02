package dev.agenor.examples.support.knowledge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Simple in-memory vector store using cosine similarity.
 * Stores document embeddings and performs similarity search.
 */
public class InMemoryVectorStore {

    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> metadata = new ConcurrentHashMap<>();
    private final int dimensions;

    public InMemoryVectorStore(int dimensions) {
        this.dimensions = dimensions;
    }

    /**
     * Stores a vector with its ID and optional metadata.
     */
    public void store(String id, float[] vector, Map<String, Object> meta) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                String.format("Vector dimension mismatch: expected %d, got %d", dimensions, vector.length));
        }
        vectors.put(id, normalize(vector));
        if (meta != null) {
            metadata.put(id, new HashMap<>(meta));
        }
    }

    /**
     * Stores a vector with its ID.
     */
    public void store(String id, float[] vector) {
        store(id, vector, null);
    }

    /**
     * Searches for the most similar vectors.
     *
     * @param queryVector the query vector
     * @param topK maximum number of results
     * @param minScore minimum similarity score (0.0 to 1.0)
     * @return list of search results ordered by similarity
     */
    public List<SearchResult> search(float[] queryVector, int topK, double minScore) {
        if (queryVector.length != dimensions) {
            throw new IllegalArgumentException(
                String.format("Query dimension mismatch: expected %d, got %d", dimensions, queryVector.length));
        }

        float[] normalizedQuery = normalize(queryVector);

        return vectors.entrySet().stream()
            .map(e -> new SearchResult(e.getKey(), cosineSimilarity(normalizedQuery, e.getValue()),
                metadata.get(e.getKey())))
            .filter(r -> r.score >= minScore)
            .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }

    /**
     * Searches with default minimum score of 0.0.
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        return search(queryVector, topK, 0.0);
    }

    /**
     * Gets a vector by ID.
     */
    public Optional<float[]> get(String id) {
        return Optional.ofNullable(vectors.get(id));
    }

    /**
     * Deletes a vector by ID.
     */
    public boolean delete(String id) {
        metadata.remove(id);
        return vectors.remove(id) != null;
    }

    /**
     * Returns the number of stored vectors.
     */
    public int size() {
        return vectors.size();
    }

    /**
     * Clears all vectors.
     */
    public void clear() {
        vectors.clear();
        metadata.clear();
    }

    /**
     * Returns the configured dimensions.
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * Computes cosine similarity between two normalized vectors.
     */
    private double cosineSimilarity(float[] v1, float[] v2) {
        double dotProduct = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
        }
        return dotProduct; // Vectors are already normalized
    }

    /**
     * Normalizes a vector to unit length.
     */
    private float[] normalize(float[] vector) {
        double magnitude = 0.0;
        for (float v : vector) {
            magnitude += v * v;
        }
        magnitude = Math.sqrt(magnitude);

        if (magnitude == 0) {
            return vector;
        }

        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / magnitude);
        }
        return normalized;
    }

    /**
     * Search result with ID, score, and metadata.
     */
    public record SearchResult(String id, double score, Map<String, Object> metadata) {
        public SearchResult(String id, double score) {
            this(id, score, null);
        }
    }
}
