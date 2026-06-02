package dev.agenor.core.guardrail;

import java.util.concurrent.CompletableFuture;

/**
 * Validates or transforms LLM output before it reaches the consumer.
 *
 * <p>This is a functional interface: implementations can be supplied as lambdas or
 * method references when a one-liner is sufficient.
 *
 * <p>Example — reject empty responses:
 * <pre>{@code
 * OutputGuardrail nonEmpty = (output, ctx) ->
 *     CompletableFuture.completedFuture(
 *         output.isBlank()
 *             ? new GuardrailResult.Blocked("LLM returned empty response")
 *             : new GuardrailResult.Passed());
 * }</pre>
 *
 * @see InputGuardrail
 * @see GuardrailResult
 * @see GuardrailContext
 * @since 0.13.0
 */
@FunctionalInterface
public interface OutputGuardrail {

    /**
     * Evaluates {@code output} and returns the guardrail result.
     *
     * @param output the raw LLM output string; never {@code null}
     * @param ctx    contextual metadata for the current agent invocation; never {@code null}
     * @return a future that resolves to a {@link GuardrailResult}; never {@code null}
     */
    CompletableFuture<GuardrailResult> apply(String output, GuardrailContext ctx);
}
