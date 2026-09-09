package dev.agenor.adapters.knowledge.ollama;

import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link OllamaEmbeddingProvider} against a real Ollama, in the shape
 * {@code OllamaProviderIT} already established: Testcontainers, a model pulled over the HTTP
 * API, and {@code -Dintegration.tests.enabled=true} to opt in.
 *
 * <p>These exist because the unit tests cannot reach the claims that matter. They mock
 * LangChain4j's {@code EmbeddingModel}, so they verify the wiring around it and take on faith
 * three things only a real daemon can answer:
 *
 * <ul>
 *   <li>that {@code nomic-embed-text} really produces vectors of the width
 *       {@code dimensions()} declares - the number a vector store is sized with, and a
 *       mismatch is a runtime failure in the caller, not here;</li>
 *   <li>that the vectors are <em>semantically</em> useful at all, which is the only reason
 *       the feature exists and which no mock can demonstrate;</li>
 *   <li>that a model nobody pulled arrives as {@code MODEL_NOT_FOUND}. The unit test throws
 *       LangChain4j's exception directly and so proves only the mapping, not that this is the
 *       exception the library actually raises.</li>
 * </ul>
 *
 * <p>That last one is the reason this class was written. The same assumption - that the shape
 * of a real failure matches the shape a unit test invents - had already produced one defect in
 * this code: a dead daemon reaches LangChain4j's Ollama model as
 * {@code RuntimeException: ConnectException}, and the classifier, which matched only the
 * outermost throwable, answered {@code UNKNOWN} while every unit test passed.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("OllamaEmbeddingProvider — integration tests")
class OllamaEmbeddingProviderIT {

    private static final String MODEL = "nomic-embed-text";
    private static final int DECLARED_DIMENSIONS = 768;

    @Container
    static GenericContainer<?> ollama = new GenericContainer<>("ollama/ollama:latest")
            .withExposedPorts(11434)
            .withStartupTimeout(Duration.ofMinutes(5));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String baseUrl;

    @BeforeAll
    static void pullTheModel() {
        baseUrl = "http://localhost:" + ollama.getMappedPort(11434);
        pullModel(baseUrl, MODEL);
    }

    private static EmbeddingProvider provider() {
        return new OllamaEmbeddingProvider(baseUrl, MODEL, DECLARED_DIMENSIONS);
    }

    @Test
    @DisplayName("the model's vectors are as wide as dimensions() promises")
    void embed_returnsVectorsOfTheDeclaredWidth() {
        float[] vector = provider().embed("how do I get a refund").join();

        // Not a tautology: dimensions() is a constant in our code and the width is the model's.
        // A vector store is sized from the former and filled from the latter, so a drift here
        // surfaces as "Vector dimension mismatch" in the caller and nowhere near the cause.
        assertThat(vector).hasSize(DECLARED_DIMENSIONS);
        assertThat(vector).hasSize(provider().dimensions());
        assertThat(vector).isNotEmpty();
    }

    @Test
    @DisplayName("embedAll returns one vector per input, in order, in one call")
    void embedAll_returnsOneVectorPerInputInOrder() {
        var texts = List.of("how do I get a refund", "reset my password", "how do I get a refund");

        List<float[]> vectors = provider().embedAll(texts).join();

        assertThat(vectors).hasSize(3);
        vectors.forEach(v -> assertThat(v).hasSize(DECLARED_DIMENSIONS));

        // Order, not just count: the caller stores vectors.get(i) under docs.get(i).id(), so a
        // reordering would mislabel every document without failing anything.
        assertThat(cosine(vectors.get(0), vectors.get(2)))
                .as("identical inputs must produce identical vectors")
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    @DisplayName("vectors put a paraphrase nearer than an unrelated sentence")
    void embed_placesAParaphraseNearerThanAnUnrelatedSentence() {
        // The claim docs/knowledge.md makes, and the only reason to prefer embeddings over the
        // keyword store: "money back" and "refund" share no word.
        var query      = provider().embed("how do I get my money back").join();
        var paraphrase = provider().embed("what is your refund policy").join();
        var unrelated  = provider().embed("the server rack is in the basement").join();

        double near = cosine(query, paraphrase);
        double far  = cosine(query, unrelated);

        assertThat(near)
                .as("a paraphrase sharing no keyword should still be the closer of the two")
                .isGreaterThan(far);
    }

    @Test
    @DisplayName("a model nobody pulled is MODEL_NOT_FOUND, which is what the example tells you to fix")
    void embed_withUnpulledModel_isModelNotFound() {
        var missing = new OllamaEmbeddingProvider(baseUrl, "no-such-embedding-model", 768);

        assertThatThrownBy(missing.embed("anything")::join)
                .cause()
                .isInstanceOf(EmbeddingException.class)
                .extracting(e -> ((EmbeddingException) e).getErrorType())
                .isEqualTo(EmbeddingException.ErrorType.MODEL_NOT_FOUND);
    }

    @Test
    @DisplayName("a daemon that is not there is NETWORK, through the real exception chain")
    void embed_withNoDaemon_isNetwork() {
        // Port 1 is reserved and nothing listens on it. The point is the *chain*: LangChain4j
        // wraps the ConnectException, and matching only the outermost throwable answered
        // UNKNOWN here while the unit tests passed.
        var unreachable = new OllamaEmbeddingProvider("http://localhost:1", MODEL, 768);

        assertThatThrownBy(unreachable.embed("anything")::join)
                .cause()
                .isInstanceOf(EmbeddingException.class)
                .extracting(e -> ((EmbeddingException) e).getErrorType())
                .isEqualTo(EmbeddingException.ErrorType.NETWORK);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static void pullModel(String ollamaUrl, String modelName) {
        if (modelIsPresent(ollamaUrl, modelName)) return;

        var response = send(HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/pull"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        String.format("{\"model\":\"%s\",\"stream\":false}", modelName)))
                .timeout(Duration.ofMinutes(10))
                .build());

        if (response.statusCode() != 200 || response.body().contains("\"error\"")) {
            throw new IllegalStateException("Failed to pull " + modelName + ": HTTP "
                    + response.statusCode() + " " + response.body());
        }
        if (!modelIsPresent(ollamaUrl, modelName)) {
            throw new IllegalStateException(
                    "Ollama reported a successful pull but does not list " + modelName);
        }
    }

    private static boolean modelIsPresent(String ollamaUrl, String modelName) {
        var response = send(HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build());

        // Ollama answers a tagless pull with the ":latest" tag, so "nomic-embed-text" comes back
        // as "nomic-embed-text:latest". Matching the quoted name alone - which is what
        // OllamaProviderIT does, and gets away with because "qwen2.5:0.5b" already carries a
        // tag - reports a successful pull as missing.
        var body = response.body();
        return response.statusCode() == 200
                && (body.contains("\"" + modelName + "\"") || body.contains("\"" + modelName + ":"));
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + request.uri(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed calling " + request.uri(), e);
        }
    }
}
