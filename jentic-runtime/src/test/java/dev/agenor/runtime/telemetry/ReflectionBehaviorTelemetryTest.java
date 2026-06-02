package dev.agenor.runtime.telemetry;

import dev.agenor.core.reflection.CritiqueResult;
import dev.agenor.core.reflection.ReflectionConfig;
import dev.agenor.core.reflection.ReflectionStrategy;
import dev.agenor.core.telemetry.*;
import dev.agenor.runtime.behavior.ReflectionBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ReflectionBehavior} emits {@code reflection.iteration} spans
 * with the correct attributes ({@code reflection.iteration}, {@code reflection.score},
 * {@code reflection.accepted}) and that spans are always ended — including on error.
 */
@DisplayName("ReflectionBehavior telemetry")
class ReflectionBehaviorTelemetryTest {

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emits one span when critique accepts on the first iteration")
    void earlyStop_emitsOneSpan() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ReflectionStrategy strategy = (output, task, cfg) ->
                CompletableFuture.completedFuture(CritiqueResult.accepted(0.9));

        buildAndRun(telemetry, strategy, new ReflectionConfig(3, 0.8, null));

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("reflection.iteration");
        assertThat(span.longAttrs.get("reflection.iteration")).isEqualTo(1L);
        assertThat(span.boolAttrs.get("reflection.accepted")).isTrue();
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("emits one span per iteration when critique requests revision each time")
    void multipleIterations_emitsOneSpanEach() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        int[] callCount = {0};
        ReflectionStrategy strategy = (output, task, cfg) -> {
            callCount[0]++;
            // Accept only on the 3rd call
            if (callCount[0] < 3) {
                return CompletableFuture.completedFuture(CritiqueResult.revise("needs work", 0.5));
            }
            return CompletableFuture.completedFuture(CritiqueResult.accepted(0.9));
        };

        buildAndRun(telemetry, strategy, new ReflectionConfig(3, 0.8, null));

        assertThat(telemetry.spans).hasSize(3);
        for (int i = 0; i < 3; i++) {
            RecordingSpan span = telemetry.spans.get(i);
            assertThat(span.name).isEqualTo("reflection.iteration");
            assertThat(span.longAttrs.get("reflection.iteration")).isEqualTo((long) (i + 1));
            assertThat(span.ended).isTrue();
        }
        // Only the last iteration is accepted
        assertThat(telemetry.spans.get(0).boolAttrs.get("reflection.accepted")).isFalse();
        assertThat(telemetry.spans.get(2).boolAttrs.get("reflection.accepted")).isTrue();
    }

    @Test
    @DisplayName("span status is ERROR when revise throws; span is still ended")
    void reviseException_spanStatusError() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        // Critique requests revision (shouldRevise=true, score below threshold)
        ReflectionStrategy strategy = (output, task, cfg) ->
                CompletableFuture.completedFuture(CritiqueResult.revise("needs work", 0.5));

        ReflectionBehavior behavior = ReflectionBehavior.builder("test-rev-err")
                .task("summarize")
                .action(() -> "initial output")
                .revise((prev, feedback) -> { throw new RuntimeException("revise failed"); })
                .strategy(strategy)
                .config(new ReflectionConfig(2, 0.8, null))
                .telemetry(telemetry)
                .build();

        // BaseBehavior swallows exceptions — execute() completes normally
        behavior.execute().join();

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("reflection.iteration");
        assertThat(span.status).isEqualTo(SpanStatus.ERROR);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("score attribute is present as a string on the span")
    void span_containsScoreAttribute() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ReflectionStrategy strategy = (output, task, cfg) ->
                CompletableFuture.completedFuture(CritiqueResult.accepted(0.75));

        buildAndRun(telemetry, strategy, ReflectionConfig.defaults());

        assertThat(telemetry.spans).hasSize(1);
        assertThat(telemetry.spans.get(0).stringAttrs).containsKey("reflection.score");
        assertThat(telemetry.spans.get(0).stringAttrs.get("reflection.score")).isEqualTo("0.75");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void buildAndRun(RecordingTelemetry telemetry,
                              ReflectionStrategy strategy,
                              ReflectionConfig config) {
        ReflectionBehavior behavior = ReflectionBehavior.builder("test-reflection")
                .task("summarize")
                .action(() -> "initial output")
                .revise((prev, feedback) -> "revised: " + feedback)
                .strategy(strategy)
                .config(config)
                .telemetry(telemetry)
                .build();

        behavior.execute().join();
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
        final Map<String, String>  stringAttrs = new LinkedHashMap<>();
        final Map<String, Long>    longAttrs   = new LinkedHashMap<>();
        final Map<String, Boolean> boolAttrs   = new LinkedHashMap<>();

        RecordingSpanBuilder(String name, RecordingTelemetry telemetry) {
            this.name      = name;
            this.telemetry = telemetry;
        }

        @Override public SpanBuilder setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public SpanBuilder setAttribute(String k, long v)    { longAttrs.put(k, v);   return this; }
        @Override public SpanBuilder setAttribute(String k, boolean v) { boolAttrs.put(k, v);   return this; }

        @Override
        public Span startSpan() {
            var span = new RecordingSpan(name,
                    new LinkedHashMap<>(stringAttrs),
                    new LinkedHashMap<>(longAttrs),
                    new LinkedHashMap<>(boolAttrs));
            telemetry.spans.add(span);
            return span;
        }
    }

    static class RecordingSpan implements Span {
        final String name;
        final Map<String, String>  stringAttrs;
        final Map<String, Long>    longAttrs;
        final Map<String, Boolean> boolAttrs;
        SpanStatus status;
        boolean    ended;
        Throwable  recordedException;

        RecordingSpan(String name,
                      Map<String, String> stringAttrs,
                      Map<String, Long> longAttrs,
                      Map<String, Boolean> boolAttrs) {
            this.name        = name;
            this.stringAttrs = stringAttrs;
            this.longAttrs   = longAttrs;
            this.boolAttrs   = boolAttrs;
        }

        @Override public Span setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public Span setAttribute(String k, long v)    { longAttrs.put(k, v);   return this; }
        @Override public Span setAttribute(String k, boolean v) { boolAttrs.put(k, v);   return this; }
        @Override public Span setAttribute(String k, double v)  { return this; }
        @Override public Span recordException(Throwable t)      { recordedException = t; return this; }
        @Override public Span setStatus(SpanStatus s)           { this.status = s; return this; }
        @Override public SpanScope makeCurrent() { return () -> {}; }
        @Override public void end()                              { this.ended = true; }
    }
}
