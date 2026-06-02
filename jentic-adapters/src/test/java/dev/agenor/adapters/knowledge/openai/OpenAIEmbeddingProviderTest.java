package dev.agenor.adapters.knowledge.openai;

import dev.agenor.adapters.knowledge.EmbeddingProviderContractTest;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAIEmbeddingProvider Tests")
class OpenAIEmbeddingProviderTest extends EmbeddingProviderContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_DIMENSIONS = 1536;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private OpenAIEmbeddingProvider provider;

    @Override
    protected EmbeddingProvider provider() {
        return provider;
    }

    @BeforeEach
    void setUp() throws Exception {
        provider = new OpenAIEmbeddingProvider(API_KEY);

        // Use reflection to inject the mock HttpClient
        java.lang.reflect.Field httpClientField = OpenAIEmbeddingProvider.class.getDeclaredField("http");
        httpClientField.setAccessible(true);
        httpClientField.set(provider, httpClient);

        // Default behavior for HttpClient to avoid NPE in contract tests.
        // We also provide a generic valid response for the contract tests to pass.
        lenient().when(httpResponse.statusCode()).thenReturn(200);
        lenient().when(httpResponse.body()).thenReturn(generateJsonResponse(1536));
        lenient().when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.completedFuture(httpResponse));
    }

    private String generateJsonResponse(int dims) {
        return generateJsonResponse(dims, 1);
    }

    private String generateJsonResponse(int dims, int count) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int j = 0; j < count; j++) {
            sb.append("{\"embedding\":[");
            for (int i = 0; i < dims; i++) {
                sb.append("0.0");
                if (i < dims - 1) sb.append(",");
            }
            sb.append("]}");
            if (j < count - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Nested
    @DisplayName("Configuration & Metadata")
    class MetadataTests {

        @Test
        @DisplayName("Default constructor uses correct model and dimensions")
        void defaultConstructor() {
            assertThat(provider.modelId()).isEqualTo(DEFAULT_MODEL);
            assertThat(provider.dimensions()).isEqualTo(DEFAULT_DIMENSIONS);
        }

        @Test
        @DisplayName("Custom constructor applies parameters correctly")
        void customConstructor() {
            OpenAIEmbeddingProvider customProvider = new OpenAIEmbeddingProvider(API_KEY, "custom-model", 512);
            assertThat(customProvider.modelId()).isEqualTo("custom-model");
            assertThat(customProvider.dimensions()).isEqualTo(512);
        }
    }

    @Nested
    @DisplayName("embed(String)")
    class EmbedTests {

        @Test
        @DisplayName("Successfully returns embedding for single text")
        void embedSuccess() {
            String jsonResponse = "{\"data\":[{\"embedding\":[0.1, 0.2, 0.3]}]}";
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(jsonResponse);
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

            float[] result = provider.embed("hello world").join();

            assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("Throws Authentication error on 401")
        void embedUnauthorized() {
            when(httpResponse.statusCode()).thenReturn(401);
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

            CompletableFuture<float[]> future = provider.embed("hello");

            assertThatThrownBy(future::join)
                .hasCauseInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Invalid API key");
        }

        @Test
        @DisplayName("Throws Rate Limit error on 429")
        void embedRateLimit() {
            when(httpResponse.statusCode()).thenReturn(429);
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

            CompletableFuture<float[]> future = provider.embed("hello");

            assertThatThrownBy(future::join)
                .hasCauseInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Rate limit exceeded");
        }

        @Test
        @DisplayName("Throws Server Error on other non-200 codes")
        void embedServerError() {
            when(httpResponse.statusCode()).thenReturn(500);
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

            CompletableFuture<float[]> future = provider.embed("hello");

            assertThatThrownBy(future::join)
                .hasCauseInstanceOf(EmbeddingException.class)
                .hasMessageContaining("OpenAI API error: HTTP 500");
        }
    }

    @Test
    @DisplayName("embedAll() returns one vector per input text")
    @Override
    protected void embedAllReturnsOneVectorPerText() {
        List<String> texts = List.of("first", "second", "third");
        String jsonResponse = generateJsonResponse(provider.dimensions(), texts.size());

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.completedFuture(httpResponse));

        List<float[]> vectors = provider.embedAll(texts).join();
        assertThat(vectors).hasSize(texts.size());
        vectors.forEach(v -> assertThat(v).hasSize(provider.dimensions()));
    }
}
