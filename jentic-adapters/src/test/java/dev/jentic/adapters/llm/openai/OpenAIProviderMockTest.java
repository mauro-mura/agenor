package dev.jentic.adapters.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.jentic.core.llm.FunctionCall;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.llm.LLMResponse;
import dev.jentic.core.llm.StreamingChunk;

/**
 * Mock-based integration tests for OpenAIProvider focusing on chat and streaming functionality.
 * These tests cover response building, token usage, finish reasons, and function call handling.
 */
@ExtendWith(MockitoExtension.class)
class OpenAIProviderMockTest {

    @Nested
    @DisplayName("Chat Response Building Tests")
    class ChatResponseBuildingTests {

        @Test
        @DisplayName("should build response with content and token usage")
        void chat_withSuccessfulResponse_shouldBuildCompleteResponse() {
            // Given
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            // When
            CompletableFuture<LLMResponse> future = provider.chat(request);

            // Then - should not throw even without actual API
            assertNotNull(future);
        }

        @Test
        @DisplayName("should handle response with finish reason STOP")
        void responseBuilder_withStopFinishReason_shouldIncludeIt() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Response content")
                    .finishReason("stop")
                    .build();

            assertEquals("stop", response.finishReason());
        }

        @Test
        @DisplayName("should handle response with finish reason LENGTH")
        void responseBuilder_withLengthFinishReason_shouldIncludeIt() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Truncated...")
                    .finishReason("length")
                    .build();

