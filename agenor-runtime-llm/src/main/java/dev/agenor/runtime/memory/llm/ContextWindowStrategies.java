package dev.agenor.runtime.memory.llm;

import dev.agenor.core.memory.llm.ContextWindowStrategy;

/**
 * Factory for built-in context window strategies.
 *
 * <p>Provides singleton instances of the standard strategies:
 * <ul>
 *   <li>{@link #FIXED} - Last N messages that fit in budget</li>
 *   <li>{@link #SLIDING} - Recent + important messages</li>
 *   <li>{@link #SUMMARIZED} - Recent + summary of old messages</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * import dev.agenor.runtime.memory.llm.ContextWindowStrategies;
 *
 * // Use built-in strategies
 * List<LLMMessage> selected = ContextWindowStrategies.SLIDING.selectMessages(
 *     allMessages,
 *     2000,
 *     estimator
 * );
 *
 * // Or with static import
 * import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.*;
 *
 * List<LLMMessage> selected = SLIDING.selectMessages(allMessages, 2000, estimator);
 * }</pre>
 *
 * <p><b>Strategy Comparison:</b>
 * <table border="1">
 *   <caption>Comparison of context window strategies</caption>
 *   <tr>
 *     <th>Strategy</th>
 *     <th>Algorithm</th>
 *     <th>Best For</th>
 *     <th>Requires LLM</th>
 *   </tr>
 *   <tr>
 *     <td>FIXED</td>
 *     <td>Last N messages</td>
 *     <td>Short conversations</td>
 *     <td>No</td>
 *   </tr>
 *   <tr>
 *     <td>SLIDING</td>
 *     <td>Recent + important</td>
 *     <td>Long conversations</td>
 *     <td>No</td>
 *   </tr>
 *   <tr>
 *     <td>SUMMARIZED</td>
 *     <td>Recent + summary</td>
 *     <td>Very long conversations</td>
 *     <td>Yes</td>
 *   </tr>
 * </table>
 *
 * <p><b>Thread Safety:</b> All strategies are stateless and thread-safe.
 *
 * @since 0.6.0
 * @see ContextWindowStrategy
 * @see FixedWindowStrategy
 * @see SlidingWindowStrategy
 * @see SummarizationStrategy
 */
public final class ContextWindowStrategies {

    /**
     * Fixed window strategy - includes last N messages that fit in budget.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Start from most recent message</li>
     *   <li>Add messages while under token budget</li>
     *   <li>Stop when budget would be exceeded</li>
     * </ol>
     *
     * <p><b>Characteristics:</b>
     * <ul>
     *   <li><b>Pros:</b> Simple, fast, predictable</li>
     *   <li><b>Cons:</b> May lose important early context</li>
     *   <li><b>Best For:</b> Short conversations</li>
     *   <li><b>Performance:</b> O(n) time</li>
     *   <li><b>Requires LLM:</b> No</li>
     *   <li><b>Overhead:</b> 0 tokens</li>
     * </ul>
     *
     * @see FixedWindowStrategy
     */
    public static final ContextWindowStrategy FIXED = new FixedWindowStrategy();

    /**
     * Sliding window strategy - includes recent messages plus important older ones.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Always include most recent messages (last 5)</li>
     *   <li>Score older messages by importance</li>
     *   <li>Include highest scoring that fit in remaining budget</li>
     * </ol>
     *
     * <p><b>Importance Scoring:</b>
     * <ul>
     *   <li>System messages: High priority</li>
     *   <li>Messages with function calls: High priority</li>
     *   <li>Long messages: Higher priority (more information)</li>
     *   <li>User questions: Higher than assistant responses</li>
     * </ul>
     *
     * <p><b>Characteristics:</b>
     * <ul>
     *   <li><b>Pros:</b> Balances recency and importance</li>
     *   <li><b>Cons:</b> Slightly slower, gaps in conversation flow</li>
     *   <li><b>Best For:</b> Long conversations</li>
     *   <li><b>Performance:</b> O(n log n) time</li>
     *   <li><b>Requires LLM:</b> No</li>
     *   <li><b>Overhead:</b> 0 tokens</li>
     * </ul>
     *
     * @see SlidingWindowStrategy
     */
    public static final ContextWindowStrategy SLIDING = new SlidingWindowStrategy();

    /**
     * Summarized strategy - includes recent messages plus summary of old conversation.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Include most recent messages (last 10)</li>
     *   <li>If older messages exist, create summary</li>
     *   <li>Insert summary as system message</li>
     *   <li>Adjust token budget accordingly</li>
     * </ol>
     *
     * <p><b>Note:</b> Current implementation is a placeholder. Full summarization
     * will be available when integrated with {@code DefaultLLMMemoryManager}.
     *
     * <p><b>Characteristics:</b>
     * <ul>
     *   <li><b>Pros:</b> Preserves overall context, continuous narrative</li>
     *   <li><b>Cons:</b> Requires LLM call, slower (~1-2s)</li>
     *   <li><b>Best For:</b> Very long conversations</li>
     *   <li><b>Performance:</b> O(n) time + LLM call</li>
     *   <li><b>Requires LLM:</b> Yes</li>
     *   <li><b>Overhead:</b> ~200 tokens for summary</li>
     * </ul>
     *
     * @see SummarizationStrategy
     */
    public static final ContextWindowStrategy SUMMARIZED = new SummarizationStrategy();

    /**
     * Private constructor - this is a factory class with only static members.
     */
    private ContextWindowStrategies() {
        throw new AssertionError("Cannot instantiate ContextWindowStrategies");
    }

    /**
     * Get a strategy by name (case-insensitive).
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * ContextWindowStrategy strategy = ContextWindowStrategies.forName("sliding");
     * // Returns ContextWindowStrategies.SLIDING
     * }</pre>
     *
     * @param name strategy name ("fixed", "sliding", or "summarized")
     * @return the corresponding strategy
     * @throws IllegalArgumentException if name is unknown
     */
    public static ContextWindowStrategy forName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Strategy name cannot be null or empty");
        }

        return switch (name.toLowerCase().trim()) {
            case "fixed" -> FIXED;
            case "sliding" -> SLIDING;
            case "summarized", "summary" -> SUMMARIZED;
            default -> throw new IllegalArgumentException(
                "Unknown strategy: " + name +
                ". Valid options: fixed, sliding, summarized"
            );
        };
    }

    /**
     * Get all available strategies.
     *
     * @return array of all built-in strategies
     */
    public static ContextWindowStrategy[] values() {
        return new ContextWindowStrategy[] { FIXED, SLIDING, SUMMARIZED };
    }

    /**
     * Get names of all available strategies.
     *
     * @return array of strategy names
     */
    public static String[] names() {
        return new String[] { "fixed", "sliding", "summarized" };
    }
}
