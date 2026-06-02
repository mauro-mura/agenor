package dev.agenor.adapters.llm;

import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.ollama.OllamaProvider;

/**
 * Factory for creating LLM providers.
 *
 * <p>Provides convenient builder access for all supported LLM providers:
 * OpenAI, Anthropic, and Ollama.
 *
 * <p>Example usage:
 * <pre>{@code
 * // OpenAI
 * LLMProvider openai = LLMProviderFactory.openai()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .modelName("gpt-4")
 *     .build();
 *
 * // Anthropic
 * LLMProvider anthropic = LLMProviderFactory.anthropic()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .modelName("claude-3-5-sonnet-20241022")
 *     .build();
 *
 * // Ollama (local)
 * LLMProvider ollama = LLMProviderFactory.ollama()
 *     .baseUrl("http://localhost:11434")
 *     .modelName("llama3.2")
 *     .build();
 * }</pre>
 *
 * @since 0.3.0
 * @see dev.agenor.core.llm.LLMProvider
 */
public final class LLMProviderFactory {

    private LLMProviderFactory() {}

    /**
     * Create OpenAI provider builder.
     *
     * @return OpenAI provider builder
     */
    public static OpenAIProvider.Builder openai() {
        return OpenAIProvider.builder();
    }

    /**
     * Create Anthropic provider builder.
     *
     * @return Anthropic provider builder
     */
    public static AnthropicProvider.Builder anthropic() {
        return AnthropicProvider.builder();
    }

    /**
     * Create Ollama provider builder.
     *
     * @return Ollama provider builder
     */
    public static OllamaProvider.Builder ollama() {
        return OllamaProvider.builder();
    }
}
