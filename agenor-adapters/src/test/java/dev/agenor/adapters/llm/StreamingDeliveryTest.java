package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.ollama.OllamaProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.StreamingChunk;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;

/**
 * {@code supportsStreaming()} returned {@code true} for all three providers, but only Ollama
 * implemented {@code onPartialResponse}. On the other two the whole text arrived in a single
 * chunk once the response was already complete, so a caller building an incremental UI on
 * {@code chatStream} saw exactly the latency of {@code chat} and no error saying so.
 *
 * <p>The awkward case is a response that produces no incremental events at all — a reply that is
 * only tool calls, or a provider that does not stream. That text must still reach the handler,
 * and a response that did stream must not arrive a second time in full at the end. Both are
 * pinned below for every provider.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Incremental streaming delivery")
class StreamingDeliveryTest {

    @Mock private OpenAiStreamingChatModel openAiStreamingModel;
    @Mock private AnthropicStreamingChatModel anthropicStreamingModel;
    @Mock private OllamaStreamingChatModel ollamaStreamingModel;

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp-1")
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
    }

    private static LLMRequest request() {
        return LLMRequest.builder().addMessage(LLMMessage.user("stream please")).build();
    }

    /** Drives the handler with the given partials, then completes with their concatenation. */
    private static org.mockito.stubbing.Answer<Object> emit(String... partials) {
        return invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            StringBuilder whole = new StringBuilder();
            for (String p : partials) {
                whole.append(p);
                handler.onPartialResponse(p);
            }
            handler.onCompleteResponse(response(whole.toString()));
            return null;
        };
    }

    /** Completes without ever emitting a partial — the provider that does not stream. */
    private static org.mockito.stubbing.Answer<Object> completeOnly(String text) {
        return invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(response(text));
            return null;
        };
    }

    private static List<StreamingChunk> collect(LLMProvider provider) throws Exception {
        List<StreamingChunk> chunks = new ArrayList<>();
        Consumer<StreamingChunk> sink = chunks::add;
        provider.chatStream(request(), sink).get(5, TimeUnit.SECONDS);
        return chunks;
    }

    private static void assertStreamedIncrementally(List<StreamingChunk> chunks) {
        List<StreamingChunk> content = chunks.stream().filter(StreamingChunk::hasContent).toList();
        assertThat(content).hasSize(3);
        assertThat(content).extracting(StreamingChunk::content)
                .containsExactly("Hel", "lo ", "there");
        assertThat(content).extracting(StreamingChunk::index).containsExactly(0, 1, 2);

        // Exactly one terminal chunk, carrying the finish reason and no content: the whole text
        // must not be replayed after having been streamed.
        StreamingChunk last = chunks.get(chunks.size() - 1);
        assertThat(chunks).hasSize(4);
        assertThat(last.hasContent()).isFalse();
        assertThat(last.finishReason()).isEqualTo("STOP");
    }

    private static void assertFellBackToWholeText(List<StreamingChunk> chunks) {
        List<StreamingChunk> content = chunks.stream().filter(StreamingChunk::hasContent).toList();
        assertThat(content).extracting(StreamingChunk::content).containsExactly("Hello there");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).hasContent()).isFalse();
    }

    @Nested
    @DisplayName("OpenAIProvider")
    class OpenAI {

        private OpenAIProvider provider() {
            OpenAIProvider p = OpenAIProvider.builder().apiKey("test-key").build();
            inject(p, "streamingModel", openAiStreamingModel);
            return p;
        }

        @Test
        @DisplayName("each partial reaches the handler as its own chunk, in order")
        void partialsArriveIncrementally() throws Exception {
            doAnswer(emit("Hel", "lo ", "there")).when(openAiStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            assertStreamedIncrementally(collect(provider()));
        }

        @Test
        @DisplayName("a response with no partials still delivers its whole text")
        void withoutPartialsFallsBack() throws Exception {
            doAnswer(completeOnly("Hello there")).when(openAiStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            assertFellBackToWholeText(collect(provider()));
        }
    }

    @Nested
    @DisplayName("AnthropicProvider")
    class Anthropic {

        private AnthropicProvider provider() {
            AnthropicProvider p = AnthropicProvider.builder().apiKey("test-key").build();
            inject(p, "streamingModel", anthropicStreamingModel);
            return p;
        }

        @Test
        @DisplayName("each partial reaches the handler as its own chunk, in order")
        void partialsArriveIncrementally() throws Exception {
            doAnswer(emit("Hel", "lo ", "there")).when(anthropicStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            assertStreamedIncrementally(collect(provider()));
        }

        @Test
        @DisplayName("a response with no partials still delivers its whole text")
        void withoutPartialsFallsBack() throws Exception {
            doAnswer(completeOnly("Hello there")).when(anthropicStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            assertFellBackToWholeText(collect(provider()));
        }
    }

    @Nested
    @DisplayName("OllamaProvider")
    class Ollama {

        private OllamaProvider provider() {
            OllamaProvider p = OllamaProvider.builder().modelName("llama3.2").build();
            inject(p, "streamingModel", ollamaStreamingModel);
            return p;
        }

        @Test
        @DisplayName("each partial reaches the handler as its own chunk, in order")
        void partialsArriveIncrementally() throws Exception {
            doAnswer(emit("Hel", "lo ", "there")).when(ollamaStreamingModel)
                    .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            List<StreamingChunk> chunks = collect(provider());
            assertThat(chunks.stream().filter(StreamingChunk::hasContent))
                    .extracting(StreamingChunk::content)
                    .containsExactly("Hel", "lo ", "there");
            assertThat(chunks).hasSize(4);
            assertThat(chunks.get(3).hasContent()).isFalse();
        }
    }
}
