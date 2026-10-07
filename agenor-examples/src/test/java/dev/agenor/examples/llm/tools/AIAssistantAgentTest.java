package dev.agenor.examples.llm.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;

/**
 * Pins the tool loop of {@link AIAssistantAgent} against a scripted provider.
 *
 * <p>A model that chains calls one at a time (observed with gpt-oss on Groq: London, then Rome)
 * asks for a second tool on the follow-up request. That request used to carry no functions, so a
 * real server answered 400 and the turn was lost; a mocked client never showed it.
 */
class AIAssistantAgentTest {

    private static LLMResponse toolCall(String id, String city) {
        return LLMResponse.builder("r-" + id, "stub")
            .functionCalls(List.of(new FunctionCall(id, "get_weather", "{\"location\":\"" + city + "\"}")))
            .build();
    }

    private static LLMResponse text(String content) {
        return LLMResponse.builder("r-text", "stub").content(content).build();
    }

    @Test
    void shouldKeepToolsOnTheFollowUpAndRunChainedCalls() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(call -> switch (call) {
            case 0 -> toolCall("call-1", "London");
            case 1 -> toolCall("call-2", "Rome");
            default -> text("London and Rome are both fine.");
        });
        AIAssistantAgent agent = new AIAssistantAgent(provider);

        String answer = agent.processUserRequest("Weather in London then Rome?", "c1")
            .get(5, TimeUnit.SECONDS);

        assertThat(answer).isEqualTo("London and Rome are both fine.");
        assertThat(provider.requests).hasSize(3);
        assertThat(provider.requests)
            .allSatisfy(request -> assertThat(request.functions()).hasSize(4));
    }

    @Test
    void shouldStopAfterTheToolBudgetInsteadOfLoopingForever() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(call -> toolCall("call-" + call, "London"));
        AIAssistantAgent agent = new AIAssistantAgent(provider);

        String answer = agent.processUserRequest("Never stop", "c2").get(5, TimeUnit.SECONDS);

        assertThat(answer).contains("tool");
        assertThat(provider.requests).hasSize(AIAssistantAgent.MAX_TOOL_ROUNDS + 1);
    }

    private static final class ScriptedProvider implements LLMProvider {
        final List<LLMRequest> requests = new ArrayList<>();
        private final Function<Integer, LLMResponse> script;

        ScriptedProvider(Function<Integer, LLMResponse> script) {
            this.script = script;
        }

        @Override
        public CompletableFuture<LLMResponse> chat(LLMRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(script.apply(requests.size() - 1));
        }

        @Override
        public CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> chunkHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<List<String>> getAvailableModels() {
            return CompletableFuture.completedFuture(List.of("stub"));
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

        @Override
        public boolean supportsFunctionCalling() {
            return true;
        }
    }
}
