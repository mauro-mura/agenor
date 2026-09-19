package dev.agenor.adapters.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;

/**
 * Abstract contract test for {@link LLMProvider}.
 *
 * <p>Subclass it, return the provider under test from {@link #provider()}, and stub the
 * underlying client through {@link #givenChatAnswers} and {@link #givenStreamEmits}. Every
 * concrete provider is then held to the same clauses.
 *
 * <p>It exists because the interface's promises were going unchecked. ADR-017 said a request may
 * name its own model and was implemented by no adapter on the path that reaches the client for
 * five months; {@code supportsStreaming()} returned {@code true} on all three while only one
 * delivered anything incrementally. Roughly 5,600 lines of per-adapter tests did not catch either,
 * because they assert against the provider's internals rather than against what the interface
 * says. A clause here is checked against every implementation at once, which is the difference.
 *
 * <p><strong>Two clauses are deliberately absent</strong>, so that their absence is a recorded
 * decision rather than an oversight:
 * <ul>
 *   <li><em>Failures arrive as a classified {@link LLMException}.</em> No bundled adapter produces
 *       one, and {@link LLMProvider#chat} does not promise it — only
 *       {@link LLMProvider#validateRequest} declares it. The clause belongs here once the promise
 *       exists; adding it now would assert a contract the interface does not state.</li>
 *   <li><em>{@code validateRequest} rejects a request with no messages.</em> Unreachable:
 *       {@code LLMRequest}'s compact constructor already refuses an empty message list with an
 *       {@code IllegalArgumentException}, so no caller can build one to hand over. Only the null
 *       request is testable.</li>
 * </ul>
 *
 * <p><strong>What a contract test cannot see, measured rather than assumed.</strong> Run against
 * the providers as they were before 0.35.0, these clauses fail six times — three on OpenAI and
 * three on Anthropic — and <em>not once</em> on Ollama, which had the same model defect. Ollama
 * already labelled its response with the requested model while sending the built-in one to the
 * server, and a clause that reads {@code LLMResponse.model()} cannot tell those apart. The
 * interface is the wrong altitude for that question. {@code ModelResolutionTest} captures the
 * request handed to the client, and {@code OllamaProviderIT} settles it against a real server by
 * naming a model that was never pulled. Keep all three: this file says what the contract is, the
 * other two say what actually left the process.
 *
 * <p><strong>On where subclasses live.</strong> The embedding side puts its contract on the
 * provider's own test class ({@code OpenAIEmbeddingProviderTest extends
 * EmbeddingProviderContractTest}). Here the contract gets its own small subclass per provider
 * instead, because the existing LLM provider tests run to several hundred lines each with nested
 * classes and their own mock lifecycles. The difference is deliberate, not an inconsistency.
 *
 * @since 0.35.0
 */
public abstract class LLMProviderContractTest {

    /** The provider under test, already wired to whatever stands in for its client. */
    protected abstract LLMProvider provider();

    /** The model this provider was constructed with, for the fallback clause. */
    protected abstract String builtWithModel();

    /** Stubs the underlying client so a {@code chat()} call answers with {@code text}. */
    protected abstract void givenChatAnswers(String text);

    /** Stubs the underlying client so a {@code chatStream()} call emits these partials, in order. */
    protected abstract void givenStreamEmits(String... partials);

    private static LLMRequest request(String model) {
        LLMRequest.Builder b = LLMRequest.builder().addMessage(LLMMessage.user("hello"));
        if (model != null) {
            b.model(model);
        }
        return b.build();
    }

    // ---------------------------------------------------------------- identity

    @Test
    @DisplayName("getProviderName() is non-blank")
    void providerNameIsNonBlank() {
        assertThat(provider().getProviderName()).isNotBlank();
    }

    @Test
    @DisplayName("getDefaultModel(), when it returns one, returns a non-blank name")
    void defaultModelIsNonBlankWhenPresent() {
        String model = provider().getDefaultModel();
        if (model != null) {
            assertThat(model).isNotBlank();
        }
    }

    @Test
    @DisplayName("getAvailableModels() completes with a non-null list")
    void availableModelsCompleteWithAList() throws Exception {
        CompletableFuture<List<String>> future = provider().getAvailableModels();
        assertThat(future).isNotNull();
        assertThat(future.get(5, TimeUnit.SECONDS)).isNotNull();
    }

    // -------------------------------------------------------------- validation

    @Test
    @DisplayName("validateRequest(null) throws LLMException")
    void validateRequestRejectsNull() {
        assertThatThrownBy(() -> provider().validateRequest(null))
                .isInstanceOf(LLMException.class);
    }

    // ------------------------------------------------------------------- chat

    @Test
    @DisplayName("chat() answers with an assistant message labelled with a model")
    void chatAnswersAsTheAssistant() throws Exception {
        givenChatAnswers("hi there");

        CompletableFuture<LLMResponse> future = provider().chat(request(null));
        assertThat(future).isNotNull();

        LLMResponse response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.model()).isNotBlank();
        assertThat(response.role()).isEqualTo(LLMMessage.Role.ASSISTANT);
    }

    @Test
    @DisplayName("chat() answers with the model the request named (ADR-017)")
    void chatHonoursTheModelNamedOnTheRequest() throws Exception {
        givenChatAnswers("hi there");

        LLMResponse response = provider().chat(request("some-other-model"))
                .get(5, TimeUnit.SECONDS);

        assertThat(response.model()).isEqualTo("some-other-model");
    }

    @Test
    @DisplayName("chat() falls back to the provider's own model when the request names none")
    void chatFallsBackToTheProvidersOwnModel() throws Exception {
        givenChatAnswers("hi there");

        LLMResponse response = provider().chat(request(null)).get(5, TimeUnit.SECONDS);

        assertThat(response.model()).isEqualTo(builtWithModel());
    }

    // -------------------------------------------------------------- streaming

    @Test
    @DisplayName("chatStream() delivers each partial as its own chunk, then a terminal one")
    void chatStreamDeliversIncrementally() throws Exception {
        givenStreamEmits("one ", "two ", "three");

        List<StreamingChunk> chunks = new ArrayList<>();
        Consumer<StreamingChunk> sink = chunks::add;
        provider().chatStream(request(null), sink).get(5, TimeUnit.SECONDS);

        assertThat(chunks).isNotEmpty();

        // Each partial is a chunk of its own: a provider that hands over the whole text once the
        // response is already complete is not streaming, however many bytes it delivers.
        assertThat(chunks).filteredOn(StreamingChunk::hasContent)
                .extracting(StreamingChunk::content)
                .containsExactly("one ", "two ", "three");

        // One stream, one id, and indices that count up from zero without gaps.
        assertThat(chunks).extracting(StreamingChunk::id).containsOnly(chunks.get(0).id());
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }

        // The last chunk closes the stream and carries no further content.
        StreamingChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isLast()).isTrue();
        assertThat(last.hasContent()).isFalse();
    }

    @Test
    @DisplayName("chatStream() labels its chunks with the model the request named")
    void chatStreamHonoursTheModelNamedOnTheRequest() throws Exception {
        givenStreamEmits("one ", "two ");

        List<StreamingChunk> chunks = new ArrayList<>();
        Consumer<StreamingChunk> sink = chunks::add;
        provider().chatStream(request("some-other-model"), sink).get(5, TimeUnit.SECONDS);

        assertThat(chunks).extracting(StreamingChunk::model).containsOnly("some-other-model");
    }
}
