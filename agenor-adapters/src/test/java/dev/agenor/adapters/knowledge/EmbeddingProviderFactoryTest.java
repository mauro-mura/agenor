package dev.agenor.adapters.knowledge;

import dev.agenor.adapters.knowledge.ollama.OllamaEmbeddingProvider;
import dev.agenor.adapters.knowledge.openai.OpenAIEmbeddingProvider;
import dev.agenor.core.knowledge.EmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EmbeddingProviderFactory}.
 *
 * <p>These tests verify that the factory returns correctly configured instances
 * (type, dimensions, modelId) without making real HTTP calls.
 */
class EmbeddingProviderFactoryTest {

    // -------------------------------------------------------------------------
    // OpenAI — default
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("openAI(key) returns OpenAIEmbeddingProvider")
    void openAIDefaultReturnsCorrectType() {
        EmbeddingProvider p = EmbeddingProviderFactory.openAI("test-key");
        assertThat(p).isInstanceOf(OpenAIEmbeddingProvider.class);
    }

    @Test
    @DisplayName("openAI(key) has dimensions 1536")
    void openAIDefaultDimensions() {
        assertThat(EmbeddingProviderFactory.openAI("test-key").dimensions()).isEqualTo(1536);
    }

    @Test
    @DisplayName("openAI(key) has modelId text-embedding-3-small")
    void openAIDefaultModelId() {
        assertThat(EmbeddingProviderFactory.openAI("test-key").modelId())
            .isEqualTo("text-embedding-3-small");
    }

    // -------------------------------------------------------------------------
    // OpenAI — explicit model
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("openAI(key, model, dim) applies custom model and dimensions")
    void openAICustomModelAndDimensions() {
        EmbeddingProvider p = EmbeddingProviderFactory.openAI("test-key", "text-embedding-3-large", 3072);
        assertThat(p.modelId()).isEqualTo("text-embedding-3-large");
        assertThat(p.dimensions()).isEqualTo(3072);
    }

    // -------------------------------------------------------------------------
    // Ollama — default
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ollama() returns OllamaEmbeddingProvider")
    void ollamaDefaultReturnsCorrectType() {
        EmbeddingProvider p = EmbeddingProviderFactory.ollama();
        assertThat(p).isInstanceOf(OllamaEmbeddingProvider.class);
    }

    @Test
    @DisplayName("ollama() has dimensions 768")
    void ollamaDefaultDimensions() {
        assertThat(EmbeddingProviderFactory.ollama().dimensions()).isEqualTo(768);
    }

    @Test
    @DisplayName("ollama() has modelId nomic-embed-text")
    void ollamaDefaultModelId() {
        assertThat(EmbeddingProviderFactory.ollama().modelId()).isEqualTo("nomic-embed-text");
    }

    // -------------------------------------------------------------------------
    // Ollama — explicit config
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ollama(url, model, dim) applies custom model and dimensions")
    void ollamaCustomConfig() {
        EmbeddingProvider p = EmbeddingProviderFactory.ollama(
            "http://localhost:11434", "mxbai-embed-large", 1024);
        assertThat(p.modelId()).isEqualTo("mxbai-embed-large");
        assertThat(p.dimensions()).isEqualTo(1024);
    }
}
