package dev.agenor.adapters.knowledge.openai;

import dev.agenor.adapters.knowledge.EmbeddingProviderContractTest;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAIEmbeddingProvider Tests")
class OpenAIEmbeddingProviderTest extends EmbeddingProviderContractTest {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_DIMENSIONS = 1536;

    @Mock
    private EmbeddingModel embeddingModel;

    private OpenAIEmbeddingProvider provider;

    @Override
    protected EmbeddingProvider provider() {
        return provider;
    }

    @BeforeEach
    void setUp() {
        provider = new OpenAIEmbeddingProvider(embeddingModel, DEFAULT_MODEL, DEFAULT_DIMENSIONS);

        lenient().when(embeddingModel.embedAll(anyList())).thenAnswer(inv -> {
            List<?> segments = inv.getArgument(0);
            return Response.from(segments.stream()
                    .map(s -> Embedding.from(new float[DEFAULT_DIMENSIONS]))
                    .toList());
        });
    }

    @Nested
    @DisplayName("Configuration & Metadata")
    class MetadataTests {

        @Test
        @DisplayName("Default constructor uses correct model and dimensions")
        void defaultConstructor() {
            OpenAIEmbeddingProvider real = new OpenAIEmbeddingProvider("test-key");
            assertThat(real.modelId()).isEqualTo(DEFAULT_MODEL);
            assertThat(real.dimensions()).isEqualTo(DEFAULT_DIMENSIONS);
        }

        @Test
        @DisplayName("Custom constructor applies parameters correctly")
        void customConstructor() {
            OpenAIEmbeddingProvider customProvider =
                    new OpenAIEmbeddingProvider("test-key", "text-embedding-3-large", 3072);
            assertThat(customProvider.modelId()).isEqualTo("text-embedding-3-large");
            assertThat(customProvider.dimensions()).isEqualTo(3072);
        }
    }

    @Nested
    @DisplayName("embed(String)")
    class EmbedTests {

        @Test
        @DisplayName("Successfully returns embedding for single text")
        void embedSuccess() {
            when(embeddingModel.embedAll(anyList()))
                    .thenReturn(Response.from(List.of(Embedding.from(new float[] {0.1f, 0.2f, 0.3f}))));

            float[] result = provider.embed("hello world").join();

            assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("A rejected key is AUTHENTICATION")
        void embedUnauthorized() {
            when(embeddingModel.embedAll(anyList()))
                    .thenThrow(new AuthenticationException("invalid api key"));

            assertThatThrownBy(provider.embed("hello")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.AUTHENTICATION);
        }

        @Test
        @DisplayName("A throttled call is RATE_LIMIT, so a caller can back off")
        void embedRateLimit() {
            when(embeddingModel.embedAll(anyList()))
                    .thenThrow(new RateLimitException("slow down"));

            assertThatThrownBy(provider.embed("hello")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("A 5xx is SERVER_ERROR")
        void embedServerError() {
            when(embeddingModel.embedAll(anyList()))
                    .thenThrow(new InternalServerException("upstream exploded"));

            assertThatThrownBy(provider.embed("hello")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.SERVER_ERROR);
        }

        @Test
        @DisplayName("Blank input is rejected as INVALID_INPUT, without a call")
        void embedBlankInput() {
            assertThatThrownBy(provider.embed("")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("embedAll() sends one batched request, not one per text")
    void embedAllIsOneRequest() {
        List<String> texts = List.of("first", "second", "third");

        CompletableFuture<List<float[]>> future = provider.embedAll(texts);

        assertThat(future.join()).hasSize(3);
        org.mockito.Mockito.verify(embeddingModel, org.mockito.Mockito.times(1))
                .embedAll(anyList());
    }
}
