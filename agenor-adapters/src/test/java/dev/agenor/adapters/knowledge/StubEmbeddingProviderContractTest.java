package dev.agenor.adapters.knowledge;

import dev.agenor.core.knowledge.EmbeddingProvider;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CompletableFuture;

/**
 * Runs {@link EmbeddingProviderContractTest} against a provider written against the interface
 * alone, owing nothing to either real adapter.
 *
 * <p>It is a top-level class on purpose. It used to be a static class nested inside its own
 * contract test, and a nested class is not picked up by the normal build — it runs only when a
 * test filter names it explicitly, which no build step does. This suite's own self-check had
 * therefore never run in CI since it was written. The equivalent on the LLM side,
 * {@code StubLLMProviderContractTest}, was made top-level for the same reason; this closes the
 * gap its own Javadoc named as still open.
 */
@DisplayName("The EmbeddingProvider contract is satisfiable without either shipped adapter")
class StubEmbeddingProviderContractTest extends EmbeddingProviderContractTest {

    @Override
    protected EmbeddingProvider provider() {
        return new StubEmbeddingProvider(384, "stub-model");
    }

    /** The smallest thing that honours the contract, written against the interface alone. */
    static final class StubEmbeddingProvider implements EmbeddingProvider {

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
}
