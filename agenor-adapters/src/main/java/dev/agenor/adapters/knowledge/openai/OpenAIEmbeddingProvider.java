package dev.agenor.adapters.knowledge.openai;

import dev.agenor.adapters.knowledge.EmbeddingSupport;
import dev.agenor.core.knowledge.EmbeddingProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link EmbeddingProvider} backed by the OpenAI Embeddings API.
 *
 * <p>Default model: {@code text-embedding-3-small} (1536 dimensions).
 * Use {@code text-embedding-3-large} (3072 dimensions) for higher accuracy
 * at the cost of increased storage and latency.
 *
 * <p>{@link #embedAll} sends one batched request, which is what the API is for;
 * {@link #embed} is that call with a single element.
 *
 * <p>Obtain instances via {@code EmbeddingProviderFactory.openAI(apiKey)}.
 */
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_DIMENSIONS = 1536;

    private final String model;
    private final int dimensions;
    private final EmbeddingModel client;

    /**
     * Creates a provider using {@code text-embedding-3-small} (1536 dimensions).
     *
     * @param apiKey OpenAI API key (non-null, non-blank)
     */
    public OpenAIEmbeddingProvider(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_DIMENSIONS);
    }

    /**
     * Creates a provider with an explicit model and dimension count.
     *
     * @param apiKey     OpenAI API key (non-null, non-blank)
     * @param model      model identifier (e.g. {@code "text-embedding-3-large"})
     * @param dimensions vector dimensionality produced by the model
     */
    public OpenAIEmbeddingProvider(String apiKey, String model, int dimensions) {
        this.model      = model;
        this.dimensions = dimensions;
        this.client     = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .dimensions(dimensions)
                .build();
    }

    /** Test seam: takes the backing model instead of building one. */
    OpenAIEmbeddingProvider(EmbeddingModel client, String model, int dimensions) {
        this.model      = model;
        this.dimensions = dimensions;
        this.client     = client;
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        return EmbeddingSupport.embedOne("OpenAI", client, text);
    }

    @Override
    public CompletableFuture<List<float[]>> embedAll(List<String> texts) {
        return EmbeddingSupport.embedAll("OpenAI", client, texts);
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