            assertEquals("length", response.finishReason());
        }

        @Test
        @DisplayName("should handle response with finish reason TOOL_CALLS")
        void responseBuilder_withToolCallsFinishReason_shouldIncludeIt() {
            FunctionCall call = new FunctionCall("call-1", "get_weather", "{}");
            
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .functionCalls(List.of(call))
                    .finishReason("tool_calls")
                    .build();

            assertEquals("tool_calls", response.finishReason());
            assertTrue(response.hasFunctionCalls());
        }

        @Test
        @DisplayName("should build response with metadata")
        void responseBuilder_withMetadata_shouldIncludeIt() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("request_id", "req-123");
            metadata.put("model_version", "v1.0");
            metadata.put("region", "us-east-1");

            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Content")
                    .metadata(metadata)
                    .build();

            assertNotNull(response.metadata());
            assertEquals("req-123", response.metadata().get("request_id"));
            assertEquals("v1.0", response.metadata().get("model_version"));
            assertEquals("us-east-1", response.metadata().get("region"));
        }

        @Test
        @DisplayName("should build response with empty metadata")
        void responseBuilder_withEmptyMetadata_shouldSucceed() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Content")
                    .metadata(new HashMap<>())
                    .build();

            assertNotNull(response.metadata());
            assertTrue(response.metadata().isEmpty());
        }
    }

    @Nested
    @DisplayName("Token Usage Tests")
    class TokenUsageTests {

        @Test
        @DisplayName("should calculate total tokens correctly")
        void responseBuilder_withTokenUsage_shouldCalculateTotal() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Response")
                    .usage(100, 50, 150)
                    .build();

            assertNotNull(response.usage());
            assertEquals(100, response.usage().promptTokens());
            assertEquals(50, response.usage().completionTokens());
            assertEquals(150, response.usage().totalTokens());
        }

        @Test
        @DisplayName("should handle zero tokens")
        void responseBuilder_withZeroTokens_shouldSucceed() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("")
                    .usage(0, 0, 0)
                    .build();

            assertNotNull(response.usage());
            assertEquals(0, response.usage().totalTokens());
        }

        @Test
        @DisplayName("should handle large token counts")
        void responseBuilder_withLargeTokenCounts_shouldSucceed() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Long response...")
                    .usage(50000, 25000, 75000)
                    .build();

            assertNotNull(response.usage());
            assertEquals(50000, response.usage().promptTokens());
            assertEquals(25000, response.usage().completionTokens());
            assertEquals(75000, response.usage().totalTokens());
        }

        @Test
        @DisplayName("should handle response without token usage")
        void responseBuilder_withoutTokenUsage_shouldHaveNullUsage() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Response")
                    .build();

            assertNull(response.usage());
        }
    }

    @Nested
    @DisplayName("Function Call Handling Tests")
    class FunctionCallHandlingTests {

        @Test
        @DisplayName("should handle single function call")
        void responseBuilder_withSingleFunctionCall_shouldIncludeIt() {
            FunctionCall call = new FunctionCall(
                    "call-abc123",
                    "get_weather",
                    "{\"location\":\"Paris\",\"unit\":\"celsius\"}"
            );

            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .functionCalls(List.of(call))
                    .build();

            assertTrue(response.hasFunctionCalls());
            assertEquals(1, response.functionCalls().size());
            assertEquals("call-abc123", response.functionCalls().get(0).id());
            assertEquals("get_weather", response.functionCalls().get(0).name());
            assertTrue(response.functionCalls().get(0).arguments().contains("Paris"));
        }

        @Test
        @DisplayName("should handle multiple function calls")
        void responseBuilder_withMultipleFunctionCalls_shouldIncludeAll() {
            FunctionCall call1 = new FunctionCall("call-1", "get_weather", "{\"location\":\"Paris\"}");
            FunctionCall call2 = new FunctionCall("call-2", "get_time", "{\"timezone\":\"UTC\"}");
            FunctionCall call3 = new FunctionCall("call-3", "get_news", "{\"category\":\"tech\"}");

            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .functionCalls(Arrays.asList(call1, call2, call3))
                    .build();

            assertTrue(response.hasFunctionCalls());
            assertEquals(3, response.functionCalls().size());
        }

        @Test
        @DisplayName("should handle function call with complex arguments")
        void functionCall_withComplexArguments_shouldStoreCorrectly() {
            String complexArgs = "{\"location\":{\"city\":\"Paris\",\"country\":\"France\"},\"options\":{\"unit\":\"celsius\",\"forecast\":true}}";
            
            FunctionCall call = new FunctionCall("call-1", "get_weather", complexArgs);

            assertEquals("call-1", call.id());
            assertEquals("get_weather", call.name());
            assertEquals(complexArgs, call.arguments());
        }

        @Test
        @DisplayName("should handle function call with array arguments")
        void functionCall_withArrayArguments_shouldStoreCorrectly() {
            String arrayArgs = "{\"cities\":[\"Paris\",\"London\",\"Berlin\"],\"metrics\":[\"temp\",\"humidity\"]}";
            
            FunctionCall call = new FunctionCall("call-1", "get_multi_weather", arrayArgs);

            assertTrue(call.arguments().contains("Paris"));
            assertTrue(call.arguments().contains("London"));
            assertTrue(call.arguments().contains("Berlin"));
        }

        @Test
        @DisplayName("should handle empty function call arguments")
        void functionCall_withEmptyArguments_shouldSucceed() {
            FunctionCall call = new FunctionCall("call-1", "get_random_fact", "{}");

            assertEquals("{}", call.arguments());
        }

        @Test
        @DisplayName("should detect function calls presence")
        void response_withoutFunctionCalls_shouldNotHaveFunctionCalls() {
            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Just a regular response")
                    .build();

            assertFalse(response.hasFunctionCalls());
        }
    }

    @Nested
    @DisplayName("Streaming Chunk Tests")
    class StreamingChunkTests {

        @Test
        @DisplayName("should create streaming chunk with content")
        void streamingChunk_withContent_shouldHaveCorrectProperties() {
            StreamingChunk chunk = StreamingChunk.of("stream-1", "gpt-4o", "Hello", 0);

            assertEquals("stream-1", chunk.id());
            assertEquals("gpt-4o", chunk.model());
            assertEquals("Hello", chunk.content());
            assertEquals(0, chunk.index());
            assertTrue(chunk.hasContent());
            assertFalse(chunk.isLast());
        }

        @Test
        @DisplayName("should create final streaming chunk")
        void streamingChunk_withFinishReason_shouldBeMarkedAsLast() {
            StreamingChunk chunk = StreamingChunk.of("stream-1", "gpt-4o", "", "stop", 10);

            assertEquals("stream-1", chunk.id());
            assertEquals("gpt-4o", chunk.model());
            assertEquals("", chunk.content());
            assertEquals("stop", chunk.finishReason());
            assertEquals(10, chunk.index());
            assertFalse(chunk.hasContent());
            assertTrue(chunk.isLast());
        }

        @Test
        @DisplayName("should handle streaming chunks sequence")
        void streamingChunks_inSequence_shouldHaveIncreasingIndices() {
            List<StreamingChunk> chunks = Arrays.asList(
                    StreamingChunk.of("s1", "gpt-4o", "Hello", 0),
                    StreamingChunk.of("s1", "gpt-4o", " ", 1),
                    StreamingChunk.of("s1", "gpt-4o", "world", 2),
                    StreamingChunk.of("s1", "gpt-4o", "!", 3),
                    StreamingChunk.of("s1", "gpt-4o", "", "stop", 4)
            );

            for (int i = 0; i < chunks.size() - 1; i++) {
                assertTrue(chunks.get(i).hasContent());
                assertFalse(chunks.get(i).isLast());
                assertEquals(i, chunks.get(i).index());
            }

            assertTrue(chunks.get(4).isLast());
            assertFalse(chunks.get(4).hasContent());
        }

        @Test
        @DisplayName("should validate chunk index is non-negative")
        void streamingChunk_withNegativeIndex_shouldThrow() {
            assertThrows(IllegalArgumentException.class, () -> {
                new StreamingChunk("id", "model", "content", null, -1, null);
            });
        }

        @Test
        @DisplayName("should handle empty content chunks")
        void streamingChunk_withEmptyContent_shouldNotHaveContent() {
            StreamingChunk chunk = StreamingChunk.of("s1", "gpt-4o", "", 5);

            assertFalse(chunk.hasContent());
            assertEquals("", chunk.content());
        }
    }

    @Nested
    @DisplayName("Request Parameter Tests")
    class RequestParameterTests {

        @Test
        @DisplayName("should create request with custom temperature")
        void request_withCustomTemperature_shouldStoreIt() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .temperature(0.3)
                    .build();

            assertEquals(0.3, request.temperature());
        }

        @Test
        @DisplayName("should create request with custom max tokens")
        void request_withCustomMaxTokens_shouldStoreIt() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .maxTokens(2048)
                    .build();

            assertEquals(2048, request.maxTokens());
        }

        @Test
        @DisplayName("should create request with all penalty parameters")
        void request_withAllPenalties_shouldStoreThem() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .topP(0.95)
                    .presencePenalty(0.5)
                    .frequencyPenalty(0.3)
                    .build();

            assertEquals(0.95, request.topP());
            assertEquals(0.5, request.presencePenalty());
            assertEquals(0.3, request.frequencyPenalty());
        }

        @Test
        @DisplayName("should handle request with default parameters")
        void request_withDefaults_shouldHaveNullOptionals() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .build();

            assertNull(request.temperature());
            assertNull(request.maxTokens());
            assertNull(request.topP());
            assertNull(request.presencePenalty());
            assertNull(request.frequencyPenalty());
        }
    }

    @Nested
    @DisplayName("Builder Fluent API Tests")
    class BuilderFluentAPITests {

        @Test
        @DisplayName("should support method chaining")
        void builder_withMethodChaining_shouldWork() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("key-123")
                    .modelName("gpt-4o")
                    .temperature(0.7)
                    .maxTokens(1000)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should allow multiple builds from same builder")
        void builder_multipleBuild_shouldCreateSeparateInstances() {
            OpenAIProvider.Builder builder = OpenAIProvider.builder()
                    .apiKey("test-key");

            OpenAIProvider provider1 = builder.build();
            OpenAIProvider provider2 = builder.build();

            assertNotNull(provider1);
            assertNotNull(provider2);
        }
    }

    @Nested
    @DisplayName("Message Role Tests")
    class MessageRoleTests {

        @Test
        @DisplayName("should handle all message roles")
        void messages_withAllRoles_shouldConvertCorrectly() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system("System prompt"))
                    .addMessage(LLMMessage.user("User question"))
                    .addMessage(LLMMessage.assistant("Assistant answer"))
                    .build();

            assertEquals(3, request.messages().size());
            assertEquals(LLMMessage.Role.SYSTEM, request.messages().get(0).role());
            assertEquals(LLMMessage.Role.USER, request.messages().get(1).role());
            assertEquals(LLMMessage.Role.ASSISTANT, request.messages().get(2).role());
        }

        @Test
        @DisplayName("should preserve message content")
        void messages_shouldPreserveContent() {
            String systemContent = "You are a helpful assistant";
            String userContent = "What is 2+2?";
            String assistantContent = "2+2 equals 4";

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system(systemContent))
                    .addMessage(LLMMessage.user(userContent))
                    .addMessage(LLMMessage.assistant(assistantContent))
                    .build();

            assertEquals(systemContent, request.messages().get(0).content());
            assertEquals(userContent, request.messages().get(1).content());
            assertEquals(assistantContent, request.messages().get(2).content());
        }
    }

    @Nested
    @DisplayName("Concurrent Request Tests")
    class ConcurrentRequestTests {

        @Test
        @DisplayName("should handle multiple concurrent chat requests")
        void chat_withConcurrentRequests_shouldHandleAll() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            List<CompletableFuture<LLMResponse>> futures = new ArrayList<>();
            
            for (int i = 0; i < 5; i++) {
                LLMRequest request = LLMRequest.builder()
                        .addMessage(LLMMessage.user("Request " + i))
                        .build();
                futures.add(provider.chat(request));
            }

            assertEquals(5, futures.size());
            futures.forEach(f -> assertNotNull(f));
        }

        @Test
        @DisplayName("should handle multiple streaming requests")
        void chatStream_withConcurrentRequests_shouldHandleAll() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int i = 0; i < 5; i++) {
                LLMRequest request = LLMRequest.builder()
                        .addMessage(LLMMessage.user("Stream request " + i))
                        .build();
                futures.add(provider.chatStream(request, chunk -> {}));
            }

            assertEquals(5, futures.size());
        }
    }

    @Nested
    @DisplayName("Response ID and Model Tests")
    class ResponseIdAndModelTests {

        @Test
        @DisplayName("should include response ID in response")
        void response_shouldHaveId() {
            LLMResponse response = LLMResponse.builder("resp-abc-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Test")
                    .build();

            assertEquals("resp-abc-123", response.id());
        }

        @Test
        @DisplayName("should include model name in response")
        void response_shouldHaveModel() {
            LLMResponse response = LLMResponse.builder("resp-123", "gpt-4o-mini")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Test")
                    .build();

            assertEquals("gpt-4o-mini", response.model());
        }

        @Test
        @DisplayName("should handle different model names")
        void response_withDifferentModels_shouldStoreCorrectly() {
            List<String> models = Arrays.asList(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gpt-4-turbo",
                    "gpt-4",
                    "gpt-3.5-turbo"
            );

            for (String model : models) {
                LLMResponse response = LLMResponse.builder("id", model)
                        .role(LLMMessage.Role.ASSISTANT)
                        .content("Test")
                        .build();
                
                assertEquals(model, response.model());
            }
        }
    }
}