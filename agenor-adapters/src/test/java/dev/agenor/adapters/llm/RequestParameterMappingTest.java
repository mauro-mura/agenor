package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.ollama.OllamaProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMRequest;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

/**
 * Pins the seven {@link LLMRequest} fields no bundled adapter used to read (0.36.0):
 * {@code topP}, {@code stop}, {@code presencePenalty}, {@code frequencyPenalty} and
 * {@code functionCall} now reach the client on all three adapters; {@code additionalParameters}
 * reaches OpenAI's own {@code customParameters}, the one provider-specific escape hatch a
 * bundled adapter exposes for it. {@code n} and a named-function {@code functionCall} are
 * deliberately left unmapped — see {@link LLMRequest#n()} and {@link LLMRequest#functionCall()}.
 *
 * <p>Modelled on {@link ModelResolutionTest}: assertions run against the {@link ChatRequest} the
 * provider hands the client.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLMRequest field mapping (0.36.0)")
class RequestParameterMappingTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private AnthropicChatModel anthropicChatModel;
    @Mock private OllamaChatModel ollamaChatModel;

    private static final ChatResponse EMPTY_RESPONSE = LLMTestFixtures.chatResponse("ok");

    private static LLMRequest.Builder requestBuilder() {
        return LLMRequest.builder().addMessage(LLMMessage.user("hello"));
    }

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
        @DisplayName("topP, stop, presencePenalty and frequencyPenalty reach the ChatRequest")
        void commonSamplingFieldsReachTheClient() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            LLMRequest request = requestBuilder()
                    .topP(0.5)
                    .stop(List.of("STOP1", "STOP2"))
                    .presencePenalty(0.3)
                    .frequencyPenalty(-0.2)
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            ChatRequest sent = captor.getValue();
            assertThat(sent.topP()).isEqualTo(0.5);
            assertThat(sent.stopSequences()).containsExactly("STOP1", "STOP2");
            assertThat(sent.presencePenalty()).isEqualTo(0.3);
            assertThat(sent.frequencyPenalty()).isEqualTo(-0.2);
        }

        @Test
        @DisplayName("an unset field is not sent at all")
        void unsetFieldsStayNull() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(requestBuilder().build()).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            ChatRequest sent = captor.getValue();
            assertThat(sent.topP()).isNull();
            assertThat(sent.stopSequences()).isEmpty();
            assertThat(sent.presencePenalty()).isNull();
            assertThat(sent.frequencyPenalty()).isNull();
            assertThat(sent.toolChoice()).isNull();
        }

        @Test
        @DisplayName("functionCall(\"none\") maps to ToolChoice.NONE")
        void functionCallNoneMapsToToolChoice() throws Exception {
            assertFunctionCallMapsTo("none", ToolChoice.NONE);
        }

        @Test
        @DisplayName("functionCall(\"auto\") maps to ToolChoice.AUTO")
        void functionCallAutoMapsToToolChoice() throws Exception {
            assertFunctionCallMapsTo("auto", ToolChoice.AUTO);
        }

        @Test
        @DisplayName("functionCall(\"required\") maps to ToolChoice.REQUIRED")
        void functionCallRequiredMapsToToolChoice() throws Exception {
            assertFunctionCallMapsTo("required", ToolChoice.REQUIRED);
        }

        private void assertFunctionCallMapsTo(String functionCall, ToolChoice expected)
                throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(requestBuilder().functionCall(functionCall).build())
                    .get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            assertThat(captor.getValue().toolChoice()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a specific function name is not translated into a ToolChoice")
        void namedFunctionChoiceIsNotMapped() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(requestBuilder().functionCall("{\"name\":\"get_weather\"}").build())
                    .get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            assertThat(captor.getValue().toolChoice()).isNull();
        }

        @Test
        @DisplayName("additionalParameters reach OpenAI's own customParameters")
        void additionalParametersReachCustomParameters() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            LLMRequest request = requestBuilder()
                    .topP(0.4)
                    .additionalParameters(Map.of("seed", 42))
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            ChatRequest sent = captor.getValue();
            // The custom-parameters path and the common-field path must not clobber each other.
            assertThat(sent.topP()).isEqualTo(0.4);
            assertThat(sent.parameters()).isInstanceOf(OpenAiChatRequestParameters.class);
            var params = (OpenAiChatRequestParameters) sent.parameters();
            assertThat(params.customParameters()).containsEntry("seed", 42);
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
        @DisplayName("topP, stop, presencePenalty and frequencyPenalty reach the ChatRequest")
        void commonSamplingFieldsReachTheClient() throws Exception {
            when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            LLMRequest request = requestBuilder()
                    .topP(0.6)
                    .stop(List.of("END"))
                    .presencePenalty(0.1)
                    .frequencyPenalty(0.2)
                    .functionCall("auto")
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(anthropicChatModel).chat(captor.capture());
            ChatRequest sent = captor.getValue();
            assertThat(sent.topP()).isEqualTo(0.6);
            assertThat(sent.stopSequences()).containsExactly("END");
            assertThat(sent.presencePenalty()).isEqualTo(0.1);
            assertThat(sent.frequencyPenalty()).isEqualTo(0.2);
            assertThat(sent.toolChoice()).isEqualTo(ToolChoice.AUTO);
        }
    }

    // ------------------------------------------------------------------
    // Ollama
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("OllamaProvider")
    class Ollama {

        private OllamaProvider provider() {
            OllamaProvider p = OllamaProvider.builder().modelName("llama3.2").build();
            inject(p, "chatModel", ollamaChatModel);
            return p;
        }

        @Test
        @DisplayName("topP, stop, presencePenalty and frequencyPenalty reach the ChatRequest")
        void commonSamplingFieldsReachTheClient() throws Exception {
            when(ollamaChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            LLMRequest request = requestBuilder()
                    .topP(0.7)
                    .stop(List.of("###"))
                    .presencePenalty(0.5)
                    .frequencyPenalty(0.5)
                    .build();

            provider().chat(request).get(5, TimeUnit.SECONDS);

            verify(ollamaChatModel).chat(captor.capture());
            ChatRequest sent = captor.getValue();
            assertThat(sent.topP()).isEqualTo(0.7);
            assertThat(sent.stopSequences()).containsExactly("###");
            assertThat(sent.presencePenalty()).isEqualTo(0.5);
            assertThat(sent.frequencyPenalty()).isEqualTo(0.5);
        }
    }
}
