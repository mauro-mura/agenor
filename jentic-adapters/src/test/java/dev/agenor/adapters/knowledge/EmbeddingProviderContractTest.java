package dev.agenor.adapters.knowledge;

import dev.agenor.core.knowledge.EmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

/**
 * Abstract contract test for {@link EmbeddingProvider}.
 *
 * <p>Subclass this test and implement {@link #provider()} to verify that any
 * concrete {@code EmbeddingProvider} honours the interface contract.
 * HTTP-based providers should be tested against a local stub (e.g. WireMock).
 *
 * <p>A {@link StubEmbeddingProvider} implementation is included for verifying
 * the contract in unit tests without any network dependency.
 */
public abstract class EmbeddingProviderContractTest {

    /** Returns the provider instance under test. */
    protected abstract EmbeddingProvider provider();

    @Test
    @DisplayName("dimensions() returns a positive integer")
    void dimensionsIsPositive() {
        assertThat(provider().dimensions()).isPositive();
    }

    @Test
    @DisplayName("modelId() is non-null and non-blank")
    void modelIdIsNonBlank() {
        assertThat(provider().modelId()).isNotBlank();
    }

    @Test
    @DisplayName("embed() returns a vector of length dimensions()")
    void embedReturnsCorrectLength() {
        float[] vector = provider().embed("test input").join();
        assertThat(vector).hasSize(provider().dimensions());
    }

    @Test
    @DisplayName("embedAll() returns one vector per input text")
    protected void embedAllReturnsOneVectorPerText() {
        List<String> texts = List.of("first", "second", "third");
        List<float[]> vectors = provider().embedAll(texts).join();
        assertThat(vectors).hasSize(texts.size());
        vectors.forEach(v -> assertThat(v).hasSize(provider().dimensions()));
    }

    @Test
    @DisplayName("embedAll() returns CompletableFuture (non-null)")
    void embedAllReturnsNonNull() {
        CompletableFuture<List<float[]>> future = provider().embedAll(List.of("hello"));
        assertThat(future).isNotNull();
    }

    // =========================================================================
    // Stub implementation for unit testing the contract itself
    // =========================================================================

    static class StubEmbeddingProvider implements EmbeddingProvider {

        private final int dims;
        private final String model;

        StubEmbeddingProvider(int dims, String model) {
            this.dims = dims;
            this.model = model;
        }

        @Override
        public CompletableFuture<float[]> embed(String text) {
            float[] v = new float[dims];
            for (int i = 0; i < dims; i++) v[i] = (float) (text.hashCode() % 100) / 100f;
            return CompletableFuture.completedFuture(v);
        }

        @Override
        public int dimensions() { return dims; }

        @Override
        public String modelId() { return model; }
    }

    // =========================================================================
    // Concrete subclass using the stub
    // =========================================================================

    static class StubEmbeddingProviderContractTest extends EmbeddingProviderContractTest {
        @Override
        protected EmbeddingProvider provider() {
            return new StubEmbeddingProvider(384, "stub-model");
        }
    }
}
