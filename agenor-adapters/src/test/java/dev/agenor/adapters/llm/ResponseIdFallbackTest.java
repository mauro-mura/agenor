package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

/**
 * Pins the 0.36.0 fix for a response with no id metadata: {@link LLMResponse#builder} requires a
 * non-null id, and OpenAI and Anthropic passed {@code ChatResponse.id()} straight through — a
 * response missing that field failed with a bare {@link NullPointerException} from inside the
 * builder, on a completed chat, not a provider failure. Ollama is unaffected: it has always
 * generated a {@link java.util.UUID} of its own, which is the inconsistency
 * {@link LLMSupport#orGeneratedId} closes by giving OpenAI and Anthropic the same fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLMResponse id fallback for a response with no id metadata")
class ResponseIdFallbackTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private AnthropicChatModel anthropicChatModel;

    /** A complete response whose metadata carries no id — {@code ChatResponseMetadata.id()} stays null. */
    private static final ChatResponse RESPONSE_WITH_NO_ID = ChatResponse.builder()
            .aiMessage(AiMessage.from("ok"))
            .metadata(ChatResponseMetadata.builder()
                    .finishReason(FinishReason.STOP)
                    .build())
            .build();

    private static LLMRequest request() {
        return LLMRequest.builder().addMessage(LLMMessage.user("hello")).build();
    }

    @Nested
    @DisplayName("OpenAIProvider")
    class OpenAI {

        @Test
        @DisplayName("does not throw, and generates an id, when the response carries none")
        void generatesAnIdWhenTheResponseHasNone() throws Exception {
            when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(RESPONSE_WITH_NO_ID);
            OpenAIProvider provider = OpenAIProvider.builder().apiKey("test-key").build();
            inject(provider, "chatModel", openAiChatModel);

            LLMResponse response = provider.chat(request()).get(5, TimeUnit.SECONDS);

            assertThat(response.id()).isNotNull().isNotBlank();
        }
    }

    @Nested
    @DisplayName("AnthropicProvider")
    class Anthropic {

        @Test
        @DisplayName("does not throw, and generates an id, when the response carries none")
        void generatesAnIdWhenTheResponseHasNone() throws Exception {
            when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(RESPONSE_WITH_NO_ID);
            AnthropicProvider provider = AnthropicProvider.builder().apiKey("test-key").build();
            inject(provider, "chatModel", anthropicChatModel);

            LLMResponse response = provider.chat(request()).get(5, TimeUnit.SECONDS);

            assertThat(response.id()).isNotNull().isNotBlank();
        }
    }
}
