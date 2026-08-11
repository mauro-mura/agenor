package dev.agenor.examples.llm;

import dev.agenor.adapters.llm.LLMProviderFactory;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.LLMProvider;

import java.time.Duration;

/**
 * Picks an {@link LLMProvider} for the Level 4 examples. Local Ollama is
 * <b>always</b> the default — regardless of which {@code *_API_KEY} variables
 * happen to be set in the environment — so examples work out of the box against
 * a local Ollama server with no signup. Scaling up to a cloud backend is an
 * explicit opt-in via {@code LLM_BACKEND}:
 *
 * <ul>
 *   <li>{@code LLM_BACKEND} unset or {@code ollama} (default) — local Ollama,
 *       {@code OLLAMA_BASE_URL}/{@code OLLAMA_MODEL} override host/model.</li>
 *   <li>{@code LLM_BACKEND=groq} — Groq's free-tier, OpenAI-compatible endpoint;
 *       requires {@code GROQ_API_KEY}.</li>
 *   <li>{@code LLM_BACKEND=openai} — OpenAI (paid); requires {@code OPENAI_API_KEY}.</li>
 *   <li>{@code LLM_BACKEND=anthropic} — Anthropic (paid); requires {@code ANTHROPIC_API_KEY}.</li>
 * </ul>
 *
 * <p>Note: Agenor's Ollama adapter does not wire up function calling, so examples
 * that use {@code FunctionDefinition}/tools should check
 * {@link LLMProvider#supportsFunctionCalling()} and point users at
 * {@code LLM_BACKEND=groq} or {@code LLM_BACKEND=openai} for the full demo.
 */
public final class ExampleLLMProvider {

    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    private static final String DEFAULT_OPENAI_MODEL = OpenAIProvider.Models.GPT_4O_MINI.id;
    private static final String DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-3-haiku-20240307";
    private static final String DEFAULT_OLLAMA_MODEL = "llama3.2";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private ExampleLLMProvider() {
    }

    /** Builds a provider using default temperature/maxTokens. */
    public static LLMProvider fromEnvironment() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double temperature = 0.7;
        private int maxTokens = 1500;

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public LLMProvider build() {
            String backend = System.getenv().getOrDefault("LLM_BACKEND", "ollama").trim().toLowerCase();
            return switch (backend) {
                case "ollama" -> buildOllama();
                case "groq" -> buildGroq();
                case "openai" -> buildOpenAI();
                case "anthropic" -> buildAnthropic();
                default -> fail("Unknown LLM_BACKEND '" + backend
                        + "' — valid values: ollama, groq, openai, anthropic");
            };
        }

        private LLMProvider buildOllama() {
            String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
            String model = System.getenv().getOrDefault("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);

            LLMProvider provider = LLMProviderFactory.ollama()
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .temperature(temperature)
                    .timeout(Duration.ofMinutes(2))
                    .build();

            return announce("Ollama (" + baseUrl + ")", model, provider,
                    "Local, free, no signup (requires `ollama pull " + model + "`). Scale up with "
                    + "LLM_BACKEND=groq (free tier) or LLM_BACKEND=openai/anthropic (paid).");
        }

        private LLMProvider buildGroq() {
            String apiKey = requireEnv("GROQ_API_KEY", "groq");
            String model = System.getenv().getOrDefault("GROQ_MODEL", DEFAULT_GROQ_MODEL);

            LLMProvider provider = LLMProviderFactory.openai()
                    .apiKey(apiKey)
                    .baseUrl(GROQ_BASE_URL)
                    .modelName(model)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            return announce("Groq (free tier)", model, provider, null);
        }

        private LLMProvider buildOpenAI() {
            String apiKey = requireEnv("OPENAI_API_KEY", "openai");
            String model = System.getenv().getOrDefault("OPENAI_MODEL", DEFAULT_OPENAI_MODEL);

            LLMProvider provider = LLMProviderFactory.openai()
                    .apiKey(apiKey)
                    .modelName(model)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            return announce("OpenAI", model, provider, null);
        }

        private LLMProvider buildAnthropic() {
            String apiKey = requireEnv("ANTHROPIC_API_KEY", "anthropic");
            String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", DEFAULT_ANTHROPIC_MODEL);

            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey(apiKey)
                    .modelName(model)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            return announce("Anthropic", model, provider, null);
        }

        private String requireEnv(String varName, String backendName) {
            String value = System.getenv(varName);
            if (value == null || value.isBlank()) {
                return fail("LLM_BACKEND=" + backendName + " requires " + varName + " to be set.");
            }
            return value;
        }

        private LLMProvider announce(String label, String model, LLMProvider provider, String hint) {
            System.out.printf("[ExampleLLMProvider] Using %s — model: %s%n", label, model);
            if (hint != null) {
                System.out.println("[ExampleLLMProvider] " + hint);
            }
            return provider;
        }

        private <T> T fail(String message) {
            System.err.println("[ExampleLLMProvider] ERROR: " + message);
            System.exit(1);
            throw new AssertionError("unreachable");
        }
    }
}
