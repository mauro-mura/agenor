package dev.agenor.core.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.MemoryEntry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Manages agent memory specifically for LLM contexts.
 *
 * <p>The LLMMemoryManager provides intelligent memory management for agents
 * that use Large Language Models. It handles:
 * <ul>
 *   <li>Conversation history management</li>
 *   <li>Token budget optimization</li>
 *   <li>Automatic summarization of old messages</li>
 *   <li>Relevant context retrieval</li>
 *   <li>Integration with MemoryStore for persistence</li>
 * </ul>
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Token-Aware:</b> Respects model context window limits</li>
 *   <li><b>Smart Selection:</b> Uses strategies to select most relevant messages</li>
 *   <li><b>Auto-Summarization:</b> Compresses old conversations automatically</li>
 *   <li><b>Persistent:</b> Stores important context in MemoryStore</li>
 * </ul>
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * // Initialize LLM memory manager
 * LLMMemoryManager llmMemory = new DefaultLLMMemoryManager(
 *     memoryStore,
 *     llmProvider,
 *     "my-agent"
 * );
 *
 * // Add messages to conversation
 * llmMemory.addMessage(LLMMessage.user("Hello!")).join();
 * llmMemory.addMessage(LLMMessage.assistant("Hi! How can I help?")).join();
 *
 * // Get conversation history for LLM prompt (token-aware)
 * // Strategy instance obtained from runtime
 * ContextWindowStrategy strategy = ...; // Injected or from factory
 * List<LLMMessage> history = llmMemory.getConversationHistory(
 *     2000,      // Max 2000 tokens
 *     strategy   // Selection strategy
 * ).join();
 *
 * // Use in LLM request
 * LLMRequest request = LLMRequest.builder()
 *     .messages(history)
 *     .addMessage(LLMMessage.user("What did we discuss?"))
 *     .build();
 * }</pre>
 *
 * <p><b>Thread Safety:</b>
 * All operations are thread-safe and return CompletableFuture for async execution.
 *
 * @since 0.6.0
 */
public interface LLMMemoryManager {

    /**
     * Add a message to the conversation history.
     *
     * <p>The message is stored in both short-term (conversation) and optionally
     * long-term (MemoryStore) memory depending on importance.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * llmMemory.addMessage(LLMMessage.user("What's the weather?"))
     *     .thenRun(() -> log.info("Message added"));
     * }</pre>
     *
     * @param message the LLM message to add
     * @return future that completes when message is stored
     * @throws IllegalArgumentException if message is null
     */
    CompletableFuture<Void> addMessage(LLMMessage message);

    /**
     * Add multiple messages to the conversation history.
     *
     * <p>More efficient than calling addMessage() multiple times.
     *
     * @param messages the messages to add
     * @return future that completes when all messages are stored
     * @throws IllegalArgumentException if messages is null or empty
     */
    CompletableFuture<Void> addMessages(List<LLMMessage> messages);

    /**
     * Get conversation history for LLM prompt with token budget.
     *
     * <p>Uses the specified strategy to select which messages to include
     * within the token budget. Older messages may be summarized or excluded.
     *
     * <p><b>Strategies:</b>
     * <p>Strategy implementations determine which messages to include:
     * <ul>
     *   <li>Fixed window - Last N messages that fit in budget</li>
     *   <li>Sliding window - Most recent + important messages</li>
     *   <li>Summarized - Recent messages + summary of old ones</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * // Obtain strategy from runtime (e.g., via dependency injection)
     * ContextWindowStrategy strategy = ...; // Provided by runtime
     *
     * // Get conversation with token budget and strategy
     * List<LLMMessage> history = llmMemory.getConversationHistory(
     *     2000,      // Max tokens
     *     strategy   // Selection strategy
     * ).join();
     * }</pre>
     *
     * @param maxTokens maximum tokens for the context
     * @param strategy strategy for selecting messages
     * @return future with list of messages that fit in budget
     * @throws IllegalArgumentException if maxTokens {@literal <=} 0 or strategy is null
     */
    CompletableFuture<List<LLMMessage>> getConversationHistory(
        int maxTokens,
        ContextWindowStrategy strategy
    );

    /**
     * Get all messages in conversation history (no token limit).
     *
     * <p><b>Warning:</b> This may return many messages. Use with caution
     * and prefer token-limited methods for LLM prompts.
     *
     * @return future with all conversation messages
     */
    CompletableFuture<List<LLMMessage>> getAllMessages();

    /**
     * Store important context in long-term memory.
     *
     * <p>This stores a key-value pair in the MemoryStore for later retrieval.
     * Use this for facts, preferences, or important information that should
     * persist beyond the current conversation.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * llmMemory.remember("user-name", "Alice", Map.of(
     *     "category", "profile",
     *     "confidence", "high"
     * ));
     * }</pre>
     *
     * @param key the memory key
     * @param content the content to remember
     * @param metadata optional metadata for the memory
     * @return future that completes when stored
     * @throws IllegalArgumentException if key or content is null
     */
    CompletableFuture<Void> remember(
        String key,
        String content,
        Map<String, Object> metadata
    );

    /**
     * Store important context in long-term memory without metadata.
     *
     * @param key the memory key
     * @param content the content to remember
     * @return future that completes when stored
     */
    default CompletableFuture<Void> remember(String key, String content) {
        return remember(key, content, Map.of());
    }

    /**
     * Retrieve relevant context from long-term memory.
     *
     * <p>Searches the MemoryStore for entries relevant to the query
     * and returns them formatted for LLM context. Results are limited
     * by token budget.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * // Get user preferences (up to 500 tokens)
     * List<MemoryEntry> context = llmMemory.retrieveRelevantContext(
     *     "user preferences",
     *     500
     * ).join();
     * }</pre>
     *
     * @param query the search query
     * @param maxTokens maximum tokens for retrieved context
     * @return future with relevant memory entries
     * @throws IllegalArgumentException if query is null or maxTokens {@literal <=} 0
     */
    CompletableFuture<List<MemoryEntry>> retrieveRelevantContext(
        String query,
        int maxTokens
    );

    /**
     * Summarize old messages to save tokens.
     *
     * <p>Takes the oldest N messages and creates a summary using the LLM.
     * The original messages are replaced with a single summary message.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * // Summarize oldest 20 messages
     * String summary = llmMemory.summarizeOldMessages(20).join();
     * System.out.println("Summary: " + summary);
     * }</pre>
     *
     * @param messagesToSummarize number of oldest messages to summarize
     * @return future with summary text
     * @throws IllegalArgumentException if messagesToSummarize {@literal <=} 0
     * @throws IllegalStateException if not enough messages to summarize
     */
    CompletableFuture<String> summarizeOldMessages(int messagesToSummarize);

    /**
     * Clear conversation history (short-term memory).
     *
     * <p>Removes all messages from the current conversation.
     * Long-term memories (from {@link #remember}) are not affected.
     *
     * @return future that completes when history is cleared
     */
    CompletableFuture<Void> clearConversationHistory();

    /**
     * Get current token count of conversation history.
     *
     * <p>Returns the estimated total tokens used by all messages
     * in the conversation history.
     *
     * @return current token count
     */
    int getCurrentTokenCount();

    /**
     * Get number of messages in conversation history.
     *
     * @return message count
     */
    int getMessageCount();

    /**
     * Get the token estimator used by this manager.
     *
     * @return the token estimator
     */
    TokenEstimator getTokenEstimator();

    /**
     * Get the agent ID this manager is associated with.
     *
     * @return the agent ID
     */
    String getAgentId();
}
