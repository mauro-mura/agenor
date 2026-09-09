package dev.agenor.examples.llm;

import dev.agenor.adapters.knowledge.EmbeddingProviderFactory;
import dev.agenor.core.knowledge.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks an {@link EmbeddingProvider} for the examples that need one, on the same terms as
 * {@link ExampleLLMProvider} picks a chat model: local Ollama is the default <b>regardless</b>
 * of which {@code *_API_KEY} variables happen to be set, so nothing here starts spending money
 * because a key was left in the shell.
 *
 * <ul>
 *   <li>{@code EMBEDDING_BACKEND} unset or {@code ollama} (default) — local Ollama;
 *       {@code OLLAMA_BASE_URL} and {@code OLLAMA_EMBEDDING_MODEL} override host and model.</li>
 *   <li>{@code EMBEDDING_BACKEND=openai} — OpenAI (paid); requires {@code OPENAI_API_KEY}.</li>
 *   <li>{@code EMBEDDING_BACKEND=none} — no provider; a caller falls back to whatever
 *       lexical search it has.</li>
 * </ul>
 *
 * <p>A provider is returned whether or not the backend is reachable — building one opens no
 * connection. The caller decides what an unreachable backend means; the support example
 * degrades to TF-IDF and says so.
 */
public final class ExampleEmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(ExampleEmbeddingProvider.class);

    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "nomic-embed-text";
    private static final int DEFAULT_OLLAMA_DIMENSIONS = 768;

    private static final String DEFAULT_OPENAI_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_OPENAI_DIMENSIONS = 1536;

    private ExampleEmbeddingProvider() {
    }

    /**
     * Builds the provider named by {@code EMBEDDING_BACKEND}.
     *
     * @return the provider, or {@code null} when the backend is {@code none}
     */
    public static EmbeddingProvider fromEnvironment() {
        var backend = System.getenv().getOrDefault("EMBEDDING_BACKEND", "ollama")
                .trim().toLowerCase();
        return switch (backend) {
            case "ollama" -> ollama();
            case "openai" -> openAI();
            case "none"   -> null;
            default -> throw new IllegalStateException("Unknown EMBEDDING_BACKEND '" + backend
                    + "' - valid values: ollama, openai, none");
        };
    }

    private static EmbeddingProvider ollama() {
        var baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", DEFAULT_OLLAMA_BASE_URL);
        var model   = System.getenv().getOrDefault("OLLAMA_EMBEDDING_MODEL", DEFAULT_OLLAMA_MODEL);
        log.info("Embeddings: Ollama ({}), model {} - local, free, requires `ollama pull {}`",
                baseUrl, model, model);
        return EmbeddingProviderFactory.ollama(baseUrl, model, DEFAULT_OLLAMA_DIMENSIONS);
    }

    private static EmbeddingProvider openAI() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "EMBEDDING_BACKEND=openai requires OPENAI_API_KEY to be set");
        }
        var model = System.getenv().getOrDefault("OPENAI_EMBEDDING_MODEL", DEFAULT_OPENAI_MODEL);
        log.info("Embeddings: OpenAI, model {} - paid, billed per token", model);
        return EmbeddingProviderFactory.openAI(apiKey, model, DEFAULT_OPENAI_DIMENSIONS);
    }
}
