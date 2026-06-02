package dev.agenor.adapters.knowledge.ollama;

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

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OllamaEmbeddingProvider Tests")
class OllamaEmbeddingProviderTest extends EmbeddingProviderContractTest {

    private static final String DEFAULT_MODEL = "nomic-embed-text";
    private static final int DEFAULT_DIMENSIONS = 768;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private OllamaEmbeddingProvider provider;

    @Override
    protected EmbeddingProvider provider() {
        return provider;
    }

    @BeforeEach
    void setUp() throws Exception {
        provider = new OllamaEmbeddingProvider();

        // Use reflection to inject the mock HttpClient
        java.lang.reflect.Field httpClientField = OllamaEmbeddingProvider.class.getDeclaredField("http");
        httpClientField.setAccessible(true);
        httpClientField.set(provider, httpClient);

        // Default behavior for HttpClient to avoid NPE in contract tests.
        lenient().when(httpResponse.statusCode()).thenReturn(200);
        lenient().when(httpResponse.body()).thenReturn(generateJsonResponse(DEFAULT_DIMENSIONS));
        lenient().when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.completedFuture(httpResponse));
    }

    private String generateJsonResponse(int dims) {
        StringBuilder sb = new StringBuilder("{\"embedding\":[");
        for (int i = 0; i < dims; i++) {
            sb.append("0.0");
            if (i < dims - 1) sb.append(",");
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
            OllamaEmbeddingProvider customProvider = new OllamaEmbeddingProvider(
                "http://ollama:11434", "custom-model", 512);
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
            String jsonResponse = "{\"embedding\":[0.1, 0.2, 0.3]}";
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(jsonResponse);
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

            float[] result = provider.embed("hello world").join();

            assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("Throws Server Error on non-200 codes")
        void embedServerError() {
            when(httpResponse.statusCode()).thenReturn(500);
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));

            CompletableFuture<float[]> future = provider.embed("hello");

            assertThatThrownBy(future::join)
                .hasCauseInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Ollama API error: HTTP 500");
        }

        @Test
        @DisplayName("Throws Network error on connection failure")
        void embedConnectionError() {
            CompletableFuture<HttpResponse<String>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new ConnectException("Connection refused"));

            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(failedFuture);

            CompletableFuture<float[]> future = provider.embed("hello");

            assertThatThrownBy(future::join)
                .hasCauseInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Cannot connect to Ollama");
        }
    }
}
