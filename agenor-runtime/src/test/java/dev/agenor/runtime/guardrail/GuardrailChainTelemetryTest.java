package dev.agenor.runtime.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.telemetry.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link GuardrailChain} emits telemetry spans for input and
 * output guardrail evaluation, and records the correct decision attributes.
 */
@DisplayName("GuardrailChain telemetry")
class GuardrailChainTelemetryTest {

    private static final GuardrailContext CTX = GuardrailContext.of("test-agent", "test");

    private RecordingTelemetry telemetry;

    @BeforeEach
    void setUp() {
        telemetry = new RecordingTelemetry();
    }

    // -------------------------------------------------------------------------
    // Input guardrail — passed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyInput emits a span with direction=input and decision=passed")
    void applyInput_passed_emitsSpan() {
        GuardrailChain chain = GuardrailChain.builder()
                .addInput((input, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Passed()))
                .telemetry(telemetry)
                .build();

        chain.applyInput("safe input", CTX);

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("guardrail.evaluate");
        assertThat(span.stringAttrs.get("guardrail.direction")).isEqualTo("input");
        assertThat(span.stringAttrs.get("guardrail.decision")).isEqualTo("passed");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    // -------------------------------------------------------------------------
    // Output guardrail — passed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyOutput emits a span with direction=output and decision=passed")
    void applyOutput_passed_emitsSpan() {
        GuardrailChain chain = GuardrailChain.builder()
                .addOutput((output, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Passed()))
                .telemetry(telemetry)
                .build();

        chain.applyOutput("safe output", CTX);

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.stringAttrs.get("guardrail.direction")).isEqualTo("output");
        assertThat(span.stringAttrs.get("guardrail.decision")).isEqualTo("passed");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
    }

    // -------------------------------------------------------------------------
    // Input guardrail — blocked
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyInput blocked emits a span with decision=blocked and the guardrail name")
    void applyInput_blocked_emitsSpanWithBlockedDecision() {
        GuardrailChain chain = GuardrailChain.builder()
                .addInput((input, ctx) -> CompletableFuture.completedFuture(
                        new GuardrailResult.Blocked("contains PII")))
                .telemetry(telemetry)
                .build();

        assertThatThrownBy(() -> chain.applyInput("my-ssn", CTX))
                .isInstanceOf(GuardrailViolationException.class);

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.stringAttrs.get("guardrail.decision")).isEqualTo("blocked");
        assertThat(span.stringAttrs).containsKey("guardrail.name");
        assertThat(span.status).isEqualTo(SpanStatus.ERROR);
        assertThat(span.ended).isTrue();
    }

    // -------------------------------------------------------------------------
    // setTelemetry post-construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setTelemetry injects telemetry after construction")
    void setTelemetry_worksPostConstruction() {
        GuardrailChain chain = GuardrailChain.builder()
                .addInput((input, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Passed()))
                .build(); // built without telemetry

        chain.setTelemetry(telemetry);
        chain.applyInput("hello", CTX);

        assertThat(telemetry.spans).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Empty chain (no guardrails)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty chain still emits a span")
    void emptyChain_emitsSpan() {
        GuardrailChain chain = GuardrailChain.builder()
                .telemetry(telemetry)
                .build();

        chain.applyInput("anything", CTX);

        assertThat(telemetry.spans).hasSize(1);
        assertThat(telemetry.spans.get(0).ended).isTrue();
    }

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    static class RecordingTelemetry implements AgenorTelemetry {
        final List<RecordingSpan> spans = new ArrayList<>();

        @Override
        public SpanBuilder spanBuilder(String name) {
            return new RecordingSpanBuilder(name, this);
        }
    }

    static class RecordingSpanBuilder implements SpanBuilder {
        private final String name;
        private final RecordingTelemetry telemetry;
        private final java.util.Map<String, String> stringAttrs = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Long> longAttrs   = new java.util.LinkedHashMap<>();

        RecordingSpanBuilder(String name, RecordingTelemetry telemetry) {
            this.name = name;
            this.telemetry = telemetry;
        }

        @Override
        public SpanBuilder setAttribute(String key, String value) {
            stringAttrs.put(key, value);
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, long value) {
            longAttrs.put(key, value);
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, boolean value) {
            return this;
        }

        @Override
        public Span startSpan() {
            RecordingSpan span = new RecordingSpan(name, new java.util.LinkedHashMap<>(stringAttrs));
            telemetry.spans.add(span);
            return span;
        }
    }

    static class RecordingSpan implements Span {
        final String name;
        final java.util.Map<String, String> stringAttrs;
        SpanStatus status;
        boolean ended;

        RecordingSpan(String name, java.util.Map<String, String> attrs) {
            this.name = name;
            this.stringAttrs = attrs;
        }

        @Override
        public Span setAttribute(String key, String value) {
            stringAttrs.put(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, long value) { return this; }

        @Override
        public Span setAttribute(String key, boolean value) { return this; }

        @Override
        public Span setAttribute(String key, double value) { return this; }

        @Override
        public Span recordException(Throwable t) { return this; }

        @Override
        public Span setStatus(SpanStatus s) { this.status = s; return this; }

        @Override
        public SpanScope makeCurrent() { return () -> {}; }

        @Override
        public void end() { this.ended = true; }
    }
}
