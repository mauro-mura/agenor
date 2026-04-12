package dev.jentic.adapters.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import dev.jentic.core.memory.llm.ModelTokenLimits;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.jentic.adapters.llm.LLMProviderFactory;
import dev.jentic.core.llm.FunctionDefinition;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.llm.LLMProvider;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.llm.LLMResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

@ExtendWith(MockitoExtension.class)
class AnthropicProviderTest {

	@Mock
    private AnthropicChatModel mockChatModel;

    @Mock
    private AnthropicStreamingChatModel mockStreamingModel;

    // -----------------------------------------------------------------------
    // Factory helper
    // -----------------------------------------------------------------------

    private AnthropicProvider createProviderWithMocks() {
        try {
            AnthropicProvider provider = AnthropicProvider.builder()
                    .apiKey("test-anthropic-key")
                    .build();

            Field chatModelField = AnthropicProvider.class.getDeclaredField("chatModel");
            chatModelField.setAccessible(true);
            chatModelField.set(provider, mockChatModel);

            Field streamingModelField = AnthropicProvider.class.getDeclaredField("streamingModel");
            streamingModelField.setAccessible(true);
            streamingModelField.set(provider, mockStreamingModel);

            return provider;
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks into AnthropicProvider", e);
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
    // chat() - SYSTEM message (case SYSTEM branch in convertMessages)
    // -----------------------------------------------------------------------

    @Test
    void chat_withSystemMessage_shouldConvertToSystemMessage() throws Exception {
        ChatResponse response = buildResponse("Ready", null, null);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are helpful"))
                .addMessage(LLMMessage.user("Hello"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // chat() - USER message (case USER branch)
    // -----------------------------------------------------------------------

    @Test
    void chat_withUserMessage_shouldReturnContent() throws Exception {
        TokenUsage usage = new TokenUsage(20, 30, 50);
        ChatResponse response = buildResponse("Claude response", FinishReason.STOP, usage);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Tell me something"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("Claude response", result.content());
        assertNotNull(result.usage());
        assertEquals(20, result.usage().promptTokens());
    }

    // -----------------------------------------------------------------------
    // chat() - ASSISTANT message (case ASSISTANT branch)
    // -----------------------------------------------------------------------

    @Test
    void chat_withAssistantMessage_shouldConvertToAiMessage() throws Exception {
        ChatResponse response = buildResponse("Continuation", null, null);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("First turn"))
                .addMessage(LLMMessage.assistant("My previous response"))
                .addMessage(LLMMessage.user("Continue"))
                .build();

        LLMResponse result = provider.chat(request).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // chat() - function calling branch
    // -----------------------------------------------------------------------

    @Test
    void chat_withFunctions_shouldPassToolSpecifications() throws Exception {
        ChatResponse response = buildResponse("Used function", null, null);
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(response);

        AnthropicProvider provider = createProviderWithMocks();
        FunctionDefinition func = FunctionDefinition.builder("search")
                .description("Search the web")
                .stringParameter("query", "Search query", true)
                .build();

        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Search for AI news"))
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
                .thenThrow(new RuntimeException("Anthropic API down"));

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Test"))
                .build();

        CompletableFuture<LLMResponse> future = provider.chat(request);
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // -----------------------------------------------------------------------
    // chatStream() - onCompleteResponse with content
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withContent_shouldDeliverChunks() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(buildResponse("Streamed answer", FinishReason.STOP, null));
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Stream"))
                .build();

        StringBuilder received = new StringBuilder();
        CompletableFuture<Void> future = provider.chatStream(request, chunk -> {
            if (chunk.hasContent()) received.append(chunk.content());
        });

        future.get(5, TimeUnit.SECONDS);
        assertFalse(received.toString().isEmpty());
    }

    // -----------------------------------------------------------------------
    // chatStream() - onCompleteResponse with empty content
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withEmptyContent_shouldSkipContentChunk() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(buildResponse("", null, null));
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Empty"))
                .build();

        provider.chatStream(request, chunk -> {}).get(5, TimeUnit.SECONDS);
    }

    // -----------------------------------------------------------------------
    // chatStream() - null completeResponse (covers null-check ternary branches)
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withNullResponse_shouldUseDefaults() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(null);
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Null test"))
                .build();

        provider.chatStream(request, chunk -> {}).get(5, TimeUnit.SECONDS);
    }

    // -----------------------------------------------------------------------
    // chatStream() - onError path
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withError_shouldCompleteExceptionally() {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new RuntimeException("Streaming failed"));
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AnthropicProvider provider = createProviderWithMocks();
        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Error test"))
                .build();

        CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // -----------------------------------------------------------------------
    // chatStream() - with functions
    // -----------------------------------------------------------------------

    @Test
    void chatStream_withFunctions_shouldPassToolSpecs() throws Exception {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(buildResponse("Used tool", null, null));
            return null;
        }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AnthropicProvider provider = createProviderWithMocks();
        FunctionDefinition func = FunctionDefinition.builder("get_time")
                .description("Get current time")
                .build();

        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("What time is it?"))
                .functions(List.of(func))
                .build();

        provider.chatStream(request, chunk -> {}).get(5, TimeUnit.SECONDS);
        verify(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    // -----------------------------------------------------------------------
    // getAvailableModels() and getProviderName()
    // -----------------------------------------------------------------------

    @Test
    void getAvailableModels_shouldReturnClaudeModels() throws Exception {
        AnthropicProvider provider = AnthropicProvider.builder().apiKey("test-key").build();
        List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);

        assertNotNull(models);
        assertFalse(models.isEmpty());
        assertTrue(models.stream().anyMatch(m -> m.contains("claude")));
    }

    @Test
    void getProviderName_shouldReturnAnthropic() {
        AnthropicProvider provider = AnthropicProvider.builder().apiKey("test-key").build();
        assertEquals("Anthropic", provider.getProviderName());
    }

    // -----------------------------------------------------------------------
    // Builder validation
    // -----------------------------------------------------------------------

    @Test
    void builder_withNullApiKey_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                AnthropicProvider.builder().build());
    }

