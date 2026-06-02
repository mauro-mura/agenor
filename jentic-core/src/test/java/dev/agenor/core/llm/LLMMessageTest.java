package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for LLMMessage class.
 */
class LLMMessageTest {

    @Test
    @DisplayName("Should create system message")
    void testSystemMessage() {
        LLMMessage message = LLMMessage.system("You are a helpful assistant.");

        assertEquals(LLMMessage.Role.SYSTEM, message.role());
        assertEquals("You are a helpful assistant.", message.content());
        assertNull(message.name());
        assertNull(message.functionCalls());
        assertTrue(message.isSystem());
        assertFalse(message.isUser());
        assertFalse(message.isAssistant());
        assertFalse(message.isFunction());
    }

    @Test
    @DisplayName("Should create user message")
    void testUserMessage() {
        LLMMessage message = LLMMessage.user("What is the capital of France?");

        assertEquals(LLMMessage.Role.USER, message.role());
        assertEquals("What is the capital of France?", message.content());
        assertTrue(message.isUser());
        assertFalse(message.hasFunctionCalls());
    }

    @Test
    @DisplayName("Should create assistant message")
    void testAssistantMessage() {
        LLMMessage message = LLMMessage.assistant("The capital of France is Paris.");

        assertEquals(LLMMessage.Role.ASSISTANT, message.role());
        assertEquals("The capital of France is Paris.", message.content());
        assertTrue(message.isAssistant());
    }

    @Test
    @DisplayName("Should create assistant message with function calls")
    void testAssistantMessageWithFunctions() {
        FunctionCall call = FunctionCall.of("get_weather", "{\"location\": \"Paris\"}");
        LLMMessage message = LLMMessage.assistant("Let me check the weather", List.of(call));

        assertEquals(LLMMessage.Role.ASSISTANT, message.role());
        assertEquals("Let me check the weather", message.content());
        assertTrue(message.hasFunctionCalls());
        assertEquals(1, message.functionCalls().size());
        assertEquals("get_weather", message.functionCalls().getFirst().name());
    }

    @Test
    @DisplayName("Should create function result message")
    void testFunctionMessage() {
        LLMMessage message = LLMMessage.function("get_weather",
            "{\"temperature\": 22, \"condition\": \"sunny\"}");

        assertEquals(LLMMessage.Role.FUNCTION, message.role());
        assertEquals("{\"temperature\": 22, \"condition\": \"sunny\"}", message.content());
        assertEquals("get_weather", message.name());
        assertTrue(message.isFunction());
    }

    @Test
    @DisplayName("Should reject null role")
    void testNullRole() {
        assertThrows(NullPointerException.class, () -> {
            new LLMMessage(null, "content", null, null);
        });
    }

    @Test
    @DisplayName("Should reject message without content or function calls")
    void testNoContentOrFunctions() {
        assertThrows(IllegalArgumentException.class, () -> {
            new LLMMessage(LLMMessage.Role.ASSISTANT, null, null, null);
        });
    }

    @Test
    @DisplayName("Should allow assistant message with only function calls")
    void testAssistantWithOnlyFunctionCalls() {
        FunctionCall call = FunctionCall.of("calculate", "{\"a\": 5, \"b\": 3}");
        LLMMessage message = new LLMMessage(LLMMessage.Role.ASSISTANT, null, null, List.of(call));

        assertNull(message.content());
        assertTrue(message.hasFunctionCalls());
        assertEquals(1, message.functionCalls().size());
    }

    @Test
    @DisplayName("Should truncate long content")
    void testTruncateContent() {
        String longContent = "a".repeat(100);
        LLMMessage message = LLMMessage.user(longContent);

        String truncated = message.truncatedContent(20);
        assertEquals(20, truncated.length());
        assertTrue(truncated.endsWith("..."));
    }

    @Test
    @DisplayName("Should not truncate short content")
    void testNoTruncateShortContent() {
        LLMMessage message = LLMMessage.user("Short message");

        String truncated = message.truncatedContent(50);
        assertEquals("Short message", truncated);
    }

    @Test
    @DisplayName("Should handle null content in truncation")
    void testTruncateNullContent() {
        FunctionCall call = FunctionCall.of("test", "{}");
        LLMMessage message = new LLMMessage(LLMMessage.Role.ASSISTANT, null, null, List.of(call));

        String truncated = message.truncatedContent(20);
        assertEquals("[no content]", truncated);
    }

    @Test
    @DisplayName("Should create immutable function calls list")
    void testImmutableFunctionCalls() {
        FunctionCall call = FunctionCall.of("test", "{}");
        List<FunctionCall> calls = new java.util.ArrayList<>();
        calls.add(call);

        LLMMessage message = new LLMMessage(LLMMessage.Role.ASSISTANT, "test", null, calls);

        // Original list modification should not affect message
        calls.clear();
        assertEquals(1, message.functionCalls().size());

        // Message list should be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> {
            message.functionCalls().clear();
        });
    }

    @Test
    @DisplayName("Should generate meaningful toString")
    void testToString() {
        LLMMessage message = LLMMessage.user("Test message");
        String str = message.toString();

        assertTrue(str.contains("USER"));
        assertTrue(str.contains("Test message"));
    }

    @Test
    @DisplayName("Should handle all role types correctly")
    void testAllRoleTypes() {
        LLMMessage system = LLMMessage.system("System");
        LLMMessage user = LLMMessage.user("User");
        LLMMessage assistant = LLMMessage.assistant("Assistant");
        LLMMessage function = LLMMessage.function("func", "result");

        assertTrue(system.isSystem() && !system.isUser() && !system.isAssistant() && !system.isFunction());
        assertTrue(user.isUser() && !user.isSystem() && !user.isAssistant() && !user.isFunction());
        assertTrue(assistant.isAssistant() && !assistant.isSystem() && !assistant.isUser() && !assistant.isFunction());
        assertTrue(function.isFunction() && !function.isSystem() && !function.isUser() && !function.isAssistant());
    }
}
