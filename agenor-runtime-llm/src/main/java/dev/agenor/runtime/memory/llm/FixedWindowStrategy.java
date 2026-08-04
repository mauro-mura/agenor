package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.llm.ContextWindowStrategy;
import dev.agenor.core.memory.llm.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

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
 *   <li><b>Best For:</b> Short conversations, when recent context is most important</li>
 *   <li><b>Performance:</b> O(n) time, O(n) space</li>
 *   <li><b>Requires LLM:</b> No</li>
 *   <li><b>Overhead:</b> 0 tokens</li>
 * </ul>
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * ContextWindowStrategy strategy = new FixedWindowStrategy();
 *
 * List<LLMMessage> selected = strategy.selectMessages(
 *     allMessages,
 *     2000,  // Token budget
 *     estimator
 * );
 *
 * // Or use singleton from factory
 * import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.FIXED;
 * List<LLMMessage> selected = FIXED.selectMessages(allMessages, 2000, estimator);
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class is stateless and thread-safe.
 *
 * @since 0.6.0
 */
public class FixedWindowStrategy implements ContextWindowStrategy {

    /**
     * Create a new fixed window strategy instance.
     */
    public FixedWindowStrategy() {
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

        // Select from end (most recent) until budget exhausted
        List<LLMMessage> selected = new ArrayList<>();
        int currentTokens = 0;

        for (int i = allMessages.size() - 1; i >= 0; i--) {
            LLMMessage msg = allMessages.get(i);
            int msgTokens = estimator.estimateTokens(msg);

            if (currentTokens + msgTokens <= maxTokens) {
                selected.add(0, msg);  // Add at start to maintain chronological order
                currentTokens += msgTokens;
            } else {
                break;  // Budget exhausted
            }
        }

        return selected;
    }

    @Override
    public String getName() {
        return "fixed";
    }

    @Override
    public boolean requiresLLM() {
        return false;
    }

    @Override
    public int getOverheadTokens() {
        return 0;
    }
}
