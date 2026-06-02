package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for LLMRequest class and its builder.
 */
class LLMRequestTest {

    @Test
    @DisplayName("Should build basic request")
    void testBasicRequest() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Hello")
            .build();

        assertEquals("gpt-4", request.model());
        assertEquals(1, request.messages().size());
        assertEquals("Hello", request.messages().getFirst().content());
    }

    @Test
    @DisplayName("Should build request with multiple messages")
    void testMultipleMessages() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .systemMessage("You are helpful")
            .userMessage("Question 1")
            .assistantMessage("Answer 1")
            .userMessage("Question 2")
            .build();

        assertEquals(4, request.messages().size());
        assertTrue(request.messages().get(0).isSystem());
        assertTrue(request.messages().get(1).isUser());
        assertTrue(request.messages().get(2).isAssistant());
        assertTrue(request.messages().get(3).isUser());
    }

    @Test
    @DisplayName("Should set temperature")
    void testTemperature() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .temperature(0.8)
            .build();

        assertEquals(0.8, request.temperature());
    }

    @Test
    @DisplayName("Should reject invalid temperature")
    void testInvalidTemperature() {
        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .temperature(-0.1)
                .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .temperature(2.1)
                .build();
        });
    }

    @Test
    @DisplayName("Should set max tokens")
    void testMaxTokens() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .maxTokens(100)
            .build();

        assertEquals(100, request.maxTokens());
    }

    @Test
    @DisplayName("Should reject invalid max tokens")
    void testInvalidMaxTokens() {
        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .maxTokens(0)
                .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .maxTokens(-100)
                .build();
        });
    }

    @Test
    @DisplayName("Should add function definitions")
    void testFunctions() {
        FunctionDefinition func = FunctionDefinition.builder("test_func")
            .description("Test function")
            .stringParameter("arg1", "Argument 1", true)
            .build();

        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .addFunction(func)
            .build();

        assertTrue(request.hasFunctions());
        assertEquals(1, request.functions().size());
        assertEquals("test_func", request.functions().getFirst().name());
    }

    @Test
    @DisplayName("Should set function call directive")
    void testFunctionCall() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .functionCall("auto")
            .build();

        assertEquals("auto", request.functionCall());
    }

    @Test
    @DisplayName("Should set top_p")
    void testTopP() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .topP(0.9)
            .build();

        assertEquals(0.9, request.topP());
    }

    @Test
    @DisplayName("Should reject invalid top_p")
    void testInvalidTopP() {
        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .topP(-0.1)
                .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .topP(1.1)
                .build();
        });
    }

    @Test
    @DisplayName("Should set stop sequences")
    void testStopSequences() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .stop(List.of("END", "STOP"))
            .build();

        assertEquals(2, request.stop().size());
        assertTrue(request.stop().contains("END"));
        assertTrue(request.stop().contains("STOP"));
    }

    @Test
    @DisplayName("Should set presence penalty")
    void testPresencePenalty() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .presencePenalty(0.5)
            .build();

        assertEquals(0.5, request.presencePenalty());
    }

    @Test
    @DisplayName("Should reject invalid presence penalty")
    void testInvalidPresencePenalty() {
        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .presencePenalty(-2.1)
                .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .presencePenalty(2.1)
                .build();
        });
    }

    @Test
    @DisplayName("Should set frequency penalty")
    void testFrequencyPenalty() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .frequencyPenalty(0.3)
            .build();

        assertEquals(0.3, request.frequencyPenalty());
    }

    @Test
    @DisplayName("Should reject invalid frequency penalty")
    void testInvalidFrequencyPenalty() {
        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .frequencyPenalty(-2.1)
                .build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4")
                .userMessage("Test")
                .frequencyPenalty(2.1)
                .build();
        });
    }

    @Test
    @DisplayName("Should allow null model — deferred to provider")
    @SuppressWarnings("deprecation")
    void testNullModel() {
        // builder(null) is deprecated but must not throw; model is resolved at execution time
        LLMRequest request = LLMRequest.builder(null)
                .userMessage("Hello")
                .build();
        assertNull(request.model());
    }

    @Test
    @DisplayName("Should build request with no-arg builder (model deferred to provider)")
    void testNoArgBuilder() {
        LLMRequest request = LLMRequest.builder()
                .userMessage("Hello")
                .build();
        assertNull(request.model());
    }

    @Test
    @DisplayName("Should set model via model() setter on builder")
    void testModelSetter() {
        LLMRequest request = LLMRequest.builder()
                .model("gpt-4o")
                .userMessage("Hello")
                .build();
        assertEquals("gpt-4o", request.model());
    }

    @Test
    @DisplayName("Should reject empty messages")
    void testEmptyMessages() {
        assertThrows(IllegalArgumentException.class, () -> {
            LLMRequest.builder().model("gpt-4").build();
        });
    }

    @Test
    @DisplayName("Should get last user message")
    void testGetLastUserMessage() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .systemMessage("System")
            .userMessage("First question")
            .assistantMessage("Answer")
            .userMessage("Second question")
            .build();

        LLMMessage lastUser = request.getLastUserMessage();
        assertNotNull(lastUser);
        assertEquals("Second question", lastUser.content());
    }

    @Test
    @DisplayName("Should return null when no user message")
    void testNoUserMessage() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .systemMessage("System only")
            .build();

        assertNull(request.getLastUserMessage());
    }

    @Test
    @DisplayName("Should count messages correctly")
    void testMessageCount() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .systemMessage("System")
            .userMessage("User")
            .assistantMessage("Assistant")
            .build();

        assertEquals(3, request.messageCount());
    }

    @Test
    @DisplayName("Should create immutable messages list")
    void testImmutableMessages() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .build();

        assertThrows(UnsupportedOperationException.class, () -> {
            request.messages().clear();
        });
    }

    @Test
    @DisplayName("Should replace messages with messages() method")
    void testReplaceMessages() {
        List<LLMMessage> newMessages = List.of(
            LLMMessage.system("New system"),
            LLMMessage.user("New user")
        );

        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Old message")
            .messages(newMessages)
            .build();

        assertEquals(2, request.messages().size());
        assertEquals("New system", request.messages().get(0).content());
        assertEquals("New user", request.messages().get(1).content());
    }

    @Test
    @DisplayName("Should handle all optional parameters as null")
    void testAllOptionalNull() {
        LLMRequest request = LLMRequest.builder().model("gpt-4")
            .userMessage("Test")
            .build();

        assertNull(request.temperature());
        assertNull(request.maxTokens());
        assertNull(request.functions());
        assertNull(request.functionCall());
        assertNull(request.topP());
        assertNull(request.n());
        assertNull(request.stop());
        assertNull(request.presencePenalty());
        assertNull(request.frequencyPenalty());
        assertNull(request.additionalParameters());
    }
}
