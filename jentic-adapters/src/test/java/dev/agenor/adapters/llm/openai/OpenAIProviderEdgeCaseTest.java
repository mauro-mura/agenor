package dev.agenor.adapters.llm.openai;

import dev.agenor.core.llm.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional edge case and scenario tests to maximize code coverage.
 * Focuses on parameter combinations, boundary conditions, and conversion edge cases.
 */
class OpenAIProviderEdgeCaseTest {

    @Nested
    @DisplayName("Parameter Boundary Tests")
    class ParameterBoundaryTests {

        @Test
        @DisplayName("should handle minimum valid maxTokens")
        void request_withMinimalMaxTokens_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Short"))
                    .maxTokens(1)
                    .build();

            assertEquals(1, request.maxTokens());
        }

        @Test
        @DisplayName("should handle maximum maxTokens")
        void request_withMaximalMaxTokens_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Long response needed"))
                    .maxTokens(100000)
                    .build();

            assertEquals(100000, request.maxTokens());
        }

        @Test
        @DisplayName("should handle topP at boundaries")
        void request_withBoundaryTopP_shouldSucceed() {
            LLMRequest request1 = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .topP(0.0)
                    .build();

            LLMRequest request2 = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .topP(1.0)
                    .build();

            assertEquals(0.0, request1.topP());
            assertEquals(1.0, request2.topP());
        }

        @Test
        @DisplayName("should handle penalties at boundaries")
        void request_withBoundaryPenalties_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .presencePenalty(-2.0)
                    .frequencyPenalty(2.0)
                    .build();

            assertEquals(-2.0, request.presencePenalty());
            assertEquals(2.0, request.frequencyPenalty());
        }

        @Test
        @DisplayName("should handle zero penalties")
        void request_withZeroPenalties_shouldSucceed() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Test"))
                    .presencePenalty(0.0)
                    .frequencyPenalty(0.0)
                    .build();

            assertEquals(0.0, request.presencePenalty());
            assertEquals(0.0, request.frequencyPenalty());
        }
    }

    @Nested
    @DisplayName("Function Definition Edge Cases")
    class FunctionDefinitionEdgeCasesTests {

        @Test
        @DisplayName("should handle function with single required parameter")
        void function_withSingleRequiredParam_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("simple")
                    .description("Simple function")
                    .stringParameter("input", "Input value", true)
                    .build();

            assertEquals("simple", func.name());
            assertEquals("Simple function", func.description());
            assertNotNull(func.parameters());
        }

        @Test
        @DisplayName("should handle function with only optional parameters")
        void function_withOnlyOptionalParams_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("optional")
                    .description("Optional params")
                    .stringParameter("param1", "First", false)
                    .stringParameter("param2", "Second", false)
                    .build();

            assertEquals("optional", func.name());
        }

        @Test
        @DisplayName("should handle function with mixed parameter types")
        void function_withMixedTypes_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("mixed")
                    .description("Mixed types")
                    .stringParameter("name", "Name", true)
                    .parameter("age", "integer", "Age", true)
                    .booleanParameter("active", "Is active", false)
                    .parameter("score", "number", "Score", false)
                    .build();

            assertNotNull(func);
            assertEquals("mixed", func.name());
        }

        @Test
        @DisplayName("should handle function with enum having single value")
        void function_withSingleValueEnum_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("single_enum")
                    .description("Single enum value")
                    .enumParameter("choice", "The choice", true, "only_option")
                    .build();

            assertNotNull(func);
        }

        @Test
        @DisplayName("should handle function with many enum values")
        void function_withManyEnumValues_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("many_enum")
                    .description("Many enum values")
                    .enumParameter("day", "Day of week", true,
                            "monday", "tuesday", "wednesday", "thursday",
                            "friday", "saturday", "sunday")
                    .build();

            assertNotNull(func);
        }

        @Test
        @DisplayName("should handle function with very long description")
        void function_withLongDescription_shouldStoreCorrectly() {
            String longDesc = "This is a very long description that explains in great detail what this function does. ".repeat(10);

            FunctionDefinition func = FunctionDefinition.builder("long_desc")
                    .description(longDesc)
                    .build();

            assertEquals(longDesc, func.description());
        }

        @Test
        @DisplayName("should handle function name with underscores")
        void function_withUnderscoreName_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("get_user_profile_by_id")
                    .description("Get profile")
                    .build();

            assertEquals("get_user_profile_by_id", func.name());
        }

        @Test
        @DisplayName("should handle function name with numbers")
        void function_withNumbersInName_shouldBuildCorrectly() {
            FunctionDefinition func = FunctionDefinition.builder("api_v2_endpoint")
                    .description("API endpoint")
                    .build();

            assertEquals("api_v2_endpoint", func.name());
        }
    }

    @Nested
    @DisplayName("Message Content Edge Cases")
    class MessageContentEdgeCasesTests {

        @Test
        @DisplayName("should handle message with only whitespace")
        void message_withWhitespace_shouldPreserveIt() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("   \n\t  "))
                    .build();

            assertEquals("   \n\t  ", request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with newlines")
        void message_withNewlines_shouldPreserveThem() {
            String multiline = "Line 1\nLine 2\nLine 3";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(multiline))
                    .build();

            assertEquals(multiline, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with tabs")
        void message_withTabs_shouldPreserveThem() {
            String tabbed = "Column1\tColumn2\tColumn3";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(tabbed))
                    .build();

            assertEquals(tabbed, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with JSON content")
        void message_withJSON_shouldPreserveIt() {
            String json = "{\"key\":\"value\",\"number\":42,\"nested\":{\"inner\":true}}";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(json))
                    .build();

            assertEquals(json, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with XML content")
        void message_withXML_shouldPreserveIt() {
            String xml = "<root><item id=\"1\">Value</item></root>";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(xml))
                    .build();

            assertEquals(xml, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with code blocks")
        void message_withCodeBlocks_shouldPreserveThem() {
            String code = "```python\ndef hello():\n    print('Hello')\n```";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(code))
                    .build();

            assertEquals(code, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with emoji")
        void message_withEmoji_shouldPreserveThem() {
            String emoji = "Hello 👋 World 🌍";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(emoji))
                    .build();

            assertEquals(emoji, request.messages().get(0).content());
        }

        @Test
        @DisplayName("should handle message with math symbols")
        void message_withMathSymbols_shouldPreserveThem() {
            String math = "∑(x² + y²) = π × r² ≈ 3.14159";
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(math))
                    .build();

            assertEquals(math, request.messages().get(0).content());
        }
    }

    @Nested
    @DisplayName("Provider Configuration Edge Cases")
    class ProviderConfigurationEdgeCasesTests {

        @Test
        @DisplayName("should handle very short timeout")
        void provider_withVeryShortTimeout_shouldBuild() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .timeout(Duration.ofMillis(100))
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle very long timeout")
        void provider_withVeryLongTimeout_shouldBuild() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .timeout(Duration.ofDays(1))
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle baseUrl with trailing slash")
        void provider_withTrailingSlashUrl_shouldBuild() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .baseUrl("https://api.example.com/")
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle baseUrl without trailing slash")
        void provider_withoutTrailingSlashUrl_shouldBuild() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .baseUrl("https://api.example.com")
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle baseUrl with port")
        void provider_withPortInUrl_shouldBuild() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .baseUrl("https://api.example.com:8443/v1")
                    .build();

            assertNotNull(provider);
        }

        @Test
        @DisplayName("should handle http protocol in baseUrl")
        void provider_withHttpProtocol_shouldBuild() {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .baseUrl("http://localhost:8080/v1")
                    .build();

            assertNotNull(provider);
        }
    }

    @Nested
    @DisplayName("Response Builder Combinations")
    class ResponseBuilderCombinationsTests {

        @Test
        @DisplayName("should build minimal response")
        void response_withMinimalData_shouldBuild() {
            LLMResponse response = LLMResponse.builder("id", "model")
                    .role(LLMMessage.Role.ASSISTANT)
                    .build();

            assertNotNull(response);
            assertEquals("id", response.id());
            assertEquals("model", response.model());
        }

        @Test
        @DisplayName("should build response with content only")
        void response_withContentOnly_shouldBuild() {
            LLMResponse response = LLMResponse.builder("id", "model")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Response text")
                    .build();

            assertEquals("Response text", response.content());
        }

        @Test
        @DisplayName("should handle response with usage only")
        void response_withUsageOnly_shouldBuild() {
            LLMResponse response = LLMResponse.builder("id", "model")
                    .role(LLMMessage.Role.ASSISTANT)
                    .usage(10, 5, 15)
                    .build();

            assertNotNull(response.usage());
            assertEquals(10, response.usage().promptTokens());
            assertEquals(5, response.usage().completionTokens());
        }

        @Test
        @DisplayName("should build response with finish reason only")
        void response_withFinishReasonOnly_shouldBuild() {
            LLMResponse response = LLMResponse.builder("id", "model")
                    .role(LLMMessage.Role.ASSISTANT)
                    .finishReason("stop")
                    .build();

            assertEquals("stop", response.finishReason());
        }

        @Test
        @DisplayName("should build response with function calls only")
        void response_withFunctionCallsOnly_shouldBuild() {
            FunctionCall call = new FunctionCall("call-1", "func", "{}");
            LLMResponse response = LLMResponse.builder("id", "model")
                    .role(LLMMessage.Role.ASSISTANT)
                    .functionCalls(List.of(call))
                    .build();

            assertTrue(response.hasFunctionCalls());
        }

        @Test
        @DisplayName("should build response with metadata only")
        void response_withMetadataOnly_shouldBuild() {
            Map<String, Object> meta = Map.of("key", "value");
            LLMResponse response = LLMResponse.builder("id", "model")
                    .role(LLMMessage.Role.ASSISTANT)
                    .metadata(meta)
                    .build();

            assertNotNull(response.metadata());
        }

        @Test
        @DisplayName("should build response with all fields")
        void response_withAllFields_shouldBuildCompletely() {
            FunctionCall call = new FunctionCall("call-1", "func", "{}");
            Map<String, Object> meta = Map.of("key", "value");

            LLMResponse response = LLMResponse.builder("id-123", "gpt-4o")
                    .role(LLMMessage.Role.ASSISTANT)
                    .content("Complete response")
                    .usage(100, 50, 150)
                    .finishReason("stop")
                    .functionCalls(List.of(call))
                    .metadata(meta)
                    .build();

            assertEquals("id-123", response.id());
            assertEquals("gpt-4o", response.model());
            assertEquals("Complete response", response.content());
            assertNotNull(response.usage());
            assertEquals(100, response.usage().promptTokens());
            assertEquals("stop", response.finishReason());
            assertTrue(response.hasFunctionCalls());
            assertNotNull(response.metadata());
        }
    }

    @Nested
    @DisplayName("Streaming Chunk Combinations")
    class StreamingChunkCombinationsTests {

        @Test
        @DisplayName("should create chunk with content and no finish")
        void chunk_withContent_shouldNotBeLast() {
            StreamingChunk chunk = StreamingChunk.of("s1", "m1", "text", 0);

            assertTrue(chunk.hasContent());
            assertFalse(chunk.isLast());
            assertNull(chunk.finishReason());
        }

        @Test
        @DisplayName("should create chunk with empty content and finish")
        void chunk_withFinish_shouldBeLast() {
            StreamingChunk chunk = StreamingChunk.of("s1", "m1", "", "stop", 1);

            assertFalse(chunk.hasContent());
            assertTrue(chunk.isLast());
            assertEquals("stop", chunk.finishReason());
        }

        @Test
        @DisplayName("should handle various finish reasons")
        void chunk_withDifferentFinishReasons_shouldStoreCorrectly() {
            List<String> finishReasons = Arrays.asList("stop", "length", "tool_calls", "content_filter");

            for (String reason : finishReasons) {
                StreamingChunk chunk = StreamingChunk.of("s1", "m1", "", reason, 0);
                assertEquals(reason, chunk.finishReason());
                assertTrue(chunk.isLast());
            }
        }
    }

    @Nested
    @DisplayName("Request Builder Combinations")
    class RequestBuilderCombinationsTests {

        @Test
        @DisplayName("should build request with single message")
        void request_withSingleMessage_shouldBuild() {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Hello"))
                    .build();

            assertEquals(1, request.messages().size());
        }

        @Test
        @DisplayName("should build request with many messages")
        void request_withManyMessages_shouldBuild() {
            LLMRequest.Builder builder = LLMRequest.builder();

            for (int i = 0; i < 20; i++) {
                if (i % 2 == 0) {
                    builder.addMessage(LLMMessage.user("User message " + i));
                } else {
                    builder.addMessage(LLMMessage.assistant("Assistant message " + i));
                }
            }

            LLMRequest request = builder.build();
            assertEquals(20, request.messages().size());
        }

        @Test
        @DisplayName("should build request with functions and messages")
        void request_withFunctionsAndMessages_shouldBuild() {
            FunctionDefinition func = FunctionDefinition.builder("test")
                    .description("Test")
                    .build();

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Call function"))
                    .addFunction(func)
                    .build();

            assertEquals(1, request.messages().size());
            assertTrue(request.hasFunctions());
        }

        @Test
        @DisplayName("should build request with all parameters and functions")
        void request_withEverything_shouldBuild() {
            FunctionDefinition func = FunctionDefinition.builder("test")
                    .description("Test function")
                    .stringParameter("param", "Parameter", true)
                    .build();

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.system("System"))
                    .addMessage(LLMMessage.user("User"))
                    .addFunction(func)
                    .temperature(0.7)
                    .maxTokens(1000)
                    .topP(0.9)
                    .presencePenalty(0.5)
                    .frequencyPenalty(0.3)
                    .build();

            assertEquals(2, request.messages().size());
            assertTrue(request.hasFunctions());
            assertEquals(0.7, request.temperature());
            assertEquals(1000, request.maxTokens());
        }
    }

    @Nested
    @DisplayName("Available Models Tests")
    class AvailableModelsTests {

        @Test
        @DisplayName("should return consistent model list")
        void getAvailableModels_calledMultipleTimes_shouldReturnSame() throws Exception {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            List<String> models1 = provider.getAvailableModels().get();
            List<String> models2 = provider.getAvailableModels().get();

            assertEquals(models1.size(), models2.size());
            assertTrue(models1.containsAll(models2));
        }

        @Test
        @DisplayName("should include all expected models")
        void getAvailableModels_shouldIncludeAllExpected() throws Exception {
            OpenAIProvider provider = OpenAIProvider.builder()
                    .apiKey("test-key")
                    .build();

            List<String> models = provider.getAvailableModels().get();
            List<String> expectedModels = Arrays.asList(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gpt-4-turbo",
                    "gpt-4",
                    "gpt-3.5-turbo"
            );

            assertTrue(models.containsAll(expectedModels));
        }
    }
}
