package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LLMResponseTest {

    @Test
    void shouldCreateResponseWithBuilder() {
        // Given/When
        var response = LLMResponse.builder("resp_123", "gpt-4")
            .content("Hello, world!")
            .usage(10, 20, 30)
            .finishReason("stop")
            .build();

        // Then
        assertEquals("resp_123", response.id());
        assertEquals("gpt-4", response.model());
        assertEquals("Hello, world!", response.content());
        assertEquals("stop", response.finishReason());
        assertNotNull(response.usage());
        assertEquals(30, response.usage().totalTokens());
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            LLMResponse.builder(null, "model").build()
        );
    }

    @Test
    void shouldThrowExceptionWhenModelIsNull() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            LLMResponse.builder("id", null).build()
        );
    }

    @Test
    void shouldDefaultRoleToAssistant() {
        // Given/When
        var response = LLMResponse.builder("id", "model").build();

        // Then
        assertEquals(LLMMessage.Role.ASSISTANT, response.role());
    }

    @Test
    void shouldSetCustomRole() {
        // Given/When
        var response = LLMResponse.builder("id", "model")
            .role(LLMMessage.Role.SYSTEM)
            .build();

        // Then
        assertEquals(LLMMessage.Role.SYSTEM, response.role());
    }

    @Test
    void shouldSetCreatedTimestamp() {
        // Given
        Instant customTime = Instant.parse("2024-01-01T12:00:00Z");

        // When
        var response = LLMResponse.builder("id", "model")
            .created(customTime)
            .build();

        // Then
        assertEquals(customTime, response.created());
    }

    @Test
    void shouldDefaultCreatedToNow() {
        // Given
        Instant before = Instant.now();

        // When
        var response = LLMResponse.builder("id", "model").build();

        // Then
        assertNotNull(response.created());
        assertTrue(response.created().isAfter(before.minusSeconds(1)));
    }

    @Test
    void shouldAddFunctionCalls() {
        // Given
        var call1 = FunctionCall.of("func1", "{}");
        var call2 = FunctionCall.of("func2", "{}");

        // When
        var response = LLMResponse.builder("id", "model")
            .functionCalls(List.of(call1, call2))
            .build();

        // Then
        assertTrue(response.hasFunctionCalls());
        assertEquals(2, response.functionCalls().size());
    }

    @Test
    void shouldCheckHasFunctionCalls() {
        // Given
        var withCalls = LLMResponse.builder("id", "model")
            .functionCalls(List.of(FunctionCall.of("test")))
            .build();

        var withoutCalls = LLMResponse.builder("id", "model").build();

        // Then
        assertTrue(withCalls.hasFunctionCalls());
        assertFalse(withoutCalls.hasFunctionCalls());
    }

    @Test
    void shouldMakeFunctionCallsImmutable() {
        // Given
        var response = LLMResponse.builder("id", "model")
            .functionCalls(List.of(FunctionCall.of("test")))
            .build();

        // When/Then
        assertThrows(UnsupportedOperationException.class, () ->
            response.functionCalls().add(FunctionCall.of("new"))
        );
    }

    @Test
    void shouldAddMetadata() {
        // Given
        Map<String, Object> metadata = Map.of("key1", "value1", "key2", 42);

        // When
        var response = LLMResponse.builder("id", "model")
            .metadata(metadata)
            .build();

        // Then
        assertEquals(metadata, response.metadata());
    }

    @Test
    void shouldMakeMetadataImmutable() {
        // Given
        var response = LLMResponse.builder("id", "model")
            .metadata(Map.of("key", "value"))
            .build();

        // When/Then
        assertThrows(UnsupportedOperationException.class, () ->
            response.metadata().put("new", "value")
        );
    }

    @Test
    void shouldCheckWasTruncated() {
        // Given
        var truncated = LLMResponse.builder("id", "model")
            .finishReason("length")
            .build();

        var notTruncated = LLMResponse.builder("id", "model")
            .finishReason("stop")
            .build();

        // Then
        assertTrue(truncated.wasTruncated());
        assertFalse(notTruncated.wasTruncated());
    }

    @Test
    void shouldCheckIsComplete() {
        // Given
        var complete = LLMResponse.builder("id", "model")
            .finishReason("stop")
            .build();

        var incomplete = LLMResponse.builder("id", "model")
            .finishReason("length")
            .build();

        // Then
        assertTrue(complete.isComplete());
        assertFalse(incomplete.isComplete());
    }

    @Test
    void shouldConvertToMessageWithoutFunctionCalls() {
        // Given
        var response = LLMResponse.builder("id", "model")
            .content("Hello")
            .build();

        // When
        LLMMessage message = response.toMessage();

        // Then
        assertEquals(LLMMessage.Role.ASSISTANT, message.role());
        assertEquals("Hello", message.content());
        assertFalse(message.hasFunctionCalls());
    }

    @Test
    void shouldConvertToMessageWithFunctionCalls() {
        // Given
        var call = FunctionCall.of("test");
        var response = LLMResponse.builder("id", "model")
            .content("Calling function")
            .functionCalls(List.of(call))
            .build();

        // When
        LLMMessage message = response.toMessage();

        // Then
        assertEquals(LLMMessage.Role.ASSISTANT, message.role());
        assertTrue(message.hasFunctionCalls());
    }

    @Test
    void shouldTruncateContent() {
        // Given
        String longContent = "A".repeat(100);
        var response = LLMResponse.builder("id", "model")
            .content(longContent)
            .build();

        // When
        String truncated = response.truncatedContent(20);

        // Then
        assertEquals(20, truncated.length());
        assertTrue(truncated.endsWith("..."));
    }

    @Test
    void shouldNotTruncateShortContent() {
        // Given
        var response = LLMResponse.builder("id", "model")
            .content("Short")
            .build();

        // When
        String truncated = response.truncatedContent(20);

        // Then
        assertEquals("Short", truncated);
    }

    @Test
    void shouldHandleNullContentInTruncate() {
        // Given
        var response = LLMResponse.builder("id", "model").build();

        // When
        String truncated = response.truncatedContent(20);

        // Then
        assertEquals("[no content]", truncated);
    }

    @Test
    void shouldGenerateToStringWithContent() {
        // Given
        var response = LLMResponse.builder("id", "gpt-4")
            .content("Hello world")
            .finishReason("stop")
            .usage(5, 2, 7)
            .build();

        // When
        String str = response.toString();

        // Then
        assertTrue(str.contains("id"));
        assertTrue(str.contains("gpt-4"));
        assertTrue(str.contains("stop"));
        assertTrue(str.contains("7"));
    }

    @Test
    void shouldGenerateToStringWithFunctionCalls() {
        // Given
        var response = LLMResponse.builder("id", "model")
            .functionCalls(List.of(FunctionCall.of("test1"), FunctionCall.of("test2")))
            .finishReason("function_call")
            .build();

        // When
        String str = response.toString();

        // Then
        assertTrue(str.contains("functionCalls=2"));
    }

    // ===== Usage Tests =====

    @Test
    void shouldCreateUsage() {
        // Given/When
        var usage = new LLMResponse.Usage(100, 50, 150);

        // Then
        assertEquals(100, usage.promptTokens());
        assertEquals(50, usage.completionTokens());
        assertEquals(150, usage.totalTokens());
    }

    @Test
    void shouldThrowExceptionForNegativePromptTokens() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new LLMResponse.Usage(-1, 50, 150)
        );
    }

    @Test
    void shouldThrowExceptionForNegativeCompletionTokens() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new LLMResponse.Usage(100, -1, 150)
        );
    }

    @Test
    void shouldThrowExceptionForNegativeTotalTokens() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new LLMResponse.Usage(100, 50, -1)
        );
    }

    @Test
    void shouldEstimateCost() {
        // Given
        var usage = new LLMResponse.Usage(1000, 500, 1500);
        double promptCostPer1k = 0.03;
        double completionCostPer1k = 0.06;

        // When
        double cost = usage.estimateCost(promptCostPer1k, completionCostPer1k);

        // Then
        assertEquals(0.06, cost, 0.001); // 1000/1000 * 0.03 + 500/1000 * 0.06
    }

    @Test
    void shouldEstimateCostForSmallUsage() {
        // Given
        var usage = new LLMResponse.Usage(100, 50, 150);
        double promptCostPer1k = 0.03;
        double completionCostPer1k = 0.06;

        // When
        double cost = usage.estimateCost(promptCostPer1k, completionCostPer1k);

        // Then
        assertEquals(0.006, cost, 0.0001); // 100/1000 * 0.03 + 50/1000 * 0.06
    }

    @Test
    void shouldHandleZeroTokens() {
        // Given/When
        var usage = new LLMResponse.Usage(0, 0, 0);

        // Then
        assertEquals(0, usage.promptTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(0, usage.totalTokens());
        assertEquals(0.0, usage.estimateCost(0.03, 0.06));
    }

    @Test
    void shouldSetUsageWithBuilder() {
        // Given
        var usage = new LLMResponse.Usage(100, 50, 150);

        // When
        var response = LLMResponse.builder("id", "model")
            .usage(usage)
            .build();

        // Then
        assertEquals(usage, response.usage());
    }

    @Test
    void shouldSetUsageWithIndividualValues() {
        // Given/When
        var response = LLMResponse.builder("id", "model")
            .usage(100, 50, 150)
            .build();

        // Then
        assertNotNull(response.usage());
        assertEquals(100, response.usage().promptTokens());
        assertEquals(50, response.usage().completionTokens());
        assertEquals(150, response.usage().totalTokens());
    }
}