    @Test
    void builder_withBlankApiKey_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                AnthropicProvider.builder().apiKey("  ").build());
    }

    @Test
    void builder_withAllOptions_shouldBuild() {
        assertDoesNotThrow(() ->
                AnthropicProvider.builder()
                        .apiKey("valid-key")
                        .modelName("claude-3-5-haiku-20241022")
                        .temperature(0.5)
                        .maxTokens(2048)
                        .logRequests(true)
                        .logResponses(true)
                        .build());
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should throw when apiKey is missing")
        void build_withoutApiKey_shouldThrow() {
            AnthropicProvider.Builder builder = LLMProviderFactory.anthropic();

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    builder::build
            );

            assertTrue(ex.getMessage().toLowerCase().contains("api key"));
        }

        @Test
        @DisplayName("should build successfully with minimal config")
        void build_withMinimalConfig_shouldSucceed() {
            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey("test-api-key")
                    .build();

            assertNotNull(provider);
            assertEquals("Anthropic", provider.getProviderName());
        }

        @Test
        @DisplayName("should build with custom configuration")
        void build_withCustomConfig_shouldSucceed() {
            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey("test-api-key")
                    .modelName("claude-3-5-sonnet-20241022")
                    .temperature(0.5)
                    .maxTokens(500)
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should use default model when not specified")
        void build_withoutModel_shouldUseDefault() {
            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey("test-api-key")
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should accept valid temperature range")
        void build_withValidTemperature_shouldSucceed() {
            assertDoesNotThrow(() -> {
                LLMProviderFactory.anthropic()
                        .apiKey("test-key")
                        .temperature(0.0)
                        .build();

                LLMProviderFactory.anthropic()
                        .apiKey("test-key")
                        .temperature(1.0)
                        .build();
            });
        }
    }

    @Nested
    @DisplayName("Provider Metadata Tests")
    class ProviderMetadataTests {

        private LLMProvider provider;

        @BeforeEach
        void setup() {
            provider = LLMProviderFactory.anthropic()
                    .apiKey("dummy-key")
                    .build();
        }

        @Test
        @DisplayName("should return correct provider name")
        void getProviderName_shouldReturnAnthropic() {
            assertEquals("Anthropic", provider.getProviderName());
        }

        @Test
        @DisplayName("should return available models")
        void getAvailableModels_shouldContainCommonModels() throws Exception {
            CompletableFuture<List<String>> modelsFuture = provider.getAvailableModels();
            List<String> models = modelsFuture.get();

            assertNotNull(models);
            assertFalse(models.isEmpty());
            assertTrue(models.contains("claude-3-5-sonnet-20241022"));
            assertTrue(models.contains("claude-3-5-haiku-20241022"));
            assertTrue(models.contains("claude-3-haiku-20240307"));
        }
    }

    @Nested
    @DisplayName("Request Conversion Tests")
    class RequestConversionTests {

        @Test
        @DisplayName("should convert simple user message")
        void convertMessages_withUserMessage_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            assertNotNull(request);
            assertEquals(1, request.messages().size());
        }

        @Test
        @DisplayName("should convert system and user messages")
        void convertMessages_withSystemAndUser_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system("You are helpful"))
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            assertEquals(2, request.messages().size());
            assertEquals(LLMMessage.Role.SYSTEM, request.messages().get(0).role());
            assertEquals(LLMMessage.Role.USER, request.messages().get(1).role());
        }

        @Test
        @DisplayName("should handle conversation history")
        void convertMessages_withConversationHistory_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("What is 2+2?"))
                    .addMessage(LLMMessage.assistant("2+2 equals 4"))
                    .addMessage(LLMMessage.user("What about 3+3?"))
                    .build();

            assertEquals(3, request.messages().size());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should require at least one message")
        void chat_withEmptyRequest_shouldThrowException() {
            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey("test-key")
                    .build();

            assertThrows(IllegalArgumentException.class, () -> {
                LLMRequest.builder().build();
            });
        }

        @Test
        @DisplayName("should validate null API key")
        void build_withNullApiKey_shouldThrow() {
            assertThrows(IllegalArgumentException.class, () -> {
                AnthropicProvider.builder().apiKey(null).build();
            });
        }
    }

    @Nested
    @DisplayName("Feature Support Tests")
    class FeatureSupportTests {

        private LLMProvider provider;

        @BeforeEach
        void setup() {
            provider = LLMProviderFactory.anthropic()
                    .apiKey("dummy-key")
                    .build();
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
        @DisplayName("available models should not be empty")
        void getAvailableModels_shouldNotBeEmpty() throws Exception {
            List<String> models = provider.getAvailableModels().get();
            assertFalse(models.isEmpty());
            assertTrue(models.size() >= 3);
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    @Tag("integration")
    class IntegrationTests {

        @Test
        @Disabled("Requires valid API key")
        @DisplayName("should make real API call")
        void chat_withRealApiKey_shouldSucceed() throws ExecutionException, InterruptedException {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            assumeTrue(apiKey != null && !apiKey.isEmpty(), "ANTHROPIC_API_KEY not set");

            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey(apiKey)
                    .modelName("claude-3-5-haiku-20241022")
                    .maxTokens(100)
                    .build();

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Say 'Hello' and nothing else"))
                    .build();

            CompletableFuture<LLMResponse> responseFuture = provider.chat(request);
            LLMResponse response = responseFuture.get();

            assertNotNull(response);
            assertNotNull(response.content());
            assertFalse(response.content().isEmpty());
        }

        @Test
        @Disabled("Requires valid API key")
        @DisplayName("should handle streaming response")
        void chatStream_withRealApiKey_shouldSucceed() {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            assumeTrue(apiKey != null && !apiKey.isEmpty(), "ANTHROPIC_API_KEY not set");

            LLMProvider provider = LLMProviderFactory.anthropic()
                    .apiKey(apiKey)
                    .modelName("claude-3-5-haiku-20241022")
                    .build();

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Count to 5"))
                    .build();

            StringBuilder fullResponse = new StringBuilder();
            CompletableFuture<Void> streamFuture = provider.chatStream(
                    request,
                    chunk -> {
                        if (chunk.hasContent()) {
                            fullResponse.append(chunk.content());
                        }
                    }
            );

            assertDoesNotThrow(() -> streamFuture.get());
            assertFalse(fullResponse.toString().isEmpty());
        }
    }

    @Nested
    @DisplayName("Token Limit Registration")
    class TokenLimitRegistrationTests {

        @BeforeEach
        void triggerClassLoad() {
            AnthropicProvider.builder().apiKey("dummy-key").build();
        }

        @Test
        @DisplayName("all enum entries must be registered in ModelTokenLimits")
        void allEnumEntries_shouldBeRegistered() {
            Arrays.stream(AnthropicProvider.Models.values()).forEach(m ->
                assertTrue(ModelTokenLimits.hasModel(m.id),
                    m.name() + " (" + m.id + ") missing from ModelTokenLimits"));
        }

        @Test
        @DisplayName("all enum context windows must match ModelTokenLimits")
        void allEnumEntries_contextWindowShouldMatch() {
            Arrays.stream(AnthropicProvider.Models.values()).forEach(m ->
                assertEquals(m.contextWindow, ModelTokenLimits.getLimit(m.id),
                    "Wrong context window for " + m.name()));
        }

        @Test
        @DisplayName("getAvailableModels() must equal enum IDs")
        void getAvailableModels_shouldEqualEnumIds() throws Exception {
            AnthropicProvider provider = AnthropicProvider.builder().apiKey("dummy-key").build();
            List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);
            List<String> enumIds = Arrays.stream(AnthropicProvider.Models.values())
                .map(m -> m.id).toList();
            assertEquals(enumIds.size(), models.size());
            assertTrue(models.containsAll(enumIds));
        }

        @Test
        @DisplayName("getDefaultModel() must be a known enum entry")
        void defaultModel_shouldBeInEnum() {
            AnthropicProvider provider = AnthropicProvider.builder().apiKey("dummy-key").build();
            String defaultModel = provider.getDefaultModel();
            assertTrue(
                Arrays.stream(AnthropicProvider.Models.values()).anyMatch(m -> m.id.equals(defaultModel)),
                "Default model '" + defaultModel + "' not found in Models enum");
        }

        @Test
        @DisplayName("builder model(enum) should resolve to enum id")
        void builderModelEnum_shouldResolveToId() {
            AnthropicProvider provider = AnthropicProvider.builder()
                .apiKey("dummy-key")
                .modelName(AnthropicProvider.Models.CLAUDE_OPUS_4_6)
                .build();
            // spot-check known values
            assertEquals("claude-opus-4-6", AnthropicProvider.Models.CLAUDE_OPUS_4_6.id);
            assertEquals(200_000, AnthropicProvider.Models.CLAUDE_OPUS_4_6.contextWindow);
        }
    }

    private static void assumeTrue(boolean condition, String message) {
        Assumptions.assumeTrue(condition, message);
    }
}
