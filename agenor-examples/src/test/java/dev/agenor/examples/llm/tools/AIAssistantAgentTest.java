package dev.agenor.examples.llm.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.agenor.core.llm.FunctionCall;
import dev.agenor.runtime.llm.ScriptedLLMProvider;

/**
 * Pins the tool loop of {@link AIAssistantAgent} against a scripted provider.
 *
 * <p>A model that chains calls one at a time (observed with gpt-oss on Groq: London, then Rome)
 * asks for a second tool on the follow-up request. That request used to carry no functions, so a
 * real server answered 400 and the turn was lost; a mocked client never showed it.
 */
class AIAssistantAgentTest {

    private static FunctionCall weather(String id, String city) {
        return new FunctionCall(id, "get_weather", "{\"location\":\"" + city + "\"}");
    }

    @Test
    void shouldKeepToolsOnTheFollowUpAndRunChainedCalls() throws Exception {
        ScriptedLLMProvider provider = ScriptedLLMProvider.create()
            .replyCalls(weather("call-1", "London"))
            .replyCalls(weather("call-2", "Rome"))
            .reply("London and Rome are both fine.");
        AIAssistantAgent agent = new AIAssistantAgent(provider);

        String answer = agent.processUserRequest("Weather in London then Rome?", "c1")
            .get(5, TimeUnit.SECONDS);

        assertThat(answer).isEqualTo("London and Rome are both fine.");
        assertThat(provider.requests()).hasSize(3);
        assertThat(provider.requests())
            .allSatisfy(request -> assertThat(request.functions()).hasSize(4));
    }

    @Test
    void shouldStopAfterTheToolBudgetInsteadOfLoopingForever() throws Exception {
        ScriptedLLMProvider provider = ScriptedLLMProvider.create();
        for (int call = 0; call <= AIAssistantAgent.MAX_TOOL_ROUNDS; call++) {
            provider.replyCalls(weather("call-" + call, "London"));
        }
        AIAssistantAgent agent = new AIAssistantAgent(provider);

        String answer = agent.processUserRequest("Never stop", "c2").get(5, TimeUnit.SECONDS);

        assertThat(answer).contains("tool");
        assertThat(provider.requests()).hasSize(AIAssistantAgent.MAX_TOOL_ROUNDS + 1);
    }
}
