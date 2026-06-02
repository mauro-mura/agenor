package dev.agenor.adapters.knowledge.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * {@link EmbeddingProvider} backed by the Ollama local embeddings API
 * ({@code POST /api/embeddings}).
 *
 * <p>Ollama must be running locally before any call is made. Install it from
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
    private final String endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;

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
        this.model = model;
        this.dimensions = dimensions;
        this.endpoint = baseUrl.stripTrailing() + "/api/embeddings";
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        String body = String.format("{\"model\":\"%s\",\"prompt\":\"%s\"}",
            model, escape(text));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new EmbeddingException(
                        "Ollama API error: HTTP " + response.statusCode(),
                        EmbeddingException.ErrorType.SERVER_ERROR);
                }
                return parseEmbedding(response.body());
            })
            .exceptionally(ex -> {
                if (ex instanceof EmbeddingException ee) throw ee;
                if (ex.getCause() instanceof EmbeddingException ee) throw ee;

                if (ex.getCause() instanceof java.net.ConnectException) {
                    throw new EmbeddingException(
                        "Cannot connect to Ollama at " + endpoint,
                        EmbeddingException.ErrorType.NETWORK, ex);
                }
                throw new EmbeddingException("Unexpected error",
                    EmbeddingException.ErrorType.UNKNOWN, ex);
            });
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelId() {
        return model;
    }

    private float[] parseEmbedding(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode vector = root.path("embedding");
            float[] arr = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                arr[i] = (float) vector.get(i).asDouble();
            }
            return arr;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse Ollama embedding response",
                EmbeddingException.ErrorType.SERVER_ERROR, e);
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
