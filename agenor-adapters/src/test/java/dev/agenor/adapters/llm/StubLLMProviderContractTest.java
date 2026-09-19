package dev.agenor.adapters.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;

/**
 * Runs {@link LLMProviderContractTest} against a provider that owes nothing to LangChain4j.
 *
 * <p>All three shipped adapters are built on the same library, so a clause that quietly encoded
 * that library's behaviour rather than the interface's would still look green everywhere. This is
 * the implementation that would go red instead.
 *
 * <p>It is a top-level class on purpose. The equivalent on the embedding side is a static class
 * nested inside its contract test, and a nested class is not picked up by the normal build - it
 * runs only when a test filter names it explicitly, which no build step does. A self-check that
 * does not run is not a self-check.
 */
@DisplayName("The LLMProvider contract is satisfiable without LangChain4j")
class StubLLMProviderContractTest extends LLMProviderContractTest {

    private final StubLLMProvider stub = new StubLLMProvider();

    @Override
    protected LLMProvider provider() {
        return stub;
    }

    @Override
    protected String builtWithModel() {
        return StubLLMProvider.MODEL;
    }

    @Override
    protected void givenChatAnswers(String text) {
        stub.willAnswer(text);
    }

    @Override
    protected void givenStreamEmits(String... partials) {
        stub.willEmit(partials);
    }

    /** The smallest thing that honours the contract, written against the interface alone. */
    static final class StubLLMProvider implements LLMProvider {

        static final String MODEL = "stub-model";

        private String answer = "";
        private String[] partials = new String[0];

        void willAnswer(String text) {
            this.answer = text;
        }

        void willEmit(String... parts) {
            this.partials = parts;
        }

        private String resolve(LLMRequest request) {
            String requested = request.model();
            return (requested != null && !requested.isBlank()) ? requested : MODEL;
        }

        @Override
        public CompletableFuture<LLMResponse> chat(LLMRequest request) {
            return CompletableFuture.completedFuture(
                    LLMResponse.builder("stub-1", resolve(request))
                            .role(LLMMessage.Role.ASSISTANT)
                            .content(answer)
                            .build());
        }

        @Override
        public CompletableFuture<Void> chatStream(LLMRequest request,
                                                  Consumer<StreamingChunk> chunkHandler) {
            String model = resolve(request);
            String streamId = "stub-stream-1";
            int index = 0;
            for (String partial : partials) {
                chunkHandler.accept(StreamingChunk.of(streamId, model, partial, index++));
            }
            chunkHandler.accept(StreamingChunk.of(streamId, model, "", "stop", index));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> getAvailableModels() {
            return CompletableFuture.completedFuture(List.of(MODEL));
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }

        @Override
        public String getDefaultModel() {
            return MODEL;
        }
    }
}
