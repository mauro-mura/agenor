package dev.agenor.runtime.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.InputGuardrail;
import dev.agenor.core.memory.llm.TokenEstimator;
import dev.agenor.runtime.memory.llm.SimpleTokenEstimator;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Enforces a maximum token budget on user input before it reaches the LLM.
 *
 * <p>If the estimated token count is within budget → {@link GuardrailResult.Passed}.
 * If it exceeds the limit → {@link GuardrailResult.Modified} with the truncated content.
 *
 * <p>Three {@link TruncationStrategy} values control which part of the input is preserved:
 * <ul>
 *   <li>{@link TruncationStrategy#END}    (default) — keep the beginning, drop the tail.</li>
 *   <li>{@link TruncationStrategy#START}             — keep the end, drop the head.</li>
 *   <li>{@link TruncationStrategy#MIDDLE}            — keep beginning and end, drop the centre.</li>
 * </ul>
 *
 * <p>The character budget for truncation is derived from the token limit using the same
 * {@link TokenEstimator} that evaluates the input, ensuring consistency.
 *
 * <p>Example:
 * <pre>{@code
 * // Default: END strategy, SimpleTokenEstimator
 * InputGuardrail g = new MaxTokensInputGuardrail(512);
 *
 * // Custom strategy and estimator
 * InputGuardrail g = new MaxTokensInputGuardrail(
 *         1024, TruncationStrategy.MIDDLE, myEstimator);
 * }</pre>
 *
 * @since 0.13.0
 */
public class MaxTokensInputGuardrail implements InputGuardrail {

    /**
     * Controls which portion of the input is preserved when truncation is needed.
     */
    public enum TruncationStrategy {
        /** Keep the beginning of the input, drop the tail. */
        END,
        /** Keep the end of the input, drop the head. */
        START,
        /** Keep equal portions at the beginning and end, drop the centre. */
        MIDDLE
    }

    private final int maxTokens;
    private final TruncationStrategy strategy;
    private final TokenEstimator estimator;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a guardrail with the {@link TruncationStrategy#END} strategy and
     * a {@link SimpleTokenEstimator}.
     *
     * @param maxTokens maximum allowed tokens; must be > 0
     */
    public MaxTokensInputGuardrail(int maxTokens) {
        this(maxTokens, TruncationStrategy.END, new SimpleTokenEstimator());
    }

    /**
     * Creates a guardrail with an explicit truncation strategy and a
     * {@link SimpleTokenEstimator}.
     *
     * @param maxTokens maximum allowed tokens; must be > 0
     * @param strategy  truncation strategy; never {@code null}
     */
    public MaxTokensInputGuardrail(int maxTokens, TruncationStrategy strategy) {
        this(maxTokens, strategy, new SimpleTokenEstimator());
    }

    /**
     * Creates a guardrail with explicit strategy and token estimator.
     *
     * @param maxTokens maximum allowed tokens; must be > 0
     * @param strategy  truncation strategy; never {@code null}
     * @param estimator token estimator to use; never {@code null}
     */
    public MaxTokensInputGuardrail(int maxTokens, TruncationStrategy strategy,
                                   TokenEstimator estimator) {
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
        this.maxTokens = maxTokens;
        this.strategy  = Objects.requireNonNull(strategy,  "strategy must not be null");
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
    }

    // -------------------------------------------------------------------------
    // InputGuardrail
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
        return CompletableFuture.completedFuture(evaluate(input));
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    GuardrailResult evaluate(String input) {
        if (input == null || input.isEmpty()) {
            return new GuardrailResult.Passed();
        }

        int tokens = estimator.estimateTokens(input);
        if (tokens <= maxTokens) {
            return new GuardrailResult.Passed();
        }

        String truncated = truncate(input, maxTokens, strategy, estimator);
        return new GuardrailResult.Modified(truncated);
    }

    // -------------------------------------------------------------------------
    // Truncation
    // -------------------------------------------------------------------------

    /**
     * Truncates {@code input} so that its estimated token count is ≤ {@code maxTokens}.
     *
     * <p>The character budget is computed as {@code maxTokens * charsPerToken}, where
     * {@code charsPerToken} is back-calculated from the estimator by comparing
     * {@code estimator.estimateTokens(input)} against {@code input.length()}.
     * This makes truncation consistent with the estimator in use.
     */
    static String truncate(String input, int maxTokens,
                           TruncationStrategy strategy, TokenEstimator estimator) {
        int totalTokens = estimator.estimateTokens(input);
        if (totalTokens <= maxTokens) return input;

        // Derive character budget proportionally from the estimator's own ratio
        int charBudget = (int) ((long) input.length() * maxTokens / totalTokens);
        charBudget = Math.max(1, Math.min(charBudget, input.length() - 1));

        return switch (strategy) {
            case END    -> input.substring(0, charBudget);
            case START  -> input.substring(input.length() - charBudget);
            case MIDDLE -> {
                int half  = charBudget / 2;
                int start = half;
                int end   = input.length() - (charBudget - half);
                // If end <= start after rounding, fall back to END strategy
                if (end <= start) yield input.substring(0, charBudget);
                yield input.substring(0, start) + input.substring(end);
            }
        };
    }

    public int maxTokens()           { return maxTokens; }
    public TruncationStrategy strategy() { return strategy; }
}
