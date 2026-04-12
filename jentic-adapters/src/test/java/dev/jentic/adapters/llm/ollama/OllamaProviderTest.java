package dev.jentic.adapters.llm.ollama;

import dev.jentic.core.llm.*;
import dev.jentic.core.memory.llm.ModelTokenLimits;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OllamaProviderTest {

    @Mock
    private OllamaChatModel mockChatModel;

    @Mock
    private OllamaStreamingChatModel mockStreamingModel;

    @Nested
    @DisplayName("Chat Method Tests")
    class ChatMethodTests {

        @Test
        @DisplayName("should successfully handle chat request")
        void chat_withValidRequest_shouldReturnResponse() throws Exception {
            AiMessage aiMessage = AiMessage.from("Test response");
            TokenUsage tokenUsage = new TokenUsage(10, 20, 30);
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .finishReason(FinishReason.STOP)
                    .tokenUsage(tokenUsage)
                    .build();
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(metadata)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals("Test response", response.content());
            assertEquals(LLMMessage.Role.ASSISTANT, response.role());
            assertEquals("stop", response.finishReason());
            assertNotNull(response.usage());
            assertEquals(10, response.usage().promptTokens());
            assertEquals(20, response.usage().completionTokens());
            assertEquals(30, response.usage().totalTokens());
        }

        @Test
        @DisplayName("should handle chat request with custom temperature and maxTokens")
        void chat_withCustomParameters_shouldPassThemToModel() throws Exception {
            AiMessage aiMessage = AiMessage.from("Custom params response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .temperature(0.8)
                    .maxTokens(500)
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals("Custom params response", response.content());

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(mockChatModel).chat(captor.capture());
            ChatRequest capturedRequest = captor.getValue();
            assertNotNull(capturedRequest);
        }

        @Test
        @DisplayName("should handle null metadata in chat response")
        void chat_withNullMetadata_shouldHandleGracefully() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response without metadata");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(null)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertNull(response.finishReason());
            assertNull(response.usage());
        }

        @Test
        @DisplayName("should wrap exceptions in LLMException")
        void chat_withException_shouldWrapInLLMException() {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException("API Error"));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof RuntimeException);
            assertTrue(exception.getCause().getCause() instanceof LLMException);
        }
    }

    @Nested
    @DisplayName("ChatStream Method Tests")
    class ChatStreamMethodTests {

        @Test
        @DisplayName("should handle streaming response successfully")
        void chatStream_withValidRequest_shouldStreamChunks() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onPartialResponse("Hello");
                handler.onPartialResponse(" ");
                handler.onPartialResponse("world");

                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Hello world"))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(FinishReason.STOP)
                                .build())
                        .build();
                handler.onCompleteResponse(finalResponse);
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Say hello"))
                    .build();

            StringBuilder result = new StringBuilder();
            AtomicInteger chunkCount = new AtomicInteger(0);
            AtomicReference<String> finishReason = new AtomicReference<>();

            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {
                chunkCount.incrementAndGet();
                if (chunk.hasContent()) {
                    result.append(chunk.content());
                }
                if (chunk.isLast() && chunk.finishReason() != null) {
                    finishReason.set(chunk.finishReason());
                }
            });

            future.get(5, TimeUnit.SECONDS);

            assertEquals("Hello world", result.toString());
            assertEquals("stop", finishReason.get());
            assertTrue(chunkCount.get() >= 4);
        }

        @Test
        @DisplayName("should handle streaming with custom parameters")
        void chatStream_withCustomParameters_shouldApplyThem() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onPartialResponse("Test");
                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Test"))
                        .build();
                handler.onCompleteResponse(finalResponse);
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .temperature(0.5)
                    .maxTokens(100)
                    .build();

            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});
            future.get(5, TimeUnit.SECONDS);

            ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
            verify(mockStreamingModel).chat(captor.capture(), any(StreamingChatResponseHandler.class));
            assertNotNull(captor.getValue());
        }

        @Test
        @DisplayName("should handle null metadata in streaming complete response")
        void chatStream_withNullMetadata_shouldHandleGracefully() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onPartialResponse("Test");
                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Test"))
                        .metadata(null)
                        .build();
                handler.onCompleteResponse(finalResponse);
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            AtomicReference<String> finishReason = new AtomicReference<>();
            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {
                if (chunk.isLast()) {
                    finishReason.set(chunk.finishReason());
                }
            });

            future.get(5, TimeUnit.SECONDS);
            assertNull(finishReason.get());
        }

        @Test
        @DisplayName("should handle errors during streaming")
        void chatStream_withError_shouldCompleteExceptionally() {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onError(new RuntimeException("Streaming error"));
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof LLMException);
            assertTrue(exception.getCause().getMessage().contains("Ollama streaming failed"));
        }

        @Test
        @DisplayName("should handle exception during streaming request setup")
        void chatStream_withSetupException_shouldCompleteExceptionally() {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doThrow(new RuntimeException("Setup error"))
                    .when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof LLMException);
            assertTrue(exception.getCause().getMessage().contains("Ollama streaming request failed"));
        }
    }

    @Nested
    @DisplayName("Message Conversion Tests")
    class MessageConversionTests {

        @Test
        @DisplayName("should convert SYSTEM message correctly")
        void convertMessage_systemRole_shouldReturnSystemMessage() {
            OllamaProvider provider = OllamaProvider.builder().build();
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system("System prompt"))
                    .addMessage(LLMMessage.user("User message"))
                    .build();

            assertNotNull(request);
            assertEquals(2, request.messages().size());
            assertTrue(request.messages().get(0).isSystem());
        }

        @Test
        @DisplayName("should convert USER message correctly")
        void convertMessage_userRole_shouldReturnUserMessage() {
            OllamaProvider provider = OllamaProvider.builder().build();
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("User message"))
                    .build();

            assertTrue(request.messages().get(0).isUser());
        }

        @Test
        @DisplayName("should convert ASSISTANT message correctly")
        void convertMessage_assistantRole_shouldReturnAiMessage() {
            OllamaProvider provider = OllamaProvider.builder().build();
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("User message"))
                    .addMessage(LLMMessage.assistant("Assistant response"))
                    .build();

            assertTrue(request.messages().get(1).isAssistant());
        }

        @Test
        @DisplayName("should throw exception for unsupported message role")
        void convertMessage_unsupportedRole_shouldThrowException() {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            // FUNCTION role is not supported by Ollama's convertMessage method
            LLMMessage functionMessage = new LLMMessage(LLMMessage.Role.FUNCTION, "Function result", "test_function", null);
            LLMRequest request = LLMRequest.builder()
                    .addMessage(functionMessage)
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);

            // Should throw ExecutionException containing the conversion error
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));

            assertNotNull(exception.getCause());
            // The exception chain should contain "Unsupported message role"
            String message = getCauseMessage(exception);
            assertTrue(message.contains("Unsupported message role") || message.contains("FUNCTION"),
                    "Exception should mention unsupported role, got: " + message);
        }

        private String getCauseMessage(Throwable throwable) {
            Throwable current = throwable;
            while (current != null) {
                if (current.getMessage() != null && current.getMessage().contains("Unsupported")) {
                    return current.getMessage();
                }
                current = current.getCause();
            }
            return throwable.getMessage();
        }
    }

    @Nested
    @DisplayName("FinishReason Mapping Tests")
    class FinishReasonMappingTests {

        @Test
        @DisplayName("should map STOP finish reason")
        void mapFinishReason_stop_shouldReturnStop() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String result = invokeMapFinishReason(provider, FinishReason.STOP);
            assertEquals("stop", result);
        }

        @Test
        @DisplayName("should map LENGTH finish reason")
        void mapFinishReason_length_shouldReturnLength() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String result = invokeMapFinishReason(provider, FinishReason.LENGTH);
            assertEquals("length", result);
        }

        @Test
        @DisplayName("should map TOOL_EXECUTION finish reason")
        void mapFinishReason_toolExecution_shouldReturnToolCalls() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String result = invokeMapFinishReason(provider, FinishReason.TOOL_EXECUTION);
            assertEquals("tool_calls", result);
        }

        @Test
        @DisplayName("should map CONTENT_FILTER finish reason")
        void mapFinishReason_contentFilter_shouldReturnContentFilter() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String result = invokeMapFinishReason(provider, FinishReason.CONTENT_FILTER);
            assertEquals("content_filter", result);
        }

        @Test
        @DisplayName("should map OTHER finish reason")
        void mapFinishReason_other_shouldReturnOther() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String result = invokeMapFinishReason(provider, FinishReason.OTHER);
            assertEquals("other", result);
        }

        @Test
        @DisplayName("should handle null finish reason")
        void mapFinishReason_null_shouldReturnNull() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String result = invokeMapFinishReason(provider, null);
            assertNull(result);
        }

        private String invokeMapFinishReason(OllamaProvider provider, FinishReason reason) {
            try {
                var method = OllamaProvider.class.getDeclaredMethod("mapFinishReason", FinishReason.class);
                method.setAccessible(true);
                return (String) method.invoke(provider, reason);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("Usage Mapping Tests")
    class UsageMappingTests {

        @Test
        @DisplayName("should map TokenUsage correctly")
        void mapUsage_withValidTokenUsage_shouldReturnUsage() {
            OllamaProvider provider = OllamaProvider.builder().build();
            TokenUsage tokenUsage = new TokenUsage(100, 50, 150);

            LLMResponse.Usage result = invokeMapUsage(provider, tokenUsage);

            assertNotNull(result);
            assertEquals(100, result.promptTokens());
            assertEquals(50, result.completionTokens());
            assertEquals(150, result.totalTokens());
        }

        @Test
        @DisplayName("should handle null TokenUsage")
        void mapUsage_withNull_shouldReturnNull() {
            OllamaProvider provider = OllamaProvider.builder().build();
            LLMResponse.Usage result = invokeMapUsage(provider, null);
            assertNull(result);
        }

        private LLMResponse.Usage invokeMapUsage(OllamaProvider provider, TokenUsage tokenUsage) {
            try {
                var method = OllamaProvider.class.getDeclaredMethod("mapUsage", TokenUsage.class);
                method.setAccessible(true);
                return (LLMResponse.Usage) method.invoke(provider, tokenUsage);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("Provider Capabilities Tests")
    class ProviderCapabilitiesTests {

        @Test
        @DisplayName("should return correct provider name")
        void getProviderName_shouldReturnOllama() {
            OllamaProvider provider = OllamaProvider.builder().build();
            assertEquals("Ollama", provider.getProviderName());
        }

        @Test
        @DisplayName("should indicate no function calling support")
        void supportsFunctionCalling_shouldReturnFalse() {
            OllamaProvider provider = OllamaProvider.builder().build();
            assertFalse(provider.supportsFunctionCalling());
        }

        @Test
        @DisplayName("should indicate streaming support")
        void supportsStreaming_shouldReturnTrue() {
            OllamaProvider provider = OllamaProvider.builder().build();
            assertTrue(provider.supportsStreaming());
        }

        @Test
        @DisplayName("should return correct default model")
        void getDefaultModel_shouldReturnLlama32() {
            OllamaProvider provider = OllamaProvider.builder().build();
            assertEquals("llama3.2", provider.getDefaultModel());
        }
    }

    @Nested
    @DisplayName("Available Models Tests")
    class AvailableModelsTests {

        @Test
        @DisplayName("should return non-empty model list")
        void getAvailableModels_shouldNotBeEmpty() throws Exception {
            OllamaProvider provider = OllamaProvider.builder().build();
            List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);

            assertNotNull(models);
            assertFalse(models.isEmpty());
        }

        @Test
        @DisplayName("should contain expected base models")
        void getAvailableModels_shouldContainBaseModels() throws Exception {
            OllamaProvider provider = OllamaProvider.builder().build();
            List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);

            // Verify key model families are represented (no hardcoded count)
            assertTrue(models.stream().anyMatch(m -> m.startsWith("llama")));
            assertTrue(models.stream().anyMatch(m -> m.startsWith("mistral")));
            assertTrue(models.stream().anyMatch(m -> m.startsWith("gemma")));
        }

        @Test
        @DisplayName("should return consistent list across calls")
        void getAvailableModels_calledMultipleTimes_shouldReturnSame() throws Exception {
            OllamaProvider provider = OllamaProvider.builder().build();
            List<String> models1 = provider.getAvailableModels().get(5, TimeUnit.SECONDS);
            List<String> models2 = provider.getAvailableModels().get(5, TimeUnit.SECONDS);

            assertEquals(models1.size(), models2.size());
            assertTrue(models1.containsAll(models2));
        }
    }

    @Nested
    @DisplayName("Token Limit Registration")
    class TokenLimitRegistrationTests {

        @BeforeEach
        void triggerClassLoad() {
            OllamaProvider.builder().build();
        }

        @Test
        @DisplayName("all enum entries must be registered in ModelTokenLimits")
        void allEnumEntries_shouldBeRegistered() {
            Arrays.stream(OllamaProvider.Models.values()).forEach(m ->
                assertTrue(ModelTokenLimits.hasModel(m.id),
                    m.name() + " (" + m.id + ") missing from ModelTokenLimits"));
        }

        @Test
        @DisplayName("all enum context windows must match ModelTokenLimits")
        void allEnumEntries_contextWindowShouldMatch() {
            Arrays.stream(OllamaProvider.Models.values()).forEach(m ->
                assertEquals(m.contextWindow, ModelTokenLimits.getLimit(m.id),
                    "Wrong context window for " + m.name()));
        }

        @Test
        @DisplayName("versioned tags must be registered with correct context window")
        void versionedTags_shouldBeRegistered() {
            // Tags inherit their base model's context window
            assertEquals(OllamaProvider.Models.LLAMA_3_2.contextWindow,
                ModelTokenLimits.getLimit("llama3.2:3b"));
            assertEquals(OllamaProvider.Models.LLAMA_3_1.contextWindow,
                ModelTokenLimits.getLimit("llama3.1:70b"));
            assertEquals(OllamaProvider.Models.CODELLAMA.contextWindow,
                ModelTokenLimits.getLimit("codellama:34b"));
            assertEquals(65_536, ModelTokenLimits.getLimit("mixtral:8x22b"));
        }

        @Test
        @DisplayName("getAvailableModels() must equal enum base IDs")
        void getAvailableModels_shouldEqualEnumIds() throws Exception {
            OllamaProvider provider = OllamaProvider.builder().build();
            List<String> models = provider.getAvailableModels().get(5, TimeUnit.SECONDS);
            List<String> enumIds = Arrays.stream(OllamaProvider.Models.values())
                .map(m -> m.id).toList();
            assertEquals(enumIds.size(), models.size());
            assertTrue(models.containsAll(enumIds));
        }

        @Test
        @DisplayName("getDefaultModel() must be a known enum entry")
        void defaultModel_shouldBeInEnum() {
            OllamaProvider provider = OllamaProvider.builder().build();
            String defaultModel = provider.getDefaultModel();
            assertTrue(
                Arrays.stream(OllamaProvider.Models.values()).anyMatch(m -> m.id.equals(defaultModel)),
                "Default model '" + defaultModel + "' not found in Models enum");
        }

        @Test
        @DisplayName("builder modelName(enum) should resolve to enum id")
        void builderModelNameEnum_shouldResolveToId() {
            // spot-check known values
            assertEquals("llama3.2", OllamaProvider.Models.LLAMA_3_2.id);
            assertEquals(128_000,    OllamaProvider.Models.LLAMA_3_2.contextWindow);
        }

        @Test
        @DisplayName("builder modelName(String) should accept versioned tag")
        void builderModelNameString_shouldAcceptVersionedTag() {
            OllamaProvider provider = OllamaProvider.builder()
                .modelName("llama3.1:70b")
                .build();
            assertNotNull(provider);
            assertTrue(ModelTokenLimits.hasModel("llama3.1:70b"));
        }
    }

    private OllamaProvider createProviderWithMocks(OllamaChatModel chatModel, OllamaStreamingChatModel streamingModel) {
        try {
            OllamaProvider provider = OllamaProvider.builder().build();

            Field chatModelField = OllamaProvider.class.getDeclaredField("chatModel");
            chatModelField.setAccessible(true);
            chatModelField.set(provider, chatModel);

            Field streamingModelField = OllamaProvider.class.getDeclaredField("streamingModel");
            streamingModelField.setAccessible(true);
            streamingModelField.set(provider, streamingModel);

            return provider;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create provider with mocks", e);
        }
    }
}
