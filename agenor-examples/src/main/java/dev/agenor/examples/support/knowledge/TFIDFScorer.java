package dev.agenor.examples.support.knowledge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * TF-IDF (Term Frequency - Inverse Document Frequency) scorer for documents.
 * Provides better relevance ranking than simple keyword matching.
 */
public class TFIDFScorer {

    // Document frequency: term -> number of documents containing the term
    private final Map<String, Integer> documentFrequency = new ConcurrentHashMap<>();

    // Term frequency per document: docId -> (term -> count)
    private final Map<String, Map<String, Integer>> termFrequencies = new ConcurrentHashMap<>();

    // Document vectors (normalized TF-IDF): docId -> (term -> tfidf)
    private final Map<String, Map<String, Double>> documentVectors = new ConcurrentHashMap<>();

    // Total number of documents
    private int totalDocuments = 0;

    // Stopwords to filter out
    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "can", "this", "that",
        "these", "those", "i", "you", "he", "she", "it", "we", "they", "what",
        "which", "who", "whom", "how", "when", "where", "why", "if", "then",
        "so", "as", "than", "such", "no", "not", "only", "same", "just", "also"
    );

    /**
     * Indexes a document for TF-IDF scoring.
     */
    public void indexDocument(String docId, String content) {
        List<String> terms = tokenize(content);

        // Calculate term frequencies
        Map<String, Integer> tf = new HashMap<>();
        for (String term : terms) {
            tf.merge(term, 1, Integer::sum);
        }
        termFrequencies.put(docId, tf);

        // Update document frequencies
        Set<String> uniqueTerms = new HashSet<>(terms);
        for (String term : uniqueTerms) {
            documentFrequency.merge(term, 1, Integer::sum);
        }

        totalDocuments++;
    }

    /**
     * Builds TF-IDF vectors for all indexed documents.
     * Call this after all documents are indexed.
     */
    public void buildIndex() {
        documentVectors.clear();

        for (Map.Entry<String, Map<String, Integer>> entry : termFrequencies.entrySet()) {
            String docId = entry.getKey();
            Map<String, Integer> tf = entry.getValue();

            Map<String, Double> tfidf = new HashMap<>();
            double magnitude = 0.0;

            for (Map.Entry<String, Integer> termEntry : tf.entrySet()) {
                String term = termEntry.getKey();
                int termFreq = termEntry.getValue();

                // TF: log-scaled term frequency
                double tfScore = 1 + Math.log(termFreq);

                // IDF: inverse document frequency
                int df = documentFrequency.getOrDefault(term, 1);
                double idfScore = Math.log((double) totalDocuments / df);

                double score = tfScore * idfScore;
                tfidf.put(term, score);
                magnitude += score * score;
            }

            // Normalize vector
            magnitude = Math.sqrt(magnitude);
            if (magnitude > 0) {
                for (String term : tfidf.keySet()) {
                    double finalMagnitude = magnitude;
                    tfidf.compute(term, (k, v) -> v / finalMagnitude);
                }
            }

            documentVectors.put(docId, tfidf);
        }
    }

    /**
     * Scores a query against all documents using cosine similarity.
     *
     * @param query the search query
     * @return map of docId -> similarity score (0.0 to 1.0)
     */
    public Map<String, Double> scoreQuery(String query) {
        Map<String, Double> queryVector = buildQueryVector(query);
        Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Map<String, Double>> docEntry : documentVectors.entrySet()) {
            double similarity = cosineSimilarity(queryVector, docEntry.getValue());
            if (similarity > 0) {
                scores.put(docEntry.getKey(), similarity);
            }
        }

        return scores;
    }

    /**
     * Returns top-K documents ranked by TF-IDF similarity.
     */
    public List<ScoredDocument> search(String query, int topK) {
        Map<String, Double> scores = scoreQuery(query);

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> new ScoredDocument(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
    }

    /**
     * Builds a TF-IDF vector for a query.
     */
    private Map<String, Double> buildQueryVector(String query) {
        List<String> terms = tokenize(query);
        Map<String, Integer> tf = new HashMap<>();

        for (String term : terms) {
            tf.merge(term, 1, Integer::sum);
        }

        Map<String, Double> vector = new HashMap<>();
        double magnitude = 0.0;

        for (Map.Entry<String, Integer> entry : tf.entrySet()) {
            String term = entry.getKey();
            int termFreq = entry.getValue();

            // Only consider terms that exist in the corpus
            if (documentFrequency.containsKey(term)) {
                double tfScore = 1 + Math.log(termFreq);
                double idfScore = Math.log((double) totalDocuments / documentFrequency.get(term));
                double score = tfScore * idfScore;
                vector.put(term, score);
                magnitude += score * score;
            }
        }

        // Normalize
        magnitude = Math.sqrt(magnitude);
        if (magnitude > 0) {
            for (String term : vector.keySet()) {
                double finalMagnitude = magnitude;
                vector.compute(term, (k, v) -> v / finalMagnitude);
            }
        }

        return vector;
    }

    /**
     * Computes cosine similarity between two vectors.
     */
    private double cosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
        double dotProduct = 0.0;

        for (Map.Entry<String, Double> entry : v1.entrySet()) {
            Double v2Value = v2.get(entry.getKey());
            if (v2Value != null) {
                dotProduct += entry.getValue() * v2Value;
            }
        }

        return dotProduct; // Vectors are already normalized
    }

    /**
     * Tokenizes text into terms.
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        return Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .split("\\s+"))
            .filter(t -> t.length() > 2)
            .filter(t -> !STOPWORDS.contains(t))
            .collect(Collectors.toList());
    }

    /**
     * Returns number of indexed documents.
     */
    public int getDocumentCount() {
        return totalDocuments;
    }

    /**
     * Returns vocabulary size.
     */
    public int getVocabularySize() {
        return documentFrequency.size();
    }

    /**
     * Scored document result.
     */
    public record ScoredDocument(String docId, double score) {}
}
