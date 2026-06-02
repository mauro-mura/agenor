package dev.agenor.adapters.llm;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.ollama.OllamaProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LLMProviderFactoryTest {

    @Test
    void shouldCreateOpenAIBuilder() {
        OpenAIProvider.Builder builder = LLMProviderFactory.openai();

        assertNotNull(builder);
        assertInstanceOf(OpenAIProvider.Builder.class, builder);
    }

    @Test
    void shouldCreateAnthropicBuilder() {
        AnthropicProvider.Builder builder = LLMProviderFactory.anthropic();

        assertNotNull(builder);
        assertInstanceOf(AnthropicProvider.Builder.class, builder);
    }

    @Test
    void shouldCreateOllamaBuilder() {
        OllamaProvider.Builder builder = LLMProviderFactory.ollama();

        assertNotNull(builder);
        assertInstanceOf(OllamaProvider.Builder.class, builder);
    }

    @Test
    void shouldCreateIndependentBuilders() {
        OpenAIProvider.Builder openai1 = LLMProviderFactory.openai();
        OpenAIProvider.Builder openai2 = LLMProviderFactory.openai();

        assertNotNull(openai1);
        assertNotNull(openai2);
        assertNotSame(openai1, openai2);
    }
}
