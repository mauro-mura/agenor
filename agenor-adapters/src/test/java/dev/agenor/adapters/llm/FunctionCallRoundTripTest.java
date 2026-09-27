package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Pins the function-calling round trip introduced in 0.36.0: an assistant turn's
 * {@link FunctionCall}s survive onto the client's {@code AiMessage}, and a
 * {@code FUNCTION}-role result is paired to its call by id, not sent as an ordinary message with
 * nothing identifying which call it answers.
 *
 * <p>Modelled on {@link ModelResolutionTest}: assertions run against the {@link ChatRequest} the
 * provider hands the client, mocked the same way, because that is the only place the mapping is
 * observable.
 *
 * <p><b>What this does not verify.</b> Anthropic requires every {@code tool_use} to be followed
 * by a matching {@code tool_result} before the next non-tool turn, server-side; a mock never
 * enforces that pairing, so a request that is malformed in a way these tests do not check could
 * still pass here and be rejected by the real API. Running {@code AIAssistantExample} against a
 * real OpenAI or Anthropic key is what would close that gap, and neither is available where this
 * suite runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Function-calling round trip")
class FunctionCallRoundTripTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private AnthropicChatModel anthropicChatModel;

    private static final ChatResponse EMPTY_RESPONSE = LLMTestFixtures.chatResponse("ok");

    // ------------------------------------------------------------------
    // OpenAI
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("OpenAIProvider")
    class OpenAI {

        private OpenAIProvider provider() {
            OpenAIProvider p = OpenAIProvider.builder().apiKey("test-key").build();
            inject(p, "chatModel", openAiChatModel);
            return p;
        }

        @Test
        @DisplayName("an assistant turn's function calls become tool execution requests")
        void assistantFunctionCallsBecomeToolExecutionRequests() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);
            FunctionCall call = FunctionCall.of("get_weather", "{\"city\":\"Paris\"}");

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What's the weather in Paris?"))
                    .addMessage(LLMMessage.assistant(null, List.of(call)))
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            AiMessage aiMessage = (AiMessage) captor.getValue().messages().get(1);
            assertThat(aiMessage.hasToolExecutionRequests()).isTrue();
            ToolExecutionRequest req = aiMessage.toolExecutionRequests().get(0);
            assertThat(req.id()).isEqualTo(call.id());
            assertThat(req.name()).isEqualTo("get_weather");
            assertThat(req.arguments()).isEqualTo("{\"city\":\"Paris\"}");
        }

        @Test
        @DisplayName("a function result is paired to its call by id")
        void functionResultIsPairedToItsCall() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);
            FunctionCall call = FunctionCall.of("get_weather", "{}");

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What's the weather?"))
                    .addMessage(LLMMessage.assistant(null, List.of(call)))
                    .addMessage(LLMMessage.function(call, "{\"temperature\":22}"))
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            var result = (ToolExecutionResultMessage) captor.getValue().messages().get(2);
            assertThat(result.id()).isEqualTo(call.id());
            assertThat(result.toolName()).isEqualTo("get_weather");
            assertThat(result.text()).isEqualTo("{\"temperature\":22}");
        }

        @Test
        @DisplayName("a function result with no call id is rejected before it reaches the client")
        @SuppressWarnings("deprecation") // exercising the deprecated overload's own failure mode
        void functionResultWithoutIdIsRejected() {
            LLMMessage orphan = LLMMessage.function("get_weather", "{\"temperature\":22}");
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What's the weather?"))
                    .addMessage(orphan)
                    .build();

            CompletableFuture<LLMResponse> future = provider().chat(request);

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertThat(ex.getCause()).isInstanceOf(LLMException.class);
            assertThat(((LLMException) ex.getCause()).getErrorType())
                    .isEqualTo(LLMException.ErrorType.INVALID_REQUEST);
            verifyNoInteractions(openAiChatModel);
        }
    }

    // ------------------------------------------------------------------
    // Anthropic
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AnthropicProvider")
    class Anthropic {

        private AnthropicProvider provider() {
            AnthropicProvider p = AnthropicProvider.builder().apiKey("test-key").build();
            inject(p, "chatModel", anthropicChatModel);
            return p;
        }

        @Test
        @DisplayName("an assistant turn's function calls become tool execution requests")
        void assistantFunctionCallsBecomeToolExecutionRequests() throws Exception {
            when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);
            FunctionCall call = FunctionCall.of("get_weather", "{\"city\":\"Paris\"}");

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What's the weather in Paris?"))
                    .addMessage(LLMMessage.assistant(null, List.of(call)))
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(anthropicChatModel).chat(captor.capture());
            AiMessage aiMessage = (AiMessage) captor.getValue().messages().get(1);
            assertThat(aiMessage.hasToolExecutionRequests()).isTrue();
            ToolExecutionRequest req = aiMessage.toolExecutionRequests().get(0);
            assertThat(req.id()).isEqualTo(call.id());
            assertThat(req.name()).isEqualTo("get_weather");
        }

        @Test
        @DisplayName("a function result is paired to its call by id")
        void functionResultIsPairedToItsCall() throws Exception {
            when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);
            FunctionCall call = FunctionCall.of("get_weather", "{}");

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What's the weather?"))
                    .addMessage(LLMMessage.assistant(null, List.of(call)))
                    .addMessage(LLMMessage.function(call, "{\"temperature\":22}"))
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(anthropicChatModel).chat(captor.capture());
            var result = (ToolExecutionResultMessage) captor.getValue().messages().get(2);
            assertThat(result.id()).isEqualTo(call.id());
            assertThat(result.toolName()).isEqualTo("get_weather");
            assertThat(result.text()).isEqualTo("{\"temperature\":22}");
        }

        @Test
        @DisplayName("a function result with no call id is rejected before it reaches the client")
        @SuppressWarnings("deprecation")
        void functionResultWithoutIdIsRejected() {
            LLMMessage orphan = LLMMessage.function("get_weather", "{\"temperature\":22}");
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What's the weather?"))
                    .addMessage(orphan)
                    .build();

            CompletableFuture<LLMResponse> future = provider().chat(request);

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertThat(ex.getCause()).isInstanceOf(LLMException.class);
            assertThat(((LLMException) ex.getCause()).getErrorType())
                    .isEqualTo(LLMException.ErrorType.INVALID_REQUEST);
            verifyNoInteractions(anthropicChatModel);
        }
    }

    // ------------------------------------------------------------------
    // Ollama: no tool spec is ever attached, so a FUNCTION-role message is a
    // clear misuse rather than a mapping to get right — pinned in
    // OllamaProviderTest, not duplicated here.
    // ------------------------------------------------------------------
}
