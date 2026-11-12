package dev.agenor.adapters.llm;

import static dev.agenor.adapters.llm.LLMTestFixtures.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import dev.agenor.adapters.llm.anthropic.AnthropicProvider;
import dev.agenor.adapters.llm.ollama.OllamaProvider;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.llm.FunctionDefinition;
import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.StreamingChunk;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;

/**
 * A {@link StreamingChunk} carries text only, so a function call cannot come back through
 * {@code chatStream}. OpenAI and Anthropic offered the request's functions to the model anyway and,
 * when it answered with a call, completed the stream normally with one empty chunk: the call was
 * lost without an error. Ollama left the functions off, so the model could not see a tool it might
 * have needed and could answer without it.
 *
 * <p>All three now behave as {@code ScriptedLLMProvider} does: the functions are offered, a reply in
 * text streams as before, and a reply that is a function call fails the stream with an
 * {@link LLMException} that says to use {@code chat()}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("A function call in a streamed response")
class StreamedFunctionCallTest {

    @Mock private OpenAiStreamingChatModel openAiStreamingModel;
    @Mock private AnthropicStreamingChatModel anthropicStreamingModel;
    @Mock private OllamaStreamingChatModel ollamaStreamingModel;

    enum Provider { OPENAI, ANTHROPIC, OLLAMA }

    private record Subject(LLMProvider provider, StreamingChatModel model) {}

    private Subject subject(Provider which) {
        return switch (which) {
            case OPENAI -> {
                OpenAIProvider p = OpenAIProvider.builder().apiKey("test-key").build();
                inject(p, "streamingModel", openAiStreamingModel);
                yield new Subject(p, openAiStreamingModel);
            }
            case ANTHROPIC -> {
                AnthropicProvider p = AnthropicProvider.builder().apiKey("test-key").build();
                inject(p, "streamingModel", anthropicStreamingModel);
                yield new Subject(p, anthropicStreamingModel);
            }
            case OLLAMA -> {
                OllamaProvider p = OllamaProvider.builder().modelName("llama3.2").build();
                inject(p, "streamingModel", ollamaStreamingModel);
                yield new Subject(p, ollamaStreamingModel);
            }
        };
    }

    private static LLMRequest requestWithFunction() {
        return LLMRequest.builder()
                .addMessage(LLMMessage.user("What is the weather in Rome?"))
                .functions(List.of(FunctionDefinition.builder("get_weather")
                        .description("Current weather for a city")
                        .stringParameter("location", "City name", true)
                        .build()))
                .build();
    }

    private static ChatResponse callResponse(String text) {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call_1").name("get_weather").arguments("{\"location\":\"Rome\"}").build();
        AiMessage message = text == null ? AiMessage.from(List.of(call)) : AiMessage.from(text, List.of(call));
        return ChatResponse.builder()
                .aiMessage(message)
                .metadata(ChatResponseMetadata.builder()
                        .id("resp-1").finishReason(FinishReason.TOOL_EXECUTION).build())
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp-1").finishReason(FinishReason.STOP).build())
                .build();
    }

    /** Streams the partials, then completes with {@code complete}; records the request it got. */
    private static Answer<Object> reply(AtomicReference<ChatRequest> sent, ChatResponse complete,
                                        String... partials) {
        return invocation -> {
            sent.set(invocation.getArgument(0));
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            for (String p : partials) {
                handler.onPartialResponse(p);
            }
            handler.onCompleteResponse(complete);
            return null;
        };
    }

    private static void stub(StreamingChatModel model, Answer<Object> answer) {
        doAnswer(answer).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Provider.class)
    @DisplayName("fails the stream instead of completing it empty")
    void callOnlyFailsTheStream(Provider which) throws Exception {
        Subject s = subject(which);
        AtomicReference<ChatRequest> sent = new AtomicReference<>();
        stub(s.model(), reply(sent, callResponse(null)));
        List<StreamingChunk> chunks = new ArrayList<>();

        Throwable thrown = catchThrowable(() ->
                s.provider().chatStream(requestWithFunction(), chunks::add).get(5, TimeUnit.SECONDS));

        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).isInstanceOf(LLMException.class);
        LLMException e = (LLMException) thrown.getCause();
        assertThat(e.getErrorType()).isEqualTo(LLMException.ErrorType.INVALID_REQUEST);
        assertThat(e.isRetryable()).isFalse();
        assertThat(e.getMessage()).contains("get_weather").contains("chat()");
        // No terminal chunk: the stream did not finish, and a handler must not read it as done.
        assertThat(chunks).noneMatch(StreamingChunk::isLast);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Provider.class)
    @DisplayName("keeps the text already streamed before the call, then fails")
    void textThenCallKeepsTheText(Provider which) {
        Subject s = subject(which);
        AtomicReference<ChatRequest> sent = new AtomicReference<>();
        stub(s.model(), reply(sent, callResponse("Let me check. "), "Let me ", "check. "));
        List<StreamingChunk> chunks = new ArrayList<>();

        Throwable thrown = catchThrowable(() ->
                s.provider().chatStream(requestWithFunction(), chunks::add).get(5, TimeUnit.SECONDS));

        assertThat(thrown).hasCauseInstanceOf(LLMException.class);
        assertThat(chunks).extracting(StreamingChunk::content).containsExactly("Let me ", "check. ");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Provider.class)
    @DisplayName("offers the functions, and a reply in text streams as before")
    void textReplyStillStreams(Provider which) throws Exception {
        Subject s = subject(which);
        AtomicReference<ChatRequest> sent = new AtomicReference<>();
        stub(s.model(), reply(sent, textResponse("Sunny."), "Sunny."));
        List<StreamingChunk> chunks = new ArrayList<>();

        s.provider().chatStream(requestWithFunction(), chunks::add).get(5, TimeUnit.SECONDS);

        assertThat(sent.get().toolSpecifications()).extracting(t -> t.name()).containsExactly("get_weather");
        assertThat(chunks).filteredOn(StreamingChunk::hasContent)
                .extracting(StreamingChunk::content).containsExactly("Sunny.");
        assertThat(chunks.get(chunks.size() - 1).isLast()).isTrue();
    }
}
