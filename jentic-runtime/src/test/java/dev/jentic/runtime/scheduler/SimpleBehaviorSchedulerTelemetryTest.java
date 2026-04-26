package dev.jentic.runtime.scheduler;

import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.Span;
import dev.jentic.core.telemetry.SpanBuilder;
import dev.jentic.core.telemetry.SpanStatus;
import dev.jentic.runtime.behavior.OneShotBehavior;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link SimpleBehaviorScheduler} emits {@code behavior.execute} spans
 * with the correct attributes ({@code behavior.id}, {@code behavior.type},
 * {@code agent.id}) and that spans are always ended — including on error.
 */
@DisplayName("SimpleBehaviorScheduler telemetry")
class SimpleBehaviorSchedulerTelemetryTest {

    private RecordingTelemetry telemetry;
    private SimpleBehaviorScheduler scheduler;

    @BeforeEach
    void setUp() {
        telemetry = new RecordingTelemetry();
        scheduler = new SimpleBehaviorScheduler(1, telemetry);
        scheduler.start().join();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop().join();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("one-shot behavior emits behavior.execute span with correct attributes")
    void oneShot_emitsBehaviorExecuteSpan() throws InterruptedException {
        CountDownLatch actionLatch = new CountDownLatch(1);
        OneShotBehavior behavior = OneShotBehavior.from("my-behavior", actionLatch::countDown);

        scheduler.schedule(behavior).join();

        assertThat(actionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        // Small pause for the finally block in executeBehavior() to complete after join()
        Thread.sleep(100);

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("behavior.execute");
        assertThat(span.stringAttrs.get("behavior.id")).isEqualTo("my-behavior");
        assertThat(span.stringAttrs.get("behavior.type")).isEqualTo("ONE_SHOT");
        assertThat(span.stringAttrs.get("agent.id")).isEqualTo("unknown"); // no agent attached
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("behavior.duration_ms attribute is present on the span")
    void oneShot_spanContainsDurationMs() throws InterruptedException {
        CountDownLatch actionLatch = new CountDownLatch(1);
        OneShotBehavior behavior = OneShotBehavior.from("timed-behavior", actionLatch::countDown);

        scheduler.schedule(behavior).join();

        assertThat(actionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);

        assertThat(telemetry.spans).hasSize(1);
        assertThat(telemetry.spans.get(0).longAttrs).containsKey("behavior.duration_ms");
        assertThat(telemetry.spans.get(0).longAttrs.get("behavior.duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("span status is OK even when action throws — BaseBehavior swallows exceptions internally")
    void behaviorThrows_schedulerSpanStillOk() throws InterruptedException {
        // BaseBehavior.execute() catches all Throwables and completes the CompletableFuture
        // normally, so the scheduler always sees a successful future and sets status=OK.
        CountDownLatch errorLatch = new CountDownLatch(1);
        OneShotBehavior behavior = OneShotBehavior.from("failing-behavior", () -> {
            errorLatch.countDown();
            throw new RuntimeException("behavior action failed");
        });

        scheduler.schedule(behavior).join();

        assertThat(errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("behavior.execute");
        // The scheduler span is always OK because BaseBehavior swallows the exception.
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    static class RecordingTelemetry implements JenticTelemetry {
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
        @Override public Span recordException(Throwable t)      { return this; }
        @Override public Span setStatus(SpanStatus s)           { this.status = s; return this; }
        @Override public void end()                              { this.ended = true; }
    }
}
