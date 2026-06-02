package dev.agenor.core.knowledge;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Provider-agnostic interface for generating text embeddings.
 *
 * <p>Follows the same pattern as {@code LLMProvider}: this interface lives in
 * {@code agenor-core} with no external dependencies; concrete implementations
 * (OpenAI, Ollama) live in {@code agenor-adapters} and are instantiated via
 * {@code EmbeddingProviderFactory}.
 *
 * <p>Example usage:
 * <pre>{@code
 * EmbeddingProvider provider = EmbeddingProviderFactory.openAI(System.getenv("OPENAI_API_KEY"));
 * float[] vector = provider.embed("What is the return policy?").join();
 * }</pre>
 *
 * <p>All operations return {@link CompletableFuture} to allow non-blocking
 * I/O in virtual-thread or reactive contexts.
 */
public interface EmbeddingProvider {

    /**
     * Generates a dense embedding vector for the given text.
     *
     * @param text input text (non-null, non-blank)
     * @return a {@link CompletableFuture} that resolves to the embedding vector;
     *         the array length equals {@link #dimensions()}
     * @throws EmbeddingException (wrapped in the future) on provider errors
     */
    CompletableFuture<float[]> embed(String text);

    /**
     * Generates embedding vectors for a batch of texts.
     *
     * <p>The default implementation delegates to {@link #embed(String)} for each
     * element sequentially. Provider implementations should override this method
     * to use a single batched HTTP request where the API supports it
     * (e.g. OpenAI {@code /v1/embeddings} with an array input).
     *
     * @param texts list of input texts (non-null, non-empty)
     * @return a {@link CompletableFuture} resolving to an ordered list of
     *         embedding vectors, one per input text
     * @throws EmbeddingException (wrapped in the future) on provider errors
     */
    default CompletableFuture<List<float[]>> embedAll(List<String> texts) {
        List<CompletableFuture<float[]>> futures = texts.stream()
            .map(this::embed)
            .toList();
        return CompletableFuture
            .allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList());
    }

    /**
     * Returns the dimensionality of the vectors produced by this provider.
     *
     * <p>All vectors returned by {@link #embed} and {@link #embedAll} have
     * exactly this many elements. Callers must use this value when allocating
     * vector stores or computing similarity scores.
     *
     * @return positive integer (e.g. 1536 for {@code text-embedding-3-small})
     */
    int dimensions();

    /**
     * Returns the model identifier used by this provider instance.
     *
     * <p>The format is provider-specific (e.g. {@code "text-embedding-3-small"}
     * for OpenAI, {@code "nomic-embed-text"} for Ollama).
     *
     * @return non-null, non-blank model identifier
     */
    String modelId();
}
