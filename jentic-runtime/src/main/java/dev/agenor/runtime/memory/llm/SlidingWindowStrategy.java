package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.llm.ContextWindowStrategy;
import dev.agenor.core.memory.llm.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

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
 *   <li><b>System messages:</b> +10.0 (very important)</li>
 *   <li><b>Function calls:</b> +5.0 (important context)</li>
 *   <li><b>User messages:</b> +1.0 (questions matter)</li>
 *   <li><b>Message length:</b> +0-5.0 (more information)</li>
 * </ul>
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li><b>Pros:</b> Balances recency and importance, preserves key context</li>
 *   <li><b>Cons:</b> Slightly slower, may have gaps in conversation flow</li>
 *   <li><b>Best For:</b> Long conversations where both recent and historical context matter</li>
 *   <li><b>Performance:</b> O(n log n) time (sorting), O(n) space</li>
 *   <li><b>Requires LLM:</b> No</li>
 *   <li><b>Overhead:</b> 0 tokens</li>
 * </ul>
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * ContextWindowStrategy strategy = new SlidingWindowStrategy();
 *
 * List<LLMMessage> selected = strategy.selectMessages(
 *     allMessages,
 *     2000,  // Token budget
 *     estimator
 * );
 *
 * // Or use singleton from factory
 * import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.SLIDING;
 * List<LLMMessage> selected = SLIDING.selectMessages(allMessages, 2000, estimator);
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class is stateless and thread-safe.
 *
 * @since 0.6.0
 */
public class SlidingWindowStrategy implements ContextWindowStrategy {

    /**
     * Number of recent messages to always include.
     */
    private static final int RECENT_MESSAGE_COUNT = 5;

    /**
     * Create a new sliding window strategy instance.
     */
    public SlidingWindowStrategy() {
        // Stateless - no initialization needed
    }

    @Override
    public List<LLMMessage> selectMessages(
        List<LLMMessage> allMessages,
        int maxTokens,
        TokenEstimator estimator
    ) {
        // Validate parameters
        if (allMessages == null) {
            throw new IllegalArgumentException("allMessages cannot be null");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (estimator == null) {
            throw new IllegalArgumentException("estimator cannot be null");
        }

        // Handle empty list
        if (allMessages.isEmpty()) {
            return List.of();
        }

        // If few messages, use fixed strategy
        if (allMessages.size() <= RECENT_MESSAGE_COUNT) {
            return new FixedWindowStrategy().selectMessages(allMessages, maxTokens, estimator);
        }

        List<LLMMessage> selected = new ArrayList<>();
        int currentTokens = 0;

        // 1. Always include most recent messages
        int recentStart = Math.max(0, allMessages.size() - RECENT_MESSAGE_COUNT);
        for (int i = recentStart; i < allMessages.size(); i++) {
            LLMMessage msg = allMessages.get(i);
            int msgTokens = estimator.estimateTokens(msg);
            currentTokens += msgTokens;
            selected.add(msg);
        }

        // 2. If budget allows, add important older messages
        if (currentTokens < maxTokens && recentStart > 0) {
            List<ScoredMessage> scored = new ArrayList<>();

            // Score all older messages
            for (int i = 0; i < recentStart; i++) {
                LLMMessage msg = allMessages.get(i);
                double score = scoreMessageImportance(msg);
                int tokens = estimator.estimateTokens(msg);
                scored.add(new ScoredMessage(msg, score, tokens));
            }

            // Sort by importance (descending)
            scored.sort((a, b) -> Double.compare(b.score, a.score));

            // Add highest scoring that fit in budget
            for (ScoredMessage sm : scored) {
                if (currentTokens + sm.tokens <= maxTokens) {
                    selected.add(sm.message);
                    currentTokens += sm.tokens;
                }
            }

            // Re-sort to maintain chronological order
            selected.sort((a, b) -> {
                int idxA = allMessages.indexOf(a);
                int idxB = allMessages.indexOf(b);
                return Integer.compare(idxA, idxB);
            });
        }

        return selected;
    }

    /**
     * Score a message's importance for inclusion in context.
     *
     * @param message the message to score
     * @return importance score (higher = more important)
     */
    private double scoreMessageImportance(LLMMessage message) {
        double score = 1.0;  // Base score

        // System messages are very important (setup, instructions)
        if (message.role() == LLMMessage.Role.SYSTEM) {
            score += 10.0;
        }

        // Function calls are important (actions, tool use)
        if (message.hasFunctionCalls()) {
            score += 5.0;
        }

        // User messages slightly more important than assistant
        // (user questions drive the conversation)
        if (message.role() == LLMMessage.Role.USER) {
            score += 1.0;
        }

        // Longer messages contain more information
        if (message.content() != null) {
            score += Math.min(5.0, message.content().length() / 500.0);
        }

        return score;
    }

    @Override
    public String getName() {
        return "sliding";
    }

    @Override
    public boolean requiresLLM() {
        return false;
    }

    @Override
    public int getOverheadTokens() {
        return 0;
    }

    /**
     * Internal record for scoring messages.
     */
    private record ScoredMessage(LLMMessage message, double score, int tokens) {}
}
