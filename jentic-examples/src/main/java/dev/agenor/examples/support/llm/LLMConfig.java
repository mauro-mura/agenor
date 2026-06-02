package dev.agenor.examples.support.llm;

import dev.agenor.adapters.llm.LLMProviderFactory;
import dev.agenor.core.llm.LLMProvider;

import java.time.Duration;

/**
 * Configuration for LLM provider in support chatbot.
 * Supports OpenAI, Anthropic, and Ollama (local).
 */
public class LLMConfig {

    public enum ProviderType {
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        NONE  // Fallback to template-based responses
    }

    private final ProviderType providerType;
    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final Double temperature;
    private final Integer maxTokens;

    private LLMConfig(Builder builder) {
        this.providerType = builder.providerType;
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.baseUrl = builder.baseUrl;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
    }

    /**
     * Creates an LLMProvider based on this configuration.
     * Returns null if providerType is NONE or configuration is invalid.
     */
    public LLMProvider createProvider() {
        try {
            return switch (providerType) {
                case OPENAI -> LLMProviderFactory.openai()
                    .apiKey(apiKey)
                    .modelName(modelName != null ? modelName : "gpt-4o-mini")
                    .temperature(temperature != null ? temperature : 0.7)
                    .maxTokens(maxTokens != null ? maxTokens : 1000)
                    .timeout(Duration.ofSeconds(30))
                    .build();

                case ANTHROPIC -> LLMProviderFactory.anthropic()
                    .apiKey(apiKey)
                    .modelName(modelName != null ? modelName : "claude-3-haiku-20240307")
                    .temperature(temperature != null ? temperature : 0.7)
                    .maxTokens(maxTokens != null ? maxTokens : 1000)
                    .timeout(Duration.ofSeconds(30))
                    .build();

                case OLLAMA -> LLMProviderFactory.ollama()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .modelName(modelName != null ? modelName : "llama3.2")
                    .temperature(temperature != null ? temperature : 0.7)
                    .timeout(Duration.ofMinutes(2))
                    .build();

                case NONE -> null;
            };
        } catch (Exception e) {
            // Log and return null to trigger fallback
            return null;
        }
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public boolean isEnabled() {
        return providerType != ProviderType.NONE;
    }

    // ========== FACTORY METHODS ==========

    /**
     * Create config from environment variables.
     * Checks OPENAI_API_KEY, ANTHROPIC_API_KEY, OLLAMA_BASE_URL in order.
     */
    public static LLMConfig fromEnvironment() {
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            return LLMConfig.openai(openaiKey)
                .modelName(System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"))
                .build();
        }

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isBlank()) {
            return LLMConfig.anthropic(anthropicKey)
                .modelName(System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-3-haiku-20240307"))
                .build();
        }

        String ollamaUrl = System.getenv("OLLAMA_BASE_URL");
        if (ollamaUrl != null && !ollamaUrl.isBlank()) {
            return LLMConfig.ollama(ollamaUrl)
                .modelName(System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2"))
                .build();
        }

        // Default: no LLM, use template-based responses
        return LLMConfig.none();
    }

    public static Builder openai(String apiKey) {
        return new Builder(ProviderType.OPENAI).apiKey(apiKey);
    }

    public static Builder anthropic(String apiKey) {
        return new Builder(ProviderType.ANTHROPIC).apiKey(apiKey);
    }

    public static Builder ollama(String baseUrl) {
        return new Builder(ProviderType.OLLAMA).baseUrl(baseUrl);
    }

    public static LLMConfig none() {
        return new Builder(ProviderType.NONE).build();
    }

    // ========== BUILDER ==========

    public static class Builder {
        private final ProviderType providerType;
        private String apiKey;
        private String modelName;
        private String baseUrl;
        private Double temperature;
        private Integer maxTokens;

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

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public LLMConfig build() {
            return new LLMConfig(this);
        }
    }
}
