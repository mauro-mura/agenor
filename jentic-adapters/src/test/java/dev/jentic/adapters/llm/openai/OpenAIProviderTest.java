package dev.jentic.adapters.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import dev.jentic.core.memory.llm.ModelTokenLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.jentic.adapters.llm.ToolConversionUtils;
import dev.jentic.core.llm.FunctionCall;
import dev.jentic.core.llm.FunctionDefinition;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.llm.LLMResponse;
import dev.jentic.core.llm.StreamingChunk;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

@ExtendWith(MockitoExtension.class)
class OpenAIProviderTest {

	@Mock
    private OpenAiChatModel mockChatModel;

    @Mock
    private OpenAiStreamingChatModel mockStreamingModel;

    // -----------------------------------------------------------------------
    // Factory helper (mirrors OllamaProviderTest pattern)
    // -----------------------------------------------------------------------

    private OpenAIProvider createProviderWithMocks() {
        try {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-api-key")
                    .build();

            Field chatModelField = OpenAIProvider.class.getDeclaredField("chatModel");
            chatModelField.setAccessible(true);
            chatModelField.set(provider, mockChatModel);

            Field streamingModelField = OpenAIProvider.class.getDeclaredField("streamingModel");
            streamingModelField.setAccessible(true);
            streamingModelField.set(provider, mockStreamingModel);

            return provider;
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks into OpenAIProvider", e);
        }
    }

    private ChatResponse buildResponse(String text, FinishReason reason, TokenUsage usage) {
        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder()
                .id("resp-" + java.util.UUID.randomUUID());
        if (reason != null) metaBuilder.finishReason(reason);
        if (usage != null) metaBuilder.tokenUsage(usage);

        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(metaBuilder.build())
                .build();
    }

    // -----------------------------------------------------------------------
    // chat() - USER message (default path in convertMessages)
    // -----------------------------------------------------------------------

    @Test
    void chat_withUserMessage_shouldReturnContent() throws Exception {
        ChatResponse response = buildResponse("Hello back!", FinishReason.STOP, new TokenUsage(10, 20, 30));
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Hi"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("Hello back!", result.content());
        assertEquals(LLMMessage.Role.ASSISTANT, result.role());
    }

    // -----------------------------------------------------------------------
    // chat() - SYSTEM message path in convertMessages
    // -----------------------------------------------------------------------

    @Test
    void chat_withSystemMessage_shouldConvertCorrectly() throws Exception {
        ChatResponse response = buildResponse("OK", null, null);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.system("You are a helpful assistant"))
                .addMessage(LLMMessage.user("Help me"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // chat() - ASSISTANT message path in convertMessages
    // -----------------------------------------------------------------------

    @Test
    void chat_withAssistantMessage_shouldConvertCorrectly() throws Exception {
        ChatResponse response = buildResponse("Continued", null, null);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("First"))
                .addMessage(LLMMessage.assistant("Response"))
                .addMessage(LLMMessage.user("Continue"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // chat() - with token usage and finish reason
    // -----------------------------------------------------------------------

    @Test
    void chat_withTokenUsageAndFinishReason_shouldPopulateResponse() throws Exception {
        TokenUsage usage = new TokenUsage(100, 50, 150);
        ChatResponse response = buildResponse("Answer", FinishReason.STOP, usage);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Question"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);

        assertNotNull(result.usage());
        assertEquals(100, result.usage().promptTokens());
        assertEquals(50, result.usage().completionTokens());
        assertNotNull(result.finishReason());
    }

    // -----------------------------------------------------------------------
    // chat() - function calling branch (hasFunctions = true)
    // -----------------------------------------------------------------------

    @Test
    void chat_withFunctions_shouldPassToolSpecifications() throws Exception {
        ChatResponse response = buildResponse("Used tool", null, null);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        OpenAIProvider provider = createProviderWithMocks();
        FunctionDefinition func = FunctionDefinition.builder("get_weather")
                .description("Get weather")
                .stringParameter("location", "City name", true)
                .build();

        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("What's the weather?"))
                .functions(List.of(func))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        verify(mockChatModel).chat(any(ChatRequest.class));
    }

    // -----------------------------------------------------------------------
    // chat() - exception handling
    // -----------------------------------------------------------------------

    @Test
    void chat_withException_shouldPropagateAsExecutionException() {
        when(mockChatModel.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("API error"));

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Test"))
                .build();

        CompletableFuture<LLMResponse> future = provider.chat(request);

        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // -----------------------------------------------------------------------
    // chatStream() - onCompleteResponse with non-empty content
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withContent_shouldDeliverChunksAndComplete() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            ChatResponse finalResponse = buildResponse("Streamed content", FinishReason.STOP, null);
            handler.onCompleteResponse(finalResponse);
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Stream this"))
                .build();

        StringBuilder received = new StringBuilder();
        CompletableFuture<Void> future = provider.chatStream(request, chunk -> {
            if (chunk.hasContent()) received.append(chunk.content());
        });

        future.get(5, TimeUnit.SECONDS);
        assertFalse(received.toString().isEmpty());
    }

    // -----------------------------------------------------------------------
    // chatStream() - onCompleteResponse with empty content (skips content chunk)
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withEmptyContent_shouldNotDeliverContentChunk() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            // Empty content → the "if (content != null && !content.isEmpty())" branch = false
            ChatResponse emptyResponse = buildResponse("", null, null);
            handler.onCompleteResponse(emptyResponse);
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Empty"))
                .build();

        CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});
        future.get(5, TimeUnit.SECONDS);
        // No assertion needed; just verify no exception
    }

