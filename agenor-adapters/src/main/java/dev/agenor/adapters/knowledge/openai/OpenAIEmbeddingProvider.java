package dev.agenor.adapters.knowledge.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link EmbeddingProvider} backed by the OpenAI Embeddings API
 * ({@code POST https://api.openai.com/v1/embeddings}).
 *
 * <p>Default model: {@code text-embedding-3-small} (1536 dimensions).
 * Use {@code text-embedding-3-large} (3072 dimensions) for higher accuracy
 * at the cost of increased storage and latency.
 *
 * <p>{@link #embedAll} sends a single batched request to the API, which is
 * more efficient than calling {@link #embed} in a loop.
 *
 * <p>Obtain instances via {@code EmbeddingProviderFactory.openAI(apiKey)}.
 */
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_DIMENSIONS = 1536;
    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final HttpClient http;
    private final ObjectMapper mapper;

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
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public CompletableFuture<float[]> embed(String text) {
        String body = buildRequestBody(text);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 401) {
                    throw new EmbeddingException("Invalid API key", EmbeddingException.ErrorType.AUTHENTICATION);
                }
                if (response.statusCode() == 429) {
                    throw new EmbeddingException("Rate limit exceeded", EmbeddingException.ErrorType.RATE_LIMIT);
                }
                if (response.statusCode() != 200) {
                    throw new EmbeddingException(
                        "OpenAI API error: HTTP " + response.statusCode(),
                        EmbeddingException.ErrorType.SERVER_ERROR);
                }
                return parseEmbedding(response.body());
            });
    }

    @Override
    public CompletableFuture<List<float[]>> embedAll(List<String> texts) {
        String body = buildBatchRequestBody(texts);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new EmbeddingException(
                        "OpenAI API error: HTTP " + response.statusCode(),
                        EmbeddingException.ErrorType.SERVER_ERROR);
                }
                return parseBatchEmbeddings(response.body());
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

    private String buildRequestBody(String text) {
        return String.format("{\"model\":\"%s\",\"input\":\"%s\"}",
            model, escape(text));
    }

    private String buildBatchRequestBody(List<String> texts) {
        String inputs = texts.stream()
            .map(t -> "\"" + escape(t) + "\"")
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        return String.format("{\"model\":\"%s\",\"input\":[%s]}", model, inputs);
    }

    private float[] parseEmbedding(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode vector = root.path("data").get(0).path("embedding");
            return toFloatArray(vector);
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse embedding response",
                EmbeddingException.ErrorType.SERVER_ERROR, e);
        }
    }

    private List<float[]> parseBatchEmbeddings(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.path("data");
            List<float[]> result = new java.util.ArrayList<>();
            for (JsonNode item : data) {
                result.add(toFloatArray(item.path("embedding")));
            }
            return result;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse batch embedding response",
                EmbeddingException.ErrorType.SERVER_ERROR, e);
        }
    }

    private float[] toFloatArray(JsonNode node) {
        float[] arr = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            arr[i] = (float) node.get(i).asDouble();
        }
        return arr;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
