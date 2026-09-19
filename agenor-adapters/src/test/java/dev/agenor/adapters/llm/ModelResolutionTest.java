package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * ADR-017 says model resolution happens in the provider's execution path, "where it is passed to
 * the underlying client", with precedence {@code request.model()} then the build-time model.
 *
 * <p>Every adapter had a build-time model on the client and never read {@code request.model()} on
 * the path that reaches it, so the request named one model and the call asked another. Nothing
 * failed: the response was simply labelled with a model that had not answered it. These tests
 * assert against the {@link ChatRequest} the provider hands the client, which is the only place
 * the difference is observable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Per-request model resolution (ADR-017)")
class ModelResolutionTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private AnthropicChatModel anthropicChatModel;
    @Mock private OllamaChatModel ollamaChatModel;

    private static final ChatResponse EMPTY_RESPONSE = LLMTestFixtures.chatResponse("ok");

    private static LLMRequest request(String model) {
        LLMRequest.Builder builder = LLMRequest.builder()
                .addMessage(LLMMessage.user("hello"));
        if (model != null) {
            builder.model(model);
        }
        return builder.build();
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
        @DisplayName("request.model() reaches the ChatRequest, not just the response label")
        void requestModelReachesTheClient() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            var response = provider().chat(request("gpt-4.1-mini")).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName()).isEqualTo("gpt-4.1-mini");
            assertThat(response.model()).isEqualTo("gpt-4.1-mini");
        }

        @Test
        @DisplayName("no model on the request falls back to the build-time model")
        void fallsBackToBuildTimeModel() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(request(null)).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName()).isEqualTo(OpenAIProvider.Models.GPT_4O.id);
        }

        @Test
        @DisplayName("a blank model is treated as absent")
        void blankModelFallsBack() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(request("   ")).get(5, TimeUnit.SECONDS);

            verify(openAiChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName()).isEqualTo(OpenAIProvider.Models.GPT_4O.id);
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
        @DisplayName("request.model() reaches the ChatRequest")
        void requestModelReachesTheClient() throws Exception {
            when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            var response = provider().chat(request("claude-opus-4-6")).get(5, TimeUnit.SECONDS);

            verify(anthropicChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName()).isEqualTo("claude-opus-4-6");
            assertThat(response.model()).isEqualTo("claude-opus-4-6");
        }

        @Test
        @DisplayName("no model on the request falls back to the build-time model")
        void fallsBackToBuildTimeModel() throws Exception {
            when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(request(null)).get(5, TimeUnit.SECONDS);

            verify(anthropicChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName())
                    .isEqualTo(AnthropicProvider.Models.CLAUDE_SONNET_4_6.id);
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
        @DisplayName("request.model() reaches the ChatRequest — it used to label the response only")
        void requestModelReachesTheClient() throws Exception {
            when(ollamaChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            var response = provider().chat(request("qwen2.5")).get(5, TimeUnit.SECONDS);

            verify(ollamaChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName()).isEqualTo("qwen2.5");
            assertThat(response.model()).isEqualTo("qwen2.5");
        }

        @Test
        @DisplayName("no model on the request falls back to the build-time model")
        void fallsBackToBuildTimeModel() throws Exception {
            when(ollamaChatModel.chat(any(ChatRequest.class))).thenReturn(EMPTY_RESPONSE);
            var captor = ArgumentCaptor.forClass(ChatRequest.class);

            provider().chat(request(null)).get(5, TimeUnit.SECONDS);

            verify(ollamaChatModel).chat(captor.capture());
            assertThat(captor.getValue().modelName()).isEqualTo("llama3.2");
        }
    }

    // ------------------------------------------------------------------
    // The library behaviour the fix depends on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("naming a model per request does not drop the client's other defaults")
    void perRequestModelDoesNotDropOtherDefaults() {
        // ChatModel.chat(ChatRequest) merges as defaultRequestParameters().overrideWith(
        // request.parameters()), so a request that sets only the model must leave the rest of the
        // client's configuration standing. This matters most on Anthropic, where max_tokens is
        // mandatory: were the merge to replace rather than override, every request carrying a
        // model would go out without it and the API would reject it. Pinned here because the
        // adapters' own tests use mocks, which bypass this default method entirely.
        AnthropicChatModel client = AnthropicChatModel.builder()
                .apiKey("test-key")
                .modelName("claude-sonnet-4-6")
                .maxTokens(4096)
                .temperature(0.7)
                .build();

        ChatRequest perRequest = ChatRequest.builder()
                .modelName("claude-opus-4-6")
                .messages(dev.langchain4j.data.message.UserMessage.from("hello"))
                .build();

        var merged = client.defaultRequestParameters().overrideWith(perRequest.parameters());

        assertThat(merged.modelName()).isEqualTo("claude-opus-4-6");
        assertThat(merged.maxOutputTokens()).isEqualTo(4096);
        assertThat(merged.temperature()).isEqualTo(0.7);
    }
}
