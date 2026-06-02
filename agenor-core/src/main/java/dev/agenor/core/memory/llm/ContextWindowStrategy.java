package dev.agenor.core.memory.llm;

import dev.agenor.core.llm.LLMMessage;

import java.util.List;

/**
 * Strategy for selecting messages to include in LLM context window.
 *
 * <p>When conversation history exceeds the model's context window, a strategy
 * is needed to decide which messages to include. Different strategies optimize
 * for different goals:
 * <ul>
 *   <li><b>Recency:</b> Most recent messages (simple, fast)</li>
 *   <li><b>Importance:</b> Most important messages (requires scoring)</li>
 *   <li><b>Summarization:</b> Recent + summary of old (preserves context)</li>
 *   <li><b>Semantic:</b> Most relevant to current query (requires embeddings)</li>
 * </ul>
 *
 * <p><b>Built-in Implementations:</b>
 * <p>Concrete implementations are available in the runtime module:
 * <ul>
 *   <li>Fixed window - Last N messages that fit</li>
 *   <li>Sliding window - Recent + important messages</li>
 *   <li>Summarization - Recent + summary of old</li>
 * </ul>
 *
 * <p>Implementations are typically accessed through a factory class
 * provided by the runtime module.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * // Obtain strategy from runtime
 * ContextWindowStrategy strategy = ...; // Provided by runtime
 *
 * // Use strategy to select messages
 * List<LLMMessage> selected = strategy.selectMessages(
 *     allMessages,
 *     2000,
 *     tokenEstimator
 * );
 *
 * // Use in LLM request
 * LLMRequest request = LLMRequest.builder()
 *     .messages(selected)
 *     .maxTokens(500)
 *     .build();
 * }</pre>
 *
 * <p><b>Custom Strategies:</b>
 * <pre>{@code
 * public class ImportanceStrategy implements ContextWindowStrategy {
 *
 *     {@literal @}Override
 *     public List<LLMMessage> selectMessages(
 *         List<LLMMessage> allMessages,
 *         int maxTokens,
 *         TokenEstimator estimator
 *     ) {
 *         // Score messages by importance
 *         // Select highest scoring that fit in budget
 *         return selectedMessages;
 *     }
 *
 *     {@literal @}Override
 *     public String getName() {
 *         return "importance";
 *     }
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b> Implementations must be thread-safe.
 *
 * @since 0.6.0
 */
public interface ContextWindowStrategy {
    /**
     * Select messages to include in context window.
     *
     * <p>Implementations must:
     * <ul>
     *   <li>Return messages that fit within maxTokens budget</li>
     *   <li>Preserve message order (oldest to newest)</li>
     *   <li>Use provided TokenEstimator for token counting</li>
     *   <li>Handle edge cases (empty list, budget too small, etc.)</li>
     * </ul>
     *
     * <p><b>Example Implementation:</b>
     * <pre>{@code
     * public List<LLMMessage> selectMessages(
     *     List<LLMMessage> allMessages,
     *     int maxTokens,
     *     TokenEstimator estimator
     * ) {
     *     List<LLMMessage> selected = new ArrayList<>();
     *     int currentTokens = 0;
     *
     *     // Start from end (most recent)
     *     for (int i = allMessages.size() - 1; i >= 0; i--) {
     *         LLMMessage msg = allMessages.get(i);
     *         int msgTokens = estimator.estimateTokens(msg);
     *
     *         if (currentTokens + msgTokens <= maxTokens) {
     *             selected.add(0, msg);  // Add at start to maintain order
     *             currentTokens += msgTokens;
     *         } else {
     *             break;  // Budget exhausted
     *         }
     *     }
     *
     *     return selected;
     * }
     * }</pre>
     *
     * @param allMessages all available messages (oldest to newest)
     * @param maxTokens maximum tokens for selected messages
     * @param estimator token estimator to use
     * @return selected messages that fit in budget (oldest to newest)
     * @throws IllegalArgumentException if any parameter is null or maxTokens {@literal <=} 0
     */
    List<LLMMessage> selectMessages(
        List<LLMMessage> allMessages,
        int maxTokens,
        TokenEstimator estimator
    );

    /**
     * Get strategy name for logging and debugging.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"fixed" - Fixed window strategy</li>
     *   <li>"sliding" - Sliding window strategy</li>
     *   <li>"summarized" - Summarization strategy</li>
     *   <li>"semantic" - Semantic relevance strategy</li>
     * </ul>
     *
     * @return strategy name (lowercase, no spaces)
     */
    String getName();

    /**
     * Check if this strategy requires an LLM provider.
     *
     * <p>Some strategies (like summarization) need an LLM to generate
     * summaries. Others (like fixed window) do not.
     *
     * @return true if strategy needs LLM access
     */
    default boolean requiresLLM() {
        return false;
    }

    /**
     * Get estimated overhead tokens for this strategy.
     *
     * <p>Some strategies add overhead:
     * <ul>
     *   <li>Fixed/Sliding: 0 tokens (no modifications)</li>
     *   <li>Summarized: ~100-300 tokens (summary message)</li>
     *   <li>Semantic: 0 tokens (just selection)</li>
     * </ul>
     *
     * @return estimated overhead in tokens
     */
    default int getOverheadTokens() {
        return 0;
    }
}
