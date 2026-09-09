package dev.agenor.adapters.knowledge.ollama;

import dev.agenor.adapters.knowledge.EmbeddingProviderContractTest;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.UnresolvedModelServerException;
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
@DisplayName("OllamaEmbeddingProvider Tests")
class OllamaEmbeddingProviderTest extends EmbeddingProviderContractTest {

    private static final String DEFAULT_MODEL = "nomic-embed-text";
    private static final int DEFAULT_DIMENSIONS = 768;

    @Mock
    private EmbeddingModel embeddingModel;

    private OllamaEmbeddingProvider provider;

    @Override
    protected EmbeddingProvider provider() {
        return provider;
    }

    @BeforeEach
    void setUp() {
        provider = new OllamaEmbeddingProvider(embeddingModel, DEFAULT_MODEL, DEFAULT_DIMENSIONS);

        // One vector per requested segment, each the declared width, so the contract cases
        // in the superclass have something honest to assert against.
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
            OllamaEmbeddingProvider real = new OllamaEmbeddingProvider();
            assertThat(real.modelId()).isEqualTo(DEFAULT_MODEL);
            assertThat(real.dimensions()).isEqualTo(DEFAULT_DIMENSIONS);
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
            when(embeddingModel.embedAll(anyList()))
                    .thenReturn(Response.from(List.of(Embedding.from(new float[] {0.1f, 0.2f, 0.3f}))));

            float[] result = provider.embed("hello world").join();

            assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("A daemon that is not there is a NETWORK error, not a server error")
        void embedConnectionError() {
            when(embeddingModel.embedAll(anyList()))
                    .thenThrow(new UnresolvedModelServerException("Connection refused"));

            CompletableFuture<float[]> future = provider.embed("hello");

            assertThatThrownBy(future::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.NETWORK);
        }

        @Test
        @DisplayName("A model that was never pulled is MODEL_NOT_FOUND, not a server error")
        void embedUnknownModel() {
            // The distinction the hand-rolled provider could not make: every non-200 was a
            // SERVER_ERROR, so "ollama pull nomic-embed-text" and "the daemon is down" read
            // the same to a caller deciding whether to retry.
            when(embeddingModel.embedAll(anyList()))
                    .thenThrow(new ModelNotFoundException("model 'nope' not found"));

            assertThatThrownBy(provider.embed("hello")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.MODEL_NOT_FOUND);
        }

        @Test
        @DisplayName("A cause wrapped one level deep is still classified")
        void embedWrappedConnectionError() {
            // The shape LangChain4j's Ollama model actually throws when the daemon is down.
            // Matching only the outermost throwable called this UNKNOWN, which is the least
            // useful answer available for the most common failure - and the unit tests, which
            // threw the library's exceptions directly, all passed while it did.
            when(embeddingModel.embedAll(anyList()))
                    .thenThrow(new RuntimeException(new java.net.ConnectException("refused")));

            assertThatThrownBy(provider.embed("hello")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.NETWORK);
        }

        @Test
        @DisplayName("Blank input is rejected as INVALID_INPUT, without a call")
        void embedBlankInput() {
            assertThatThrownBy(provider.embed("   ")::join)
                    .cause()
                    .isInstanceOf(EmbeddingException.class)
                    .extracting(e -> ((EmbeddingException) e).getErrorType())
                    .isEqualTo(EmbeddingException.ErrorType.INVALID_INPUT);
        }
    }
}
