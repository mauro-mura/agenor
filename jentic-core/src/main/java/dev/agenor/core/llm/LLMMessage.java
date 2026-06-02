package dev.agenor.core.llm;

import java.util.List;
import java.util.Objects;

/**
 * Represents a single message in an LLM conversation.
 *
 * <p>Messages have a role (system, user, assistant, or function) and content.
 * This class is immutable and thread-safe.
 *
 * <p>Message roles:
 * <ul>
 *   <li><b>SYSTEM</b>: Instructions that set the behavior/context for the LLM</li>
 *   <li><b>USER</b>: Messages from the user/human</li>
 *   <li><b>ASSISTANT</b>: Messages from the LLM</li>
 *   <li><b>FUNCTION</b>: Results from function calls (for function calling)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * LLMMessage systemMsg = LLMMessage.system("You are a helpful assistant.");
 * LLMMessage userMsg = LLMMessage.user("What is 2+2?");
 * LLMMessage assistantMsg = LLMMessage.assistant("2+2 equals 4.");
 * }</pre>
 *
 * @param role the role of the message (system, user, assistant, or function)
 * @param content the textual content of the message
 * @param name optional name identifier for the message sender
 * @param functionCalls optional list of function calls (for assistant role)
 *
 * @since 0.3.0
 */
public record LLMMessage(
    Role role,
    String content,
    String name,
    List<FunctionCall> functionCalls
) {

    /**
     * Message role in the conversation.
     */
    public enum Role {
        /**
         * System message that sets behavior and context
         */
        SYSTEM,

        /**
         * Message from the user/human
         */
        USER,

        /**
         * Message from the AI assistant
         */
        ASSISTANT,

        /**
         * Function call result message
         */
        FUNCTION
    }

    /**
     * Compact constructor with validation.
     */
    public LLMMessage {
        Objects.requireNonNull(role, "Role cannot be null");

        // Content can be null for assistant messages with function calls
        if (content == null && (functionCalls == null || functionCalls.isEmpty())) {
            throw new IllegalArgumentException("Message must have either content or function calls");
        }

        // Make defensive copies of mutable collections
        if (functionCalls != null) {
            functionCalls = List.copyOf(functionCalls);
        }
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a system message.
     *
     * <p>System messages provide instructions and context to the LLM.
     * They typically appear at the start of the conversation.
     *
     * @param content the system message content
     * @return a new system message
     */
    public static LLMMessage system(String content) {
        return new LLMMessage(Role.SYSTEM, content, null, null);
    }

    /**
     * Create a user message.
     *
     * <p>User messages represent input from the human user.
     *
     * @param content the user message content
     * @return a new user message
     */
    public static LLMMessage user(String content) {
        return new LLMMessage(Role.USER, content, null, null);
    }

    /**
     * Create an assistant message.
     *
     * <p>Assistant messages represent responses from the LLM.
     *
     * @param content the assistant message content
     * @return a new assistant message
     */
    public static LLMMessage assistant(String content) {
        return new LLMMessage(Role.ASSISTANT, content, null, null);
    }

    /**
     * Create an assistant message with function calls.
     *
     * <p>Used when the assistant wants to call functions/tools.
     *
     * @param content optional reasoning or explanation (can be null)
     * @param functionCalls the function calls to make
     * @return a new assistant message with function calls
     */
    public static LLMMessage assistant(String content, List<FunctionCall> functionCalls) {
        return new LLMMessage(Role.ASSISTANT, content, null, functionCalls);
    }

    /**
     * Create a function result message.
     *
     * <p>Function messages contain the results of function calls.
     *
     * @param functionName the name of the function that was called
     * @param result the function result (typically JSON)
     * @return a new function message
     */
    public static LLMMessage function(String functionName, String result) {
        return new LLMMessage(Role.FUNCTION, result, functionName, null);
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /**
     * Check if this message has function calls.
     *
     * @return true if the message contains function calls
     */
    public boolean hasFunctionCalls() {
        return functionCalls != null && !functionCalls.isEmpty();
    }

    /**
     * Check if this is a system message.
     *
     * @return true if role is SYSTEM
     */
    public boolean isSystem() {
        return role == Role.SYSTEM;
    }

    /**
     * Check if this is a user message.
     *
     * @return true if role is USER
     */
    public boolean isUser() {
        return role == Role.USER;
    }

    /**
     * Check if this is an assistant message.
     *
     * @return true if role is ASSISTANT
     */
    public boolean isAssistant() {
        return role == Role.ASSISTANT;
    }

    /**
     * Check if this is a function message.
     *
     * @return true if role is FUNCTION
     */
    public boolean isFunction() {
        return role == Role.FUNCTION;
    }

    /**
     * Get a truncated version of the content for logging.
     *
     * @param maxLength maximum length of the truncated content
     * @return truncated content with ellipsis if needed
     */
    public String truncatedContent(int maxLength) {
        if (content == null) {
            return "[no content]";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength - 3) + "...";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LLMMessage{");
        sb.append("role=").append(role);
        if (name != null) {
            sb.append(", name='").append(name).append('\'');
        }
        if (content != null) {
            sb.append(", content='").append(truncatedContent(50)).append('\'');
        }
        if (hasFunctionCalls()) {
            sb.append(", functionCalls=").append(functionCalls.size());
        }
        sb.append('}');
        return sb.toString();
    }
}
