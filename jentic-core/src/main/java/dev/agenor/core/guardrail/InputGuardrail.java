package dev.agenor.core.guardrail;

import java.util.concurrent.CompletableFuture;

/**
 * Validates or transforms user input before it reaches the LLM.
 *
 * <p>This is a functional interface: implementations can be supplied as lambdas or
 * method references when a one-liner is sufficient.
 *
 * <p>Example — simple length check:
 * <pre>{@code
 * InputGuardrail lengthCheck = (input, ctx) ->
 *     CompletableFuture.completedFuture(
 *         input.length() > 4096
 *             ? new GuardrailResult.Blocked("Input exceeds maximum length")
 *             : new GuardrailResult.Passed());
 * }</pre>
 *
 * @see OutputGuardrail
 * @see GuardrailResult
 * @see GuardrailContext
 * @since 0.13.0
 */
@FunctionalInterface
public interface InputGuardrail {

    /**
     * Evaluates {@code input} and returns the guardrail result.
     *
     * @param input the raw user input string; never {@code null}
     * @param ctx   contextual metadata for the current agent invocation; never {@code null}
     * @return a future that resolves to a {@link GuardrailResult}; never {@code null}
     */
    CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx);
}
