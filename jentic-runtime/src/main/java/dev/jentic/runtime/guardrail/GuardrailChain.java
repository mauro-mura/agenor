package dev.jentic.runtime.guardrail;

import dev.jentic.core.guardrail.GuardrailContext;
import dev.jentic.core.guardrail.GuardrailResult;
import dev.jentic.core.guardrail.GuardrailViolationException;
import dev.jentic.core.guardrail.InputGuardrail;
import dev.jentic.core.guardrail.OutputGuardrail;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.Span;
import dev.jentic.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered chain of {@link InputGuardrail}s and {@link OutputGuardrail}s executed
 * sequentially inside the {@code LLMAgent} pipeline.
 *
 * <p>Execution semantics:
 * <ul>
 *   <li>{@link GuardrailResult.Passed}  — content unchanged, next guardrail receives same value.</li>
 *   <li>{@link GuardrailResult.Modified} — content replaced, chain continues with the new value.</li>
 *   <li>{@link GuardrailResult.Blocked}  — chain short-circuits, {@link GuardrailViolationException} thrown.</li>
 * </ul>
 *
 * <p>Instances are <strong>thread-safe</strong>: the internal lists are frozen at
 * {@link Builder#build()} time and the {@code applyInput}/{@code applyOutput} methods
 * carry no mutable state.
 *
 * <p>Usage:
 * <pre>{@code
 * GuardrailChain chain = GuardrailChain.builder()
 *     .addInput(new PiiRedactionGuardrail())
 *     .addInput(new MaxTokensInputGuardrail(4096))
 *     .addOutput(new ContentPolicyGuardrail("/etc/jentic/policy.yaml"))
 *     .build();
 *
 * // In LLMAgent:
 * String safeInput  = chain.applyInput(rawInput, ctx);
 * String safeOutput = chain.applyOutput(llmResponse, ctx);
 * }</pre>
 *
 * @see InputGuardrail
 * @see OutputGuardrail
 * @since 0.13.0
 */
public final class GuardrailChain {

    private static final Logger log = LoggerFactory.getLogger(GuardrailChain.class);

    private final List<InputGuardrail>  inputGuardrails;
    private final List<OutputGuardrail> outputGuardrails;
    private volatile JenticTelemetry telemetry;

    private GuardrailChain(Builder builder) {
        this.inputGuardrails  = Collections.unmodifiableList(new ArrayList<>(builder.inputGuardrails));
        this.outputGuardrails = Collections.unmodifiableList(new ArrayList<>(builder.outputGuardrails));
        this.telemetry        = builder.telemetry;
    }

    /**
     * Sets the telemetry instance used to emit {@code guardrail.evaluate} spans.
     * Can be called after construction (e.g. by {@code JenticRuntime} after registration).
     *
     * @param telemetry the telemetry instance; {@code null} is treated as noop
     * @since 0.19.0
     */
    public void setTelemetry(JenticTelemetry telemetry) {
        this.telemetry = telemetry != null ? telemetry : JenticTelemetry.noop();
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /** Returns a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Runs all input guardrails sequentially against {@code input}.
     *
     * @param input the raw user input; never {@code null}
     * @param ctx   guardrail context for the current invocation; never {@code null}
     * @return the (possibly modified) content after passing all guardrails
     * @throws GuardrailViolationException if any guardrail returns {@link GuardrailResult.Blocked}
     */
    public String applyInput(String input, GuardrailContext ctx) throws GuardrailViolationException {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(ctx,   "ctx must not be null");
        Span span = telemetry.spanBuilder("guardrail.evaluate")
                .setAttribute("guardrail.direction", "input")
                .startSpan();
        try {
            String result = executeChain(input, ctx, inputGuardrails, "input");
            span.setAttribute("guardrail.decision", "passed").setStatus(SpanStatus.OK);
            return result;
        } catch (GuardrailViolationException e) {
            span.setAttribute("guardrail.decision", "blocked")
                .setAttribute("guardrail.name", e.blockedBy())
                .setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Runs all output guardrails sequentially against {@code output}.
     *
     * @param output the raw LLM output; never {@code null}
     * @param ctx    guardrail context for the current invocation; never {@code null}
     * @return the (possibly modified) content after passing all guardrails
     * @throws GuardrailViolationException if any guardrail returns {@link GuardrailResult.Blocked}
     */
    public String applyOutput(String output, GuardrailContext ctx) throws GuardrailViolationException {
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(ctx,    "ctx must not be null");
        Span span = telemetry.spanBuilder("guardrail.evaluate")
                .setAttribute("guardrail.direction", "output")
                .startSpan();
        try {
            String result = executeChain(output, ctx, outputGuardrails, "output");
            span.setAttribute("guardrail.decision", "passed").setStatus(SpanStatus.OK);
            return result;
        } catch (GuardrailViolationException e) {
            span.setAttribute("guardrail.decision", "blocked")
                .setAttribute("guardrail.name", e.blockedBy())
                .setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Returns {@code true} if no input guardrails are registered.
     */
    public boolean hasNoInputGuardrails() {
        return inputGuardrails.isEmpty();
    }

    /**
     * Returns {@code true} if no output guardrails are registered.
     */
    public boolean hasNoOutputGuardrails() {
        return outputGuardrails.isEmpty();
    }

    /**
     * Returns an unmodifiable view of the input guardrail list.
     * Used by {@link dev.jentic.runtime.guardrail.GuardrailAnnotationProcessor} for chain merging.
     */
    public List<InputGuardrail> inputGuardrails() {
        return inputGuardrails;
    }

    /**
     * Returns an unmodifiable view of the output guardrail list.
     * Used by {@link dev.jentic.runtime.guardrail.GuardrailAnnotationProcessor} for chain merging.
     */
    public List<OutputGuardrail> outputGuardrails() {
        return outputGuardrails;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Generic sequential execution for both input and output guardrail lists.
     * The {@code direction} parameter is used only for logging.
     */
    private <G> String executeChain(
            String content,
            GuardrailContext ctx,
            List<G> guardrails,
            String direction) throws GuardrailViolationException {

        String current = content;

        for (G guardrail : guardrails) {
            GuardrailResult result = evaluate(guardrail, current, ctx, direction);

            switch (result) {
                case GuardrailResult.Passed ignored -> {
                    log.trace("[guardrail:{}] {} passed by {}", direction, truncate(current), guardrailName(guardrail));
                }
                case GuardrailResult.Modified m -> {
                    log.debug("[guardrail:{}] content modified by {}", direction, guardrailName(guardrail));
                    current = m.newContent();
                }
                case GuardrailResult.Blocked b -> {
                    log.warn("[guardrail:{}] content blocked by {}: {}", direction, guardrailName(guardrail), b.reason());
                    throw new GuardrailViolationException(b.reason(), guardrailName(guardrail));
                }
            }
        }

        return current;
    }

    private GuardrailResult evaluate(Object guardrail, String content, GuardrailContext ctx, String direction) {
        try {
            return switch (direction) {
                case "input"  -> ((InputGuardrail)  guardrail).apply(content, ctx).join();
                case "output" -> ((OutputGuardrail) guardrail).apply(content, ctx).join();
                default -> throw new IllegalStateException("Unknown direction: " + direction);
            };
        } catch (GuardrailViolationException e) {
            throw e;
        } catch (Exception e) {
            throw new GuardrailViolationException(
                    "Guardrail threw unexpected exception: " + e.getMessage(),
                    guardrailName(guardrail), e);
        }
    }

    private static String guardrailName(Object guardrail) {
        return guardrail.getClass().getName();
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link GuardrailChain}.
     *
     * <p>Builder instances are <strong>not thread-safe</strong>; build and share
     * the resulting {@link GuardrailChain} instead.
     */
    public static final class Builder {

        private final List<InputGuardrail>  inputGuardrails  = new ArrayList<>();
        private final List<OutputGuardrail> outputGuardrails = new ArrayList<>();
        private JenticTelemetry telemetry = JenticTelemetry.noop();

        private Builder() {}

        /**
         * Sets the telemetry instance that will emit {@code guardrail.evaluate} spans.
         *
         * @param telemetry the telemetry instance; {@code null} uses noop
         * @return {@code this}
         * @since 0.19.0
         */
        public Builder telemetry(JenticTelemetry telemetry) {
            this.telemetry = telemetry != null ? telemetry : JenticTelemetry.noop();
            return this;
        }

        /**
         * Appends an input guardrail to the chain.
         *
         * @param guardrail the guardrail to add; never {@code null}
         */
        public Builder addInput(InputGuardrail guardrail) {
            inputGuardrails.add(Objects.requireNonNull(guardrail, "guardrail must not be null"));
            return this;
        }

        /**
         * Appends an output guardrail to the chain.
         *
         * @param guardrail the guardrail to add; never {@code null}
         */
        public Builder addOutput(OutputGuardrail guardrail) {
            outputGuardrails.add(Objects.requireNonNull(guardrail, "guardrail must not be null"));
            return this;
        }

        /** Builds an immutable {@link GuardrailChain}. */
        public GuardrailChain build() {
            return new GuardrailChain(this);
        }
    }
}