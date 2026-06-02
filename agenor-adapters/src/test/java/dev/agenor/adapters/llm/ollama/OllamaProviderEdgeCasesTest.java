package dev.agenor.adapters.llm.ollama;

import dev.agenor.core.llm.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OllamaProviderEdgeCasesTest {

    @Mock
    private OllamaChatModel mockChatModel;

    @Mock
    private OllamaStreamingChatModel mockStreamingModel;

    @Nested
    @DisplayName("Edge Cases - Request Building")
    class RequestBuildingEdgeCases {

        @Test
        @DisplayName("should handle request with only temperature set")
        void chat_withOnlyTemperature_shouldWork() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .temperature(0.5)
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals("Response", response.content());
        }

        @Test
        @DisplayName("should handle request with only maxTokens set")
        void chat_withOnlyMaxTokens_shouldWork() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .maxTokens(200)
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
        }

        @Test
        @DisplayName("should handle request with neither temperature nor maxTokens")
        void chat_withoutOptionalParams_shouldWork() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
        }
    }

    @Nested
    @DisplayName("Edge Cases - Streaming")
    class StreamingEdgeCases {

        @Test
        @DisplayName("should handle streaming with no partial responses")
        void chatStream_withNoPartialResponses_shouldStillComplete() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Complete"))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(FinishReason.STOP)
                                .build())
                        .build();
                handler.onCompleteResponse(finalResponse);
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            boolean[] completed = {false};
            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {
                if (chunk.isLast()) {
                    completed[0] = true;
                }
            });

            future.get(5, TimeUnit.SECONDS);
            assertTrue(completed[0]);
        }

        @Test
        @DisplayName("should handle streaming with null finishReason in metadata")
        void chatStream_withNullFinishReason_shouldHandleGracefully() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onPartialResponse("Test");
                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Test"))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(null)
                                .build())
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
        @DisplayName("should handle streaming with only temperature parameter")
        void chatStream_withOnlyTemperature_shouldWork() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onPartialResponse("Response");
                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Response"))
                        .build();
                handler.onCompleteResponse(finalResponse);
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .temperature(0.7)
                    .build();

            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});
            future.get(5, TimeUnit.SECONDS);

            verify(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }

        @Test
        @DisplayName("should handle streaming with only maxTokens parameter")
        void chatStream_withOnlyMaxTokens_shouldWork() throws Exception {
            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);

            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handler.onPartialResponse("Response");
                ChatResponse finalResponse = ChatResponse.builder()
                        .aiMessage(AiMessage.from("Response"))
                        .build();
                handler.onCompleteResponse(finalResponse);
                return null;
            }).when(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .maxTokens(150)
                    .build();

            CompletableFuture<Void> future = provider.chatStream(request, chunk -> {});
            future.get(5, TimeUnit.SECONDS);

            verify(mockStreamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }
    }

    @Nested
    @DisplayName("Edge Cases - Response Handling")
    class ResponseHandlingEdgeCases {

        @Test
        @DisplayName("explicit request model takes priority over provider's configured model")
        void chat_withExplicitRequestModel_requestModelTakesPriority() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            // Provider configured with "mistral", request explicitly overrides with "llama3.2"
            OllamaProvider provider = createProviderWithModelName("mistral");

            LLMRequest request = LLMRequest.builder()
                    .model("llama3.2")
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            // request.model() takes priority per ADR-017
            assertEquals("llama3.2", response.model());
        }

        @Test
        @DisplayName("provider's configured model is used when request has no model")
        void chat_withNoRequestModel_usesProviderDefault() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            // Provider configured with "mistral"; request has no model set
            OllamaProvider provider = createProviderWithModelName("mistral");

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            // Falls back to provider's configured model (getDefaultModel() = "mistral")
            assertEquals("mistral", response.model());
        }

        @Test
        @DisplayName("should handle metadata with null tokenUsage")
        void chat_withNullTokenUsage_shouldReturnNullUsage() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .finishReason(FinishReason.STOP)
                    .tokenUsage(null)
                    .build();
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(metadata)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNull(response.usage());
            assertEquals("stop", response.finishReason());
        }
    }

    @Nested
    @DisplayName("Edge Cases - Message Conversion")
    class MessageConversionEdgeCases {

        @Test
        @DisplayName("should handle multiple message types in single request")
        void chat_withMultipleMessageTypes_shouldConvertAll() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system("You are helpful"))
                    .addMessage(LLMMessage.user("Hello"))
                    .addMessage(LLMMessage.assistant("Hi there"))
                    .addMessage(LLMMessage.user("How are you?"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            verify(mockChatModel).chat(any(ChatRequest.class));
        }

        @Test
        @DisplayName("should handle single system message")
        void chat_withOnlySystemMessage_shouldWork() throws Exception {
            AiMessage aiMessage = AiMessage.from("Response");
            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .build();

            OllamaProvider provider = createProviderWithMocks(mockChatModel, mockStreamingModel);
            when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system("System only"))
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
        }
    }

    @Nested
    @DisplayName("Edge Cases - Builder Configuration")
    class BuilderConfigurationEdgeCases {

        @Test
        @DisplayName("should handle all builder options together")
        void builder_withAllOptions_shouldCreateProvider() {
            OllamaProvider provider = OllamaProvider.builder()
                    .baseUrl("http://custom:11434")
                    .modelName("mistral")
                    .temperature(0.9)
                    .timeout(Duration.ofSeconds(120))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            assertNotNull(provider);
            assertEquals("Ollama", provider.getProviderName());
            assertEquals("llama3.2", provider.getDefaultModel());
        }

        @Test
        @DisplayName("should handle extreme timeout values")
        void builder_withExtremeTimeout_shouldWork() {
            assertDoesNotThrow(() -> {
                OllamaProvider.builder()
                        .timeout(Duration.ofMillis(1))
                        .build();

                OllamaProvider.builder()
                        .timeout(Duration.ofHours(1))
                        .build();
            });
        }

        @Test
        @DisplayName("should handle edge temperature values")
        void builder_withEdgeTemperature_shouldWork() {
            assertDoesNotThrow(() -> {
                OllamaProvider.builder()
                        .temperature(0.0)
                        .build();

                OllamaProvider.builder()
                        .temperature(2.0)
                        .build();
            });
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

    private OllamaProvider createProviderWithModelName(String modelName) {
        try {
            OllamaProvider provider = OllamaProvider.builder()
                    .modelName(modelName)
                    .build();

            Field chatModelField = OllamaProvider.class.getDeclaredField("chatModel");
            chatModelField.setAccessible(true);
            chatModelField.set(provider, mockChatModel);

            Field streamingModelField = OllamaProvider.class.getDeclaredField("streamingModel");
            streamingModelField.setAccessible(true);
            streamingModelField.set(provider, mockStreamingModel);

            return provider;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create provider with model name", e);
        }
    }
}
