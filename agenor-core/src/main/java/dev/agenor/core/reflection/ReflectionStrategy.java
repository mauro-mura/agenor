package dev.agenor.core.reflection;

import java.util.concurrent.CompletableFuture;

/**
 * Strategy for critiquing an LLM-generated output and deciding whether revision is needed.
 *
 * <p>Implementations evaluate the quality of {@code originalOutput} with respect to
 * the given {@code task} and return a {@link CritiqueResult} that drives the
 * Generate → Critique → Revise loop in {@code ReflectionBehavior}.
 *
 * <p>This is a functional interface: single-method implementations can be supplied
 * as lambdas or method references.
 *
 * <p>Example:
 * <pre>{@code
 * ReflectionStrategy strategy = (output, task, config) ->
 *     CompletableFuture.completedFuture(
 *         new CritiqueResult("Looks good", false, 0.95));
 * }</pre>
 *
 * @see CritiqueResult
 * @see ReflectionConfig
 *
 * @since 0.12.0
 */
@FunctionalInterface
public interface ReflectionStrategy {

    /**
     * Critiques {@code originalOutput} in the context of {@code task}.
     *
     * @param originalOutput the text produced by the agent that should be evaluated (non-null)
     * @param task           the original task or prompt that produced the output (non-null)
     * @param config         reflection parameters controlling thresholds and iteration limits (non-null)
     * @return a future that resolves to a {@link CritiqueResult}; never {@code null}
     */
    CompletableFuture<CritiqueResult> critique(
            String originalOutput,
            String task,
            ReflectionConfig config);
}
