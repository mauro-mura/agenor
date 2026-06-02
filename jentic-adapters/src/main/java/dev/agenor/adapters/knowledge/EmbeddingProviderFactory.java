package dev.agenor.adapters.knowledge;

import dev.agenor.adapters.knowledge.ollama.OllamaEmbeddingProvider;
import dev.agenor.adapters.knowledge.openai.OpenAIEmbeddingProvider;
import dev.agenor.core.knowledge.EmbeddingProvider;

/**
 * Factory for {@link EmbeddingProvider} instances.
 *
 * <p>Mirrors the pattern of {@code LLMProviderFactory}: a single entry point
 * that hides concrete implementation classes and their constructors.
 *
 * <p>Example:
 * <pre>{@code
 * // Cloud provider
 * EmbeddingProvider cloud = EmbeddingProviderFactory.openAI(System.getenv("OPENAI_API_KEY"));
 *
 * // Local model via Ollama
 * EmbeddingProvider local = EmbeddingProviderFactory.ollama();
 * }</pre>
 */
public final class EmbeddingProviderFactory {

    private EmbeddingProviderFactory() {}

    /**
     * Creates an {@link OpenAIEmbeddingProvider} using the
     * {@code text-embedding-3-small} model (1536 dimensions).
     *
     * @param apiKey OpenAI API key (non-null, non-blank)
     * @return configured provider
     */
    public static EmbeddingProvider openAI(String apiKey) {
        return new OpenAIEmbeddingProvider(apiKey);
    }

    /**
     * Creates an {@link OpenAIEmbeddingProvider} with an explicit model and
     * dimension count.
     *
     * <p>Use {@code "text-embedding-3-large"} with {@code 3072} for higher
     * accuracy at the cost of storage and latency.
     *
     * @param apiKey     OpenAI API key (non-null, non-blank)
     * @param model      model identifier (e.g. {@code "text-embedding-3-small"})
     * @param dimensions vector dimensionality produced by the model
     * @return configured provider
     */
    public static EmbeddingProvider openAI(String apiKey, String model, int dimensions) {
        return new OpenAIEmbeddingProvider(apiKey, model, dimensions);
    }

    /**
     * Creates an {@link OllamaEmbeddingProvider} using the
     * {@code nomic-embed-text} model at {@code http://localhost:11434}.
     *
     * @return configured provider
     */
    public static EmbeddingProvider ollama() {
        return new OllamaEmbeddingProvider();
    }

    /**
     * Creates an {@link OllamaEmbeddingProvider} with an explicit base URL,
     * model, and dimension count.
     *
     * @param baseUrl    Ollama base URL (e.g. {@code "http://localhost:11434"})
     * @param model      model identifier (e.g. {@code "nomic-embed-text"})
     * @param dimensions vector dimensionality produced by the model
     * @return configured provider
     */
    public static EmbeddingProvider ollama(String baseUrl, String model, int dimensions) {
        return new OllamaEmbeddingProvider(baseUrl, model, dimensions);
    }
}
