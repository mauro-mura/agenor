package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.ollama.OllamaProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;

/**
 * Every adapter spells a finish reason the same way: {@code stop}, {@code length},
 * {@code tool_calls}, {@code content_filter}, {@code other}.
 *
 * <p>OpenAI and Anthropic passed LangChain4j's enum name through ({@code STOP}, {@code LENGTH},
 * ...), from their first commit, while Ollama, {@code ScriptedLLMProvider} and the core's own
 * helpers used the lower-case spelling. {@code wasTruncated()} was therefore always false on the
 * two commercial providers, and code comparing the string worked on Ollama and in tests but not
 * in production. The OpenAI suite asserted the lower-case spelling for years without catching it,
 * because those tests built the {@code LLMResponse} themselves: this one goes through the adapter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Finish reason spelling")
class FinishReasonSpellingTest {

    @Mock private OpenAiChatModel openAiChatModel;
    @Mock private OpenAiStreamingChatModel openAiStreamingModel;
    @Mock private AnthropicChatModel anthropicChatModel;
    @Mock private AnthropicStreamingChatModel anthropicStreamingModel;
    @Mock private OllamaChatModel ollamaChatModel;
    @Mock private OllamaStreamingChatModel ollamaStreamingModel;

    enum Provider { OPENAI, ANTHROPIC, OLLAMA }

    static Stream<Arguments> everyProviderAndReason() {
        Stream.Builder<Arguments> all = Stream.builder();
        String[][] spelling = {
                {"STOP", "stop"}, {"LENGTH", "length"}, {"TOOL_EXECUTION", "tool_calls"},
                {"CONTENT_FILTER", "content_filter"}, {"OTHER", "other"}};
        for (Provider p : Provider.values()) {
            for (String[] s : spelling) {
                all.add(Arguments.of(p, FinishReason.valueOf(s[0]), s[1]));
            }
        }
        return all.build();
    }

    private LLMProvider provider(Provider which) {
        return switch (which) {
            case OPENAI -> {
                OpenAIProvider p = OpenAIProvider.builder().apiKey("test-key").build();
                inject(p, "chatModel", openAiChatModel);
                inject(p, "streamingModel", openAiStreamingModel);
                yield p;
            }
            case ANTHROPIC -> {
                AnthropicProvider p = AnthropicProvider.builder().apiKey("test-key").build();
                inject(p, "chatModel", anthropicChatModel);
                inject(p, "streamingModel", anthropicStreamingModel);
                yield p;
            }
            case OLLAMA -> {
                OllamaProvider p = OllamaProvider.builder().modelName("llama3.2").build();
                inject(p, "chatModel", ollamaChatModel);
                inject(p, "streamingModel", ollamaStreamingModel);
                yield p;
            }
        };
    }

    private static ChatResponse response(FinishReason reason) {
        ChatResponseMetadata.Builder<?> metadata = ChatResponseMetadata.builder().id("resp-1");
        if (reason != null) {
            metadata.finishReason(reason);
        }
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).metadata(metadata.build()).build();
    }

    private static LLMRequest request() {
        return LLMRequest.builder().addMessage(LLMMessage.user("hello")).build();
    }

    private void stubChat(Provider which, ChatResponse response) {
        switch (which) {
            case OPENAI -> when(openAiChatModel.chat(any(ChatRequest.class))).thenReturn(response);
            case ANTHROPIC -> when(anthropicChatModel.chat(any(ChatRequest.class))).thenReturn(response);
            case OLLAMA -> when(ollamaChatModel.chat(any(ChatRequest.class))).thenReturn(response);
        }
    }

    private void stubStream(Provider which, ChatResponse response) {
        var answer = (org.mockito.stubbing.Answer<Object>) invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("ok");
            handler.onCompleteResponse(response);
            return null;
        };
        switch (which) {
            case OPENAI -> doAnswer(answer).when(openAiStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            case ANTHROPIC -> doAnswer(answer).when(anthropicStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            case OLLAMA -> doAnswer(answer).when(ollamaStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }
    }

    private List<StreamingChunk> stream(Provider which) throws Exception {
        List<StreamingChunk> chunks = new ArrayList<>();
        provider(which).chatStream(request(), chunks::add).get(5, TimeUnit.SECONDS);
        return chunks;
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("everyProviderAndReason")
    @DisplayName("chat() spells it in lower case")
    void chatSpellsItLowerCase(Provider which, FinishReason reason, String expected) throws Exception {
        stubChat(which, response(reason));

        LLMResponse response = provider(which).chat(request()).get(5, TimeUnit.SECONDS);

        assertThat(response.finishReason()).isEqualTo(expected);
        assertThat(response.wasTruncated()).isEqualTo(reason == FinishReason.LENGTH);
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("everyProviderAndReason")
    @DisplayName("the final chunk of a stream spells it in lower case")
    void streamSpellsItLowerCase(Provider which, FinishReason reason, String expected) throws Exception {
        stubStream(which, response(reason));

        List<StreamingChunk> chunks = stream(which);

        StreamingChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.finishReason()).isEqualTo(expected);
        assertThat(last.wasTruncated()).isEqualTo(reason == FinishReason.LENGTH);
        assertThat(last.wasFiltered()).isEqualTo(reason == FinishReason.CONTENT_FILTER);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Provider.class)
    @DisplayName("a stream whose response names no finish reason still ends with a final chunk")
    void streamWithoutReasonStillEnds(Provider which) throws Exception {
        stubStream(which, response(null));

        List<StreamingChunk> chunks = stream(which);

        StreamingChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isLast()).isTrue();
        assertThat(last.finishReason()).isEqualTo("stop");
    }
}
