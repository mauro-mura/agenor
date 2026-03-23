package dev.jentic.core.guardrail;

/**
 * Result produced by a guardrail evaluation.
 *
 * <p>Three mutually exclusive outcomes are modeled as records inside this sealed
 * interface, enabling exhaustive {@code switch} expressions with full compiler
 * enforcement (Java 21+):
 *
 * <pre>{@code
 * GuardrailResult result = guardrail.apply(input, ctx).join();
 * String output = switch (result) {
 *     case GuardrailResult.Passed p   -> input;
 *     case GuardrailResult.Modified m -> m.newContent();
 *     case GuardrailResult.Blocked  b -> throw new GuardrailViolationException(b.reason(), "...");
 * };
 * }</pre>
 *
 * @see InputGuardrail
 * @see OutputGuardrail
 * @since 0.13.0
 */
public sealed interface GuardrailResult
        permits GuardrailResult.Passed,
                GuardrailResult.Modified,
                GuardrailResult.Blocked {

    /**
     * The content passed validation without modification.
     */
    record Passed() implements GuardrailResult {}

    /**
     * The guardrail transformed the content.
     * The chain continues with {@link #newContent()} as the new input/output value.
     *
     * @param newContent the transformed content; never {@code null}
     */
    record Modified(String newContent) implements GuardrailResult {
        public Modified {
            if (newContent == null) throw new IllegalArgumentException("newContent must not be null");
        }
    }

    /**
     * The guardrail rejected the content.
     * The chain short-circuits and {@link GuardrailViolationException} is thrown.
     *
     * @param reason human-readable explanation of why the content was blocked; never {@code null}
     */
    record Blocked(String reason) implements GuardrailResult {
        public Blocked {
            if (reason == null) throw new IllegalArgumentException("reason must not be null");
        }
    }
}