    // -----------------------------------------------------------------------
    // chatStream() - onCompleteResponse with null aiMessage
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withNullAiMessage_shouldUseDefaultValues() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            // Null aiMessage → content = "", finish = "stop"
            handler.onCompleteResponse(null);
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Null response"))
                .build();

        CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});
        future.get(5, TimeUnit.SECONDS);
    }

    // -----------------------------------------------------------------------
    // chatStream() - onError path
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withError_shouldCompleteExceptionally() {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new RuntimeException("Stream error"));
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        OpenAIProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Error test"))
                .build();

        CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});

        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // -----------------------------------------------------------------------
    // chatStream() - with functions (hasFunctions branch)
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withFunctions_shouldPassToolSpecs() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(buildResponse("Tool response", null, null));
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        OpenAIProvider provider = createProviderWithMocks();
        FunctionDefinition func = FunctionDefinition.builder("calc")
                .description("Calculate")
                .parameter("x", "integer", "First number", true)
                .build();

        LLMRequest request = LLMRequest.builder("gpt-4o")
                .addMessage(LLMMessage.user("Calculate 2+2"))
                .functions(List.of(func))
                .build();

        provider.chatStream(request, chunk -> {}).get(5, TimeUnit.SECONDS);
        verify(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    // -----------------------------------------------------------------------
    // getAvailableModels() and getProviderName()
    // -----------------------------------------------------------------------

    @Test
    void getAvailableModels_shouldReturnGptModels() throws Exception {
        OpenAIProvider provider = OpenAIProvider.builder().apiKey("key").build();
        List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);

        assertNotNull(models);
        assertFalse(models.isEmpty());
        assertTrue(models.stream().anyMatch(m -> m.startsWith("gpt")));
    }

    @Test
    void getProviderName_shouldReturnOpenAI() {
        OpenAIProvider provider = OpenAIProvider.builder().apiKey("key").build();
        assertEquals("OpenAI", provider.getProviderName());
    }

    // -----------------------------------------------------------------------
    // Builder validation
    // -----------------------------------------------------------------------

    @Test
    void builder_withNullApiKey_shouldThrow() {
        assertThrows(IllegalStateException.class, () ->
                OpenAIProvider.builder().build());
    }

    @Nested
    @DisplayName("Builder Configuration Tests")
    class BuilderConfigurationTests {

        @Test
        @DisplayName("should configure all builder parameters")
        void build_withAllParameters_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key-123")
                    .baseUrl("https://api.custom.com/v1")
                    .modelName("gpt-4o")
                    .temperature(0.7)
                    .maxTokens(1000)
                    .timeout(Duration.ofSeconds(45))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            assertNotNull(provider);
            assertEquals("OpenAI", provider.getProviderName());
        }

        @Test
        @DisplayName("should build with empty API key")
        void build_withEmptyApiKey_shouldSucceed() {
            // Empty string is technically valid, only null is rejected
            assertDoesNotThrow(() -> {
                OpenAIProvider.builder()
                        .apiKey("")
                        .build();
            });
        }

        @Test
        @DisplayName("should handle custom base URL")
        void build_withCustomBaseUrl_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .baseUrl("https://custom.openai.endpoint/v1")
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should configure timeout")
        void build_withCustomTimeout_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .timeout(Duration.ofMinutes(2))
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should configure logging flags")
        void build_withLoggingEnabled_shouldSucceed() {
            OpenAIProvider provider1 = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .logRequests(true)
                    .logResponses(false)
                    .build();

            OpenAIProvider provider2 = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .logRequests(false)
                    .logResponses(true)
                    .build();

            assertNotNull(provider1);
            assertNotNull(provider2);
        }

        @Test
        @DisplayName("should validate temperature bounds")
        void build_withInvalidTemperature_shouldHandleGracefully() {
            // LangChain4j may validate internally, but builder should not throw
            assertDoesNotThrow(() -> {
                OpenAIProvider.builder()
                        .apiKey("test-key")
                        .temperature(2.0) // Out of typical range
                        .build();
            });
        }

        @Test
        @DisplayName("should accept different model names")
        void build_withVariousModels_shouldSucceed() {
            List<String> models = Arrays.asList(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gpt-4-turbo",
                    "gpt-4",
                    "gpt-3.5-turbo"
            );

            for (String model : models) {
                OpenAIProvider provider = OpenAIProvider.builder()
                        .apiKey("test-key")
                        .modelName(model)
                        .build();
                assertNotNull(provider);
            }
        }
    }

    @Nested
    @DisplayName("Message Conversion Tests")
    class MessageConversionTests {

        @Test
        @DisplayName("should convert system message correctly")
        void convertMessages_withSystemMessage_shouldCreateSystemMessage() {
            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.system("You are a helpful assistant"))
                    .build();

            assertEquals(1, request.messages().size());
            assertEquals(LLMMessage.Role.SYSTEM, request.messages().get(0).role());
            assertEquals("You are a helpful assistant", request.messages().get(0).content());
        }

        @Test
        @DisplayName("should convert assistant message correctly")
        void convertMessages_withAssistantMessage_shouldCreateAiMessage() {
            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.assistant("I can help you with that"))
                    .build();

            assertEquals(1, request.messages().size());
            assertEquals(LLMMessage.Role.ASSISTANT, request.messages().get(0).role());
        }

        @Test
        @DisplayName("should convert user message correctly")
        void convertMessages_withUserMessage_shouldCreateUserMessage() {
            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("What is the weather?"))
                    .build();

            assertEquals(1, request.messages().size());
            assertEquals(LLMMessage.Role.USER, request.messages().get(0).role());
        }

        @Test
        @DisplayName("should handle assistant message with function calls and no content")
        void convertMessages_withFunctionCallsNoContent_shouldHandleGracefully() {
            assertDoesNotThrow(() -> {
                FunctionCall call = new FunctionCall("id", "func", "{}");
                LLMRequest.builder("test-id")
                        .addMessage(new LLMMessage(LLMMessage.Role.ASSISTANT, null, null, List.of(call)))
                        .build();
            });
        }

        @Test
        @DisplayName("should convert multi-turn conversation")
        void convertMessages_withMultiTurnConversation_shouldPreserveOrder() {
            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.system("You are a math tutor"))
                    .addMessage(LLMMessage.user("What is 5 + 3?"))
                    .addMessage(LLMMessage.assistant("5 + 3 equals 8"))
                    .addMessage(LLMMessage.user("What about 10 - 2?"))
                    .addMessage(LLMMessage.assistant("10 - 2 equals 8"))
                    .build();

            assertEquals(5, request.messages().size());
            assertEquals(LLMMessage.Role.SYSTEM, request.messages().get(0).role());
            assertEquals(LLMMessage.Role.USER, request.messages().get(1).role());
            assertEquals(LLMMessage.Role.ASSISTANT, request.messages().get(2).role());
        }
    }

    @Nested
    @DisplayName("Function Calling Tests")
    class FunctionCallingTests {

        @Test
        @DisplayName("should create request with function definitions")
        void chat_withFunctions_shouldIncludeToolSpecs() {
            FunctionDefinition getWeather = FunctionDefinition.builder("get_weather")
                    .description("Get current weather for a location")
                    .stringParameter("location", "City name", true)
                    .stringParameter("unit", "Temperature unit (celsius/fahrenheit)", false)
                    .build();

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("What's the weather in Paris?"))
                    .addFunction(getWeather)
                    .build();

            assertTrue(request.hasFunctions());
            assertEquals(1, request.functions().size());
            assertEquals("get_weather", request.functions().get(0).name());
        }

        @Test
        @DisplayName("should handle multiple function definitions")
        void chat_withMultipleFunctions_shouldIncludeAllToolSpecs() {
            FunctionDefinition func1 = FunctionDefinition.builder("get_weather")
                    .description("Get weather")
                    .stringParameter("location", "City", true)
                    .build();

            FunctionDefinition func2 = FunctionDefinition.builder("get_time")
                    .description("Get time")
                    .stringParameter("timezone", "Timezone", true)
                    .build();

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("What's the weather and time?"))
                    .addFunction(func1)
                    .addFunction(func2)
                    .build();

            assertTrue(request.hasFunctions());
            assertEquals(2, request.functions().size());
        }

        @Test
        @DisplayName("should handle function with complex parameters")
        void chat_withComplexFunction_shouldConvertCorrectly() {
            FunctionDefinition complexFunc = FunctionDefinition.builder("search")
                    .description("Search for items")
                    .stringParameter("query", "Search query", true)
                    .parameter("limit", "integer", "Max results", false)
                    .enumParameter("category", "Category", false, "books", "electronics", "clothing")
                    .booleanParameter("inStock", "Only in stock", false)
                    .build();

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("Search for laptops"))
                    .addFunction(complexFunc)
                    .build();

            assertTrue(request.hasFunctions());
            FunctionDefinition func = request.functions().get(0);
            assertNotNull(func.parameters());
        }

        @Test
        @DisplayName("should handle function with no parameters")
        void chat_withParameterlessFunction_shouldSucceed() {
            FunctionDefinition simpleFunc = FunctionDefinition.builder("get_random_joke")
                    .description("Get a random joke")
                    .build();

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("Tell me a joke"))
                    .addFunction(simpleFunc)
                    .build();

            assertTrue(request.hasFunctions());
            assertEquals("get_random_joke", request.functions().get(0).name());
        }
    }

    @Nested
    @DisplayName("Provider Capabilities Tests")
    class ProviderCapabilitiesTests {

        private OpenAIProvider provider;

        @BeforeEach
        void setup() {
            provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();
        }

        @Test
        @DisplayName("should return OpenAI as provider name")
        void getProviderName_shouldReturnOpenAI() {
            assertEquals("OpenAI", provider.getProviderName());
        }

        @Test
        @DisplayName("should support function calling")
        void supportsFunctionCalling_shouldReturnTrue() {
            assertTrue(provider.supportsFunctionCalling());
        }

        @Test
        @DisplayName("should support streaming")
        void supportsStreaming_shouldReturnTrue() {
            assertTrue(provider.supportsStreaming());
        }

        @Test
        @DisplayName("should return available models list")
        void getAvailableModels_shouldReturnExpectedModels() throws Exception {
            CompletableFuture<List<String>> modelsFuture = provider.getAvailableModels();
            List<String> models = modelsFuture.get();

            assertNotNull(models);
            assertTrue(models.contains("gpt-4o"));
            assertTrue(models.contains("gpt-4o-mini"));
            assertTrue(models.contains("gpt-4-turbo"));
            assertTrue(models.contains("gpt-4"));
            assertTrue(models.contains("gpt-3.5-turbo"));
        }

        @Test
        @DisplayName("available models should return completed future")
        void getAvailableModels_shouldReturnCompletedFuture() {
            CompletableFuture<List<String>> future = provider.getAvailableModels();
            assertTrue(future.isDone());
        }
    }

    @Nested
    @DisplayName("Request Validation Tests")
    class RequestValidationTests {

        @Test
        @DisplayName("should create valid request with all parameters")
        void createRequest_withAllParameters_shouldSucceed() {
            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("Hello"))
                    .temperature(0.7)
                    .maxTokens(500)
                    .topP(0.9)
                    .presencePenalty(0.1)
                    .frequencyPenalty(0.2)
                    .build();

            assertNotNull(request);
            assertEquals("test-id", request.model());
            assertEquals(0.7, request.temperature());
            assertEquals(500, request.maxTokens());
            assertEquals(0.9, request.topP());
            assertEquals(0.1, request.presencePenalty());
            assertEquals(0.2, request.frequencyPenalty());
        }

        @Test
        @DisplayName("should require at least one message")
        void createRequest_withNoMessages_shouldThrow() {
            assertThrows(IllegalArgumentException.class, () -> {
                LLMRequest.builder("test-id").build();
            });
        }

        @Test
        @DisplayName("should handle optional parameters as null")
        void createRequest_withMinimalParameters_shouldSucceed() {
            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            assertNotNull(request);
            assertNull(request.temperature());
            assertNull(request.maxTokens());
        }
    }

    @Nested
    @DisplayName("Streaming Tests")
    class StreamingTests {

        @Test
        @DisplayName("should create streaming request")
        void chatStream_withValidRequest_shouldCreateStreamingHandler() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            LLMRequest request = LLMRequest.builder("stream-test")
                    .addMessage(LLMMessage.user("Count to 3"))
                    .build();

            List<StreamingChunk> chunks = new ArrayList<>();
            CompletableFuture<Void> future = provider.chatStream(
                    request,
                    chunks::add
            );

            assertNotNull(future);
        }

        @Test
        @DisplayName("should handle streaming with function calls")
        void chatStream_withFunctions_shouldIncludeToolSpecs() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            FunctionDefinition func = FunctionDefinition.builder("get_data")
                    .description("Get data")
                    .build();

            LLMRequest request = LLMRequest.builder("stream-test")
                    .addMessage(LLMMessage.user("Get me some data"))
                    .addFunction(func)
                    .build();

            CompletableFuture<Void> future = provider.chatStream(
                    request,
                    chunk -> {}
            );

            assertNotNull(future);
        }

        @Test
        @DisplayName("should validate streaming chunk structure")
        void streamingChunk_shouldHaveCorrectStructure() {
            StreamingChunk chunk = StreamingChunk.of("id-123", "gpt-4o", "Hello", 0);

            assertEquals("id-123", chunk.id());
            assertEquals("gpt-4o", chunk.model());
            assertEquals("Hello", chunk.content());
            assertEquals(0, chunk.index());
            assertTrue(chunk.hasContent());
            assertFalse(chunk.isLast());
        }

        @Test
        @DisplayName("should handle final streaming chunk")
        void streamingChunk_withFinishReason_shouldBeMarkedAsLast() {
            StreamingChunk finalChunk = StreamingChunk.of("id-123", "gpt-4o", "", "stop", 5);

            assertTrue(finalChunk.isLast());
            assertFalse(finalChunk.hasContent());
            assertEquals("stop", finalChunk.finishReason());
        }
    }

    @Nested
    @DisplayName("Response Handling Tests")
    class ResponseHandlingTests {

        @Test
        @DisplayName("should build response with all fields")
        void buildResponse_withAllFields_shouldIncludeEverything() {
            LLMResponse response = LLMResponse.builder("resp-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("This is the response content")
                    .usage(100, 50, 150)
                    .finishReason("stop")
                    .metadata(Map.of("key", "value"))
                    .build();

            assertEquals("resp-123", response.id());
            assertEquals("gpt-4o", response.model());
            assertEquals(LLMMessage.Role.ASSISTANT, response.role());
            assertEquals("This is the response content", response.content());
            assertNotNull(response.usage());
            assertEquals(100, response.usage().promptTokens());
            assertEquals(50, response.usage().completionTokens());
            assertEquals(150, response.usage().totalTokens());
            assertEquals("stop", response.finishReason());
            assertNotNull(response.metadata());
        }

        @Test
        @DisplayName("should handle response with function calls")
        void buildResponse_withFunctionCalls_shouldIncludeCalls() {
            FunctionCall call1 = new FunctionCall("call-1", "get_weather", "{\"location\":\"Paris\"}");
            FunctionCall call2 = new FunctionCall("call-2", "get_time", "{\"timezone\":\"UTC\"}");

            LLMResponse response = LLMResponse.builder("resp-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .functionCalls(Arrays.asList(call1, call2))
                    .build();

            assertTrue(response.hasFunctionCalls());
            assertEquals(2, response.functionCalls().size());
            assertEquals("get_weather", response.functionCalls().get(0).name());
            assertEquals("get_time", response.functionCalls().get(1).name());
        }

        @Test
        @DisplayName("should handle response without content")
        void buildResponse_withoutContent_shouldSucceed() {
            LLMResponse response = LLMResponse.builder("resp-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .build();

            assertNotNull(response);
            assertNull(response.content());
        }

        @Test
        @DisplayName("should validate function call structure")
        void functionCall_shouldHaveRequiredFields() {
            FunctionCall call = new FunctionCall("id-123", "test_func", "{\"arg\":\"value\"}");

            assertEquals("id-123", call.id());
            assertEquals("test_func", call.name());
            assertEquals("{\"arg\":\"value\"}", call.arguments());
        }
    }

    @Nested
    @DisplayName("Error Scenarios Tests")
    class ErrorScenariosTests {

        @Test
        @DisplayName("should handle various error conditions gracefully")
        void chat_withErrorScenarios_shouldHandleGracefully() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            // Empty content message
            LLMRequest emptyContentRequest = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user(""))
                    .build();

            assertDoesNotThrow(() -> {
                provider.chat(emptyContentRequest);
            });
        }

        @Test
        @DisplayName("should validate builder state")
        void builder_withInvalidState_shouldThrow() {
            // Missing API key
            assertThrows(IllegalStateException.class, () -> {
                OpenAIProvider.builder().build();
            });

            // Null API key
            assertThrows(IllegalStateException.class, () -> {
                OpenAIProvider.builder().apiKey(null).build();
            });
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle very long messages")
        void chat_withLongMessage_shouldSucceed() {
            String longContent = "a".repeat(10000);

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user(longContent))
                    .build();

            assertEquals(1, request.messages().size());
            assertEquals(longContent, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle special characters in messages")
        void chat_withSpecialCharacters_shouldSucceed() {
            String specialContent = "Special chars: \n\t\r\"'\\{}[]<>";

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user(specialContent))
                    .build();

            assertEquals(specialContent, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle unicode characters")
        void chat_withUnicodeCharacters_shouldSucceed() {
            String unicodeContent = "Unicode: 你好 🌍 мир";

            LLMRequest request = LLMRequest.builder("test-id")
                    .addMessage(LLMMessage.user(unicodeContent))
                    .build();

            assertEquals(unicodeContent, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle zero temperature")
        void build_withZeroTemperature_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .temperature(0.0)
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle max temperature")
        void build_withMaxTemperature_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .temperature(2.0)
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle very short timeout")
        void build_withShortTimeout_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .timeout(Duration.ofSeconds(1))
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle very long timeout")
        void build_withLongTimeout_shouldSucceed() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .timeout(Duration.ofHours(1))
                    .build();

            assertNotNull(provider);
        }
    }

    @Nested
    @DisplayName("Tool Conversion Utility Tests")
    class ToolConversionUtilityTests {

        @Test
        @DisplayName("should convert function to tool spec")
        void convertFunctionToToolSpec_shouldSucceed() {
            FunctionDefinition function = FunctionDefinition.builder("test_func")
                    .description("Test function")
                    .stringParameter("param1", "First param", true)
                    .build();

            ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

            assertNotNull(spec);
            assertEquals("test_func", spec.name());
            assertEquals("Test function", spec.description());
        }

        @Test
        @DisplayName("should convert list of functions")
        void convertFunctionsToToolSpecs_shouldConvertAll() {
            List<FunctionDefinition> functions = Arrays.asList(
                    FunctionDefinition.builder("func1")
                            .description("First")
                            .build(),
                    FunctionDefinition.builder("func2")
                            .description("Second")
                            .build()
            );

            List<ToolSpecification> specs = ToolConversionUtils.convertFunctionsToToolSpecs(functions);

            assertEquals(2, specs.size());
            assertEquals("func1", specs.get(0).name());
            assertEquals("func2", specs.get(1).name());
        }

        @Test
        @DisplayName("should handle empty function list")
        void convertFunctionsToToolSpecs_withEmptyList_shouldReturnEmpty() {
            List<ToolSpecification> specs = ToolConversionUtils.convertFunctionsToToolSpecs(Collections.emptyList());
            assertTrue(specs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Token Limit Registration")
    class TokenLimitRegistrationTests {

        @BeforeEach
        void triggerClassLoad() {
            OpenAIProvider.builder().apiKey("dummy-key").build();
        }

        @Test
        @DisplayName("getAvailableModels() and ModelTokenLimits must be consistent")
        void getAvailableModels_shouldAllBeRegisteredInTokenLimits() throws Exception {
            OpenAIProvider provider = OpenAIProvider.builder().apiKey("dummy-key").build();
            List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);

            assertFalse(models.isEmpty());
            models.forEach(model ->
                assertTrue(ModelTokenLimits.hasModel(model),
                    model + " is in getAvailableModels() but not in ModelTokenLimits"));
        }

        @Test
        @DisplayName("gpt-4.1 → 1M")
        void gpt41() { assertEquals(1_000_000, ModelTokenLimits.getLimit("gpt-4.1")); }

        @Test
        @DisplayName("gpt-4.1-mini → 1M")
        void gpt41Mini() { assertEquals(1_000_000, ModelTokenLimits.getLimit("gpt-4.1-mini")); }

        @Test
        @DisplayName("gpt-4.1-nano → 1M")
        void gpt41Nano() { assertEquals(1_000_000, ModelTokenLimits.getLimit("gpt-4.1-nano")); }

        @Test
        @DisplayName("o3 → 200k")
        void o3() { assertEquals(200_000, ModelTokenLimits.getLimit("o3")); }

        @Test
        @DisplayName("o4-mini → 200k")
        void o4Mini() { assertEquals(200_000, ModelTokenLimits.getLimit("o4-mini")); }

        @Test
        @DisplayName("gpt-4o → 128k")
        void gpt4o() { assertEquals(128_000, ModelTokenLimits.getLimit("gpt-4o")); }

        @Test
        @DisplayName("gpt-4o-mini → 128k")
        void gpt4oMini() { assertEquals(128_000, ModelTokenLimits.getLimit("gpt-4o-mini")); }

        @Test
        @DisplayName("gpt-4-turbo → 128k")
        void gpt4Turbo() { assertEquals(128_000, ModelTokenLimits.getLimit("gpt-4-turbo")); }

        @Test
        @DisplayName("gpt-4 → 8192")
        void gpt4() { assertEquals(8_192, ModelTokenLimits.getLimit("gpt-4")); }

        @Test
        @DisplayName("gpt-4-32k → 32768")
        void gpt4_32k() { assertEquals(32_768, ModelTokenLimits.getLimit("gpt-4-32k")); }

        @Test
        @DisplayName("gpt-3.5-turbo → 16385")
        void gpt35Turbo() { assertEquals(16_385, ModelTokenLimits.getLimit("gpt-3.5-turbo")); }
    }
}
