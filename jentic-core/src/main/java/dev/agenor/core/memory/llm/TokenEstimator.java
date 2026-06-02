package dev.agenor.core.memory.llm;

import dev.agenor.core.llm.LLMMessage;

import java.util.List;

/**
 * Estimates token counts for text and messages.
 *
 * <p>Token estimation is critical for managing LLM context windows and costs.
 * This interface provides methods to estimate tokens for various content types.
 *
 * <p><b>Implementation Note:</b> Token counting varies by model family:
 * <ul>
 *   <li><b>GPT models:</b> Use tiktoken encoding</li>
 *   <li><b>Claude models:</b> Use similar encoding</li>
 *   <li><b>Other models:</b> May use different tokenizers</li>
 * </ul>
 *
 * <p>Implementations should provide reasonably accurate estimates (±5%) for
 * the models they support. Perfect accuracy is not required since estimates
 * are used for budgeting, not billing.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * TokenEstimator estimator = new SimpleTokenEstimator();
 *
 * // Estimate tokens for text
 * int tokens = estimator.estimateTokens("Hello, world!");  // ~3 tokens
 *
 * // Estimate tokens for message
 * LLMMessage msg = LLMMessage.user("What's the weather?");
 * int msgTokens = estimator.estimateTokens(msg);  // ~8 tokens (includes overhead)
 *
 * // Estimate tokens for conversation
 * List<LLMMessage> history = List.of(
 *     LLMMessage.system("You are helpful"),
 *     LLMMessage.user("Hi"),
 *     LLMMessage.assistant("Hello!")
 * );
 * int totalTokens = estimator.estimateTokens(history);  // ~15 tokens
 * }</pre>
 *
 * <p><b>Thread Safety:</b> Implementations must be thread-safe.
 *
 * @since 0.6.0
 */
public interface TokenEstimator {

    /**
     * Estimate token count for plain text.
     *
     * <p>This is the most basic estimation method. Other methods build on this.
     *
     * <p><b>Estimation Rules (typical):</b>
     * <ul>
     *   <li>1 token ≈ 4 characters for English</li>
     *   <li>1 token ≈ ¾ words for English</li>
     *   <li>Punctuation usually counts as tokens</li>
     *   <li>Whitespace may or may not count</li>
     * </ul>
     *
     * @param text the text to estimate (may be null)
     * @return estimated token count (0 if text is null or empty)
     */
    int estimateTokens(String text);

    /**
     * Estimate token count for an LLM message.
     *
     * <p>Messages have overhead beyond just the content:
     * <ul>
     *   <li>Role identifier (system, user, assistant)</li>
     *   <li>Message formatting tokens</li>
     *   <li>Function call structure (if present)</li>
     * </ul>
     *
     * <p><b>Typical Overhead:</b>
     * <ul>
     *   <li>Simple message: +3-5 tokens</li>
     *   <li>Function call: +10-20 tokens</li>
     *   <li>Function result: +5-10 tokens</li>
     * </ul>
     *
     * @param message the message to estimate (may be null)
     * @return estimated token count including overhead (0 if null)
     */
    int estimateTokens(LLMMessage message);

    /**
     * Estimate token count for a list of messages.
     *
     * <p>This includes:
     * <ul>
     *   <li>Token count for each message</li>
     *   <li>Per-message overhead</li>
     *   <li>Conversation structure overhead</li>
     * </ul>
     *
     * <p><b>Note:</b> The total is usually slightly more than the sum
     * of individual messages due to conversation formatting.
     *
     * @param messages the messages to estimate (may be null or empty)
     * @return estimated total token count (0 if null or empty)
     */
    int estimateTokens(List<LLMMessage> messages);

    /**
     * Get the maximum context window size for a model.
     *
     * <p>Context window is the maximum total tokens (prompt + completion)
     * that a model can handle in a single request.
     *
     * <p><b>Common Limits:</b>
     * <ul>
     *   <li>gpt-3.5-turbo: 16,385 tokens</li>
     *   <li>gpt-4: 8,192 tokens</li>
     *   <li>gpt-4-turbo: 128,000 tokens</li>
     *   <li>claude-3-opus: 200,000 tokens</li>
     *   <li>claude-3-sonnet: 200,000 tokens</li>
     * </ul>
     *
     * @param model the model identifier (e.g., "gpt-4", "claude-3-opus")
     * @return context window size in tokens, or -1 if unknown
     * @throws IllegalArgumentException if model is null
     */
    int getContextWindowSize(String model);

    /**
     * Check if a model supports a given token count.
     *
     * <p>Convenience method equivalent to:
     * <pre>{@code
     * tokenCount <= getContextWindowSize(model)
     * }</pre>
     *
     * @param model the model identifier
     * @param tokenCount the token count to check
     * @return true if model can handle the token count
     * @throws IllegalArgumentException if model is null
     */
    default boolean fitsInContextWindow(String model, int tokenCount) {
        int windowSize = getContextWindowSize(model);
        return windowSize < 0 || tokenCount <= windowSize;
    }

    /**
     * Calculate tokens remaining in context window.
     *
     * <p>Returns how many tokens are available for completion given
     * the prompt size.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * int promptTokens = 1000;
     * int remaining = estimator.getRemainingTokens("gpt-4", promptTokens);
     * // remaining = 7,192 (8192 - 1000)
     * }</pre>
     *
     * @param model the model identifier
     * @param usedTokens tokens already used in prompt
     * @return tokens remaining, or -1 if window size unknown
     * @throws IllegalArgumentException if model is null or usedTokens {@literal <} 0
     */
    default int getRemainingTokens(String model, int usedTokens) {
        if (usedTokens < 0) {
            throw new IllegalArgumentException("usedTokens cannot be negative");
        }
        int windowSize = getContextWindowSize(model);
        return windowSize < 0 ? -1 : Math.max(0, windowSize - usedTokens);
    }

    /**
     * Get the estimator name for logging and debugging.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"SimpleTokenEstimator" - Simple character-based estimation</li>
     *   <li>"TiktokenEstimator" - OpenAI tiktoken-based estimation</li>
     *   <li>"ClaudeEstimator" - Anthropic-specific estimation</li>
     * </ul>
     *
     * @return estimator name
     */
    String getName();

    /**
     * Get accuracy information about this estimator.
     *
     * <p>Describes the expected accuracy level:
     * <ul>
     *   <li>"exact" - Uses actual tokenizer (tiktoken, etc.)</li>
     *   <li>"high" - ±5% accuracy</li>
     *   <li>"medium" - ±10% accuracy</li>
     *   <li>"low" - ±20% accuracy (simple heuristics)</li>
     * </ul>
     *
     * @return accuracy level description
     */
    default String getAccuracyLevel() {
        return "medium";
    }
}
