package dev.agenor.examples.support.llm;

import dev.agenor.adapters.llm.LLMProviderFactory;
import dev.agenor.core.llm.LLMProvider;

import java.time.Duration;
import java.util.function.Function;

/**
 * Configuration for LLM provider in support chatbot.
 * Supports OpenAI, Groq (free tier), Anthropic, and Ollama (local).
 *
 * <p>The provider is selected explicitly via the {@code LLM_BACKEND} env var
 * ({@code ollama} / {@code groq} / {@code openai} / {@code anthropic}). Unset
 * (or unrecognized) defaults to {@link ProviderType#NONE} — template-based
 * responses, no LLM required — so a stray {@code OPENAI_API_KEY} left in the
 * shell never silently enables a paid backend.
 */
public class LLMConfig {

    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-3-haiku-20240307";
    private static final String DEFAULT_OLLAMA_MODEL = "llama3.2";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    public enum ProviderType {
        OPENAI,
        GROQ,
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
                    .modelName(resolveModelName())
                    .temperature(temperature != null ? temperature : 0.7)
                    .maxTokens(maxTokens != null ? maxTokens : 1000)
                    .timeout(Duration.ofSeconds(30))
                    .build();

                case GROQ -> LLMProviderFactory.openai()
                    .apiKey(apiKey)
                    .baseUrl(GROQ_BASE_URL)
                    .modelName(resolveModelName())
                    .temperature(temperature != null ? temperature : 0.7)
                    .maxTokens(maxTokens != null ? maxTokens : 1000)
                    .timeout(Duration.ofSeconds(30))
                    .build();

                case ANTHROPIC -> LLMProviderFactory.anthropic()
                    .apiKey(apiKey)
                    .modelName(resolveModelName())
                    .temperature(temperature != null ? temperature : 0.7)
                    .maxTokens(maxTokens != null ? maxTokens : 1000)
                    .timeout(Duration.ofSeconds(30))
                    .build();

                case OLLAMA -> LLMProviderFactory.ollama()
                    .baseUrl(resolveBaseUrl())
                    .modelName(resolveModelName())
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

    /** Resolved model name — the explicit override if set, else the per-provider default. */
    public String getModelName() {
        return resolveModelName();
    }

    /** Resolved base URL — only meaningful for {@link ProviderType#OLLAMA}. */
    public String getBaseUrl() {
        return resolveBaseUrl();
    }

    private String resolveModelName() {
        if (modelName != null) {
            return modelName;
        }
        return switch (providerType) {
            case OPENAI -> DEFAULT_OPENAI_MODEL;
            case GROQ -> DEFAULT_GROQ_MODEL;
            case ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL;
            case OLLAMA -> DEFAULT_OLLAMA_MODEL;
            case NONE -> null;
        };
    }

    private String resolveBaseUrl() {
        return baseUrl != null ? baseUrl : DEFAULT_OLLAMA_BASE_URL;
    }

    // ========== FACTORY METHODS ==========

    /**
     * Create config from environment variables. See the {@code LLM_BACKEND}
     * doc on the class for the selection rule.
     */
    public static LLMConfig fromEnvironment() {
        String backend = System.getenv("LLM_BACKEND");
        if (backend == null || backend.isBlank()) {
            return LLMConfig.none();
        }

        LLMConfig config = switch (backend.trim().toLowerCase()) {
            case "openai" -> requireKey("OPENAI_API_KEY", key -> LLMConfig.openai(key)
                    .modelName(System.getenv().getOrDefault("OPENAI_MODEL", DEFAULT_OPENAI_MODEL))
                    .build());

            case "groq" -> requireKey("GROQ_API_KEY", key -> LLMConfig.groq(key)
                    .modelName(System.getenv().getOrDefault("GROQ_MODEL", DEFAULT_GROQ_MODEL))
                    .build());

            case "anthropic" -> requireKey("ANTHROPIC_API_KEY", key -> LLMConfig.anthropic(key)
                    .modelName(System.getenv().getOrDefault("ANTHROPIC_MODEL", DEFAULT_ANTHROPIC_MODEL))
                    .build());

            case "ollama" -> LLMConfig
                    .ollama(System.getenv().getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL))
                    .modelName(System.getenv().getOrDefault("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL))
                    .build();

            default -> {
                System.err.println("[LLMConfig] Unknown LLM_BACKEND '" + backend + "' — valid values: "
                        + "ollama, groq, openai, anthropic. Falling back to template-based responses.");
                yield LLMConfig.none();
            }
        };

        if (config.isEnabled()) {
            String label = config.providerType == ProviderType.OLLAMA
                    ? "Ollama (" + config.getBaseUrl() + ")"
                    : config.providerType.toString();
            System.out.printf("[LLMConfig] Using %s — model: %s%n", label, config.getModelName());
        }
        return config;
    }

    /** Falls back to {@link #none()} (with a warning) if the required key is missing. */
    private static LLMConfig requireKey(String envVar, Function<String, LLMConfig> factory) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            System.err.println("[LLMConfig] LLM_BACKEND requires " + envVar
                    + " to be set — falling back to template-based responses.");
            return LLMConfig.none();
        }
        return factory.apply(value);
    }

    public static Builder openai(String apiKey) {
        return new Builder(ProviderType.OPENAI).apiKey(apiKey);
    }

    public static Builder groq(String apiKey) {
        return new Builder(ProviderType.GROQ).apiKey(apiKey);
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
