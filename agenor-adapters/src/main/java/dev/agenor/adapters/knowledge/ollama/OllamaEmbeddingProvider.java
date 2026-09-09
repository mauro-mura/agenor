package dev.agenor.adapters.knowledge.ollama;

import dev.agenor.adapters.knowledge.EmbeddingSupport;
import dev.agenor.core.knowledge.EmbeddingProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link EmbeddingProvider} backed by a local Ollama instance.
 *
 * <p>Ollama must be running before any call is made. Install it from
 * <a href="https://ollama.com">ollama.com</a> and pull the desired model:
 * <pre>{@code
 * ollama pull nomic-embed-text
 * }</pre>
 *
 * <p>Default base URL: {@code http://localhost:11434}.
 * Default model: {@code nomic-embed-text} (768 dimensions).
 *
 * <p>Obtain instances via {@code EmbeddingProviderFactory.ollama()}.
 */
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "nomic-embed-text";
    private static final int DEFAULT_DIMENSIONS = 768;

    private final String model;
    private final int dimensions;
    private final EmbeddingModel client;

    /**
     * Creates a provider using {@code nomic-embed-text} at {@code localhost:11434}.
     */
    public OllamaEmbeddingProvider() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_DIMENSIONS);
    }

    /**
     * Creates a provider with an explicit base URL, model, and dimension count.
     *
     * @param baseUrl    Ollama base URL (e.g. {@code "http://localhost:11434"})
     * @param model      model identifier (e.g. {@code "nomic-embed-text"})
     * @param dimensions vector dimensionality produced by the model
     */
    public OllamaEmbeddingProvider(String baseUrl, String model, int dimensions) {
        this.model      = model;
        this.dimensions = dimensions;
        // No dimensions() on this builder in LangChain4j 1.12.x - the Ollama model reports its
        // own. The declared count stays ours: it is what a vector store has to be sized with,
        // and it must be known without a round trip.
        this.client     = OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl.stripTrailing())
                .modelName(model)
                .build();
    }

    /** Test seam: takes the backing model instead of building one. */
    OllamaEmbeddingProvider(EmbeddingModel client, String model, int dimensions) {
        this.model      = model;
        this.dimensions = dimensions;
        this.client     = client;
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        return EmbeddingSupport.embedOne("Ollama", client, text);
    }

    @Override
    public CompletableFuture<List<float[]>> embedAll(List<String> texts) {
        return EmbeddingSupport.embedAll("Ollama", client, texts);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelId() {
        return model;
    }
}
