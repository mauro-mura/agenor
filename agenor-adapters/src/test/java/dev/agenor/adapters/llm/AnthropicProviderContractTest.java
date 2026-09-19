package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.chatResponse;
import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.core.llm.LLMProvider;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnthropicProvider honours the LLMProvider contract")
class AnthropicProviderContractTest extends LLMProviderContractTest {

    @Mock private AnthropicChatModel chatModel;
    @Mock private AnthropicStreamingChatModel streamingModel;

    private AnthropicProvider provider;

    @BeforeEach
    void setUp() {
        provider = AnthropicProvider.builder().apiKey("test-key").build();
        inject(provider, "chatModel", chatModel);
        inject(provider, "streamingModel", streamingModel);
    }

    @Override
    protected LLMProvider provider() {
        return provider;
    }

    @Override
    protected String builtWithModel() {
        return AnthropicProvider.Models.CLAUDE_SONNET_4_6.id;
    }

    @Override
    protected void givenChatAnswers(String text) {
        lenient().when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse(text));
    }

    @Override
    protected void givenStreamEmits(String... partials) {
        lenient().doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            StringBuilder whole = new StringBuilder();
            for (String partial : partials) {
                whole.append(partial);
                handler.onPartialResponse(partial);
            }
            handler.onCompleteResponse(chatResponse(whole.toString()));
            return null;
        }).when(streamingModel)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }
}
