package dev.agenor.examples.support.knowledge;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;

/**
 * Configuration for embedding models.
 * Supports OpenAI and Ollama embedding models.
 */
public class EmbeddingConfig {

    public enum ProviderType {
        OPENAI,
        OLLAMA,
        NONE  // In-memory only (no embeddings)
    }

    private final ProviderType providerType;
    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final int dimensions;

    private EmbeddingConfig(Builder builder) {
        this.providerType = builder.providerType;
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.baseUrl = builder.baseUrl;
        this.dimensions = builder.dimensions;
    }

    /**
     * Creates an EmbeddingModel based on this configuration.
     */
    public EmbeddingModel createModel() {
        try {
            return switch (providerType) {
                case OPENAI -> OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName != null ? modelName : "text-embedding-3-small")
                    .timeout(Duration.ofSeconds(30))
                    .build();

                case OLLAMA -> OllamaEmbeddingModel.builder()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .modelName(modelName != null ? modelName : "nomic-embed-text")
                    .timeout(Duration.ofMinutes(2))
                    .build();

                case NONE -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public int getDimensions() {
        return dimensions;
    }

    public boolean isEnabled() {
        return providerType != ProviderType.NONE;
    }

    // ========== FACTORY METHODS ==========

    /**
     * Create config from environment variables.
     */
    public static EmbeddingConfig fromEnvironment() {
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            return EmbeddingConfig.openai(openaiKey)
                .modelName(System.getenv().getOrDefault("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"))
                .dimensions(1536)
                .build();
        }

        String ollamaUrl = System.getenv("OLLAMA_BASE_URL");
        if (ollamaUrl != null && !ollamaUrl.isBlank()) {
            return EmbeddingConfig.ollama(ollamaUrl)
                .modelName(System.getenv().getOrDefault("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text"))
                .dimensions(768)
                .build();
        }

        return EmbeddingConfig.none();
    }

    public static Builder openai(String apiKey) {
        return new Builder(ProviderType.OPENAI).apiKey(apiKey).dimensions(1536);
    }

    public static Builder ollama(String baseUrl) {
        return new Builder(ProviderType.OLLAMA).baseUrl(baseUrl).dimensions(768);
    }

    public static EmbeddingConfig none() {
        return new Builder(ProviderType.NONE).build();
    }

    // ========== BUILDER ==========

    public static class Builder {
        private final ProviderType providerType;
        private String apiKey;
        private String modelName;
        private String baseUrl;
        private int dimensions = 384; // Default for small models

        private Builder(ProviderType providerType) {
            this.providerType = providerType;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public EmbeddingConfig build() {
            return new EmbeddingConfig(this);
        }
    }
}
