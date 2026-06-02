package dev.agenor.runtime.telemetry;

import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalNotifier;
import dev.agenor.core.hitl.ApprovalTimeoutException;
import dev.agenor.core.telemetry.*;
import dev.agenor.runtime.behavior.advanced.HumanCheckpointBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link HumanCheckpointBehavior} emits {@code hitl.approval} spans
 * with the correct attributes and status codes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HumanCheckpointBehavior telemetry")
class HumanCheckpointBehaviorTelemetryTest {

    @Mock
    private ApprovalGate gate;

    @Mock
    private ApprovalNotifier notifier;

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("approved decision emits hitl.approval span with OK status and decision attribute")
    void approved_emitsSpanWithOkStatus() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        when(gate.requestApproval(any()))
                .thenReturn(CompletableFuture.completedFuture(new ApprovalDecision.Approved()));

        HumanCheckpointBehavior<String> behavior = new HumanCheckpointBehavior<>(
                "hitl-approved",
                gate,
                notifier,
                "payload",
                "process-payment",
                Duration.ofMinutes(1),
                decision -> {},
                telemetry
        );

        behavior.execute().join();

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("hitl.approval");
        assertThat(span.stringAttrs).containsKey("hitl.request_id");
        assertThat(span.stringAttrs.get("hitl.action")).isEqualTo("process-payment");
        assertThat(span.stringAttrs.get("hitl.decision")).isEqualTo("Approved");
        assertThat(span.longAttrs).containsKey("hitl.wait_ms");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("rejected decision emits hitl.approval span with OK status and Rejected attribute")
    void rejected_emitsSpanWithDecisionRejected() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        when(gate.requestApproval(any()))
                .thenReturn(CompletableFuture.completedFuture(new ApprovalDecision.Rejected("too risky")));

        HumanCheckpointBehavior<String> behavior = new HumanCheckpointBehavior<>(
                "hitl-rejected",
                gate,
                notifier,
                "payload",
                "delete-account",
                Duration.ofMinutes(1),
                decision -> {},
                telemetry
        );

        behavior.execute().join();

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.stringAttrs.get("hitl.decision")).isEqualTo("Rejected");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("timeout exception emits hitl.approval span with ERROR status; span still ended")
    void timeout_emitsSpanWithErrorStatus() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ApprovalTimeoutException timeout =
                new ApprovalTimeoutException("req-1", Instant.now().plusSeconds(60));
        when(gate.requestApproval(any()))
                .thenReturn(CompletableFuture.failedFuture(timeout));

        HumanCheckpointBehavior<String> behavior = new HumanCheckpointBehavior<>(
                "hitl-timeout",
                gate,
                notifier,
                "payload",
                "risky-op",
                Duration.ofMillis(1),  // very short timeout
                decision -> {},
                telemetry
        );

        // BaseBehavior swallows exceptions — execute() completes normally
        behavior.execute().join();

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("hitl.approval");
        assertThat(span.status).isEqualTo(SpanStatus.ERROR);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("span contains hitl.request_id and hitl.action as builder attributes")
    void span_containsRequestIdAndAction() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        when(gate.requestApproval(any()))
                .thenReturn(CompletableFuture.completedFuture(new ApprovalDecision.Approved()));

        HumanCheckpointBehavior<String> behavior = new HumanCheckpointBehavior<>(
                "hitl-attrs",
                gate,
                notifier,
                "payload",
                "send-wire-transfer",
                Duration.ofMinutes(1),
                decision -> {},
                telemetry
        );

        behavior.execute().join();

        RecordingSpan span = telemetry.spans.get(0);
        // request_id is a UUID string — just verify it is non-blank
        assertThat(span.stringAttrs.get("hitl.request_id")).isNotBlank();
        assertThat(span.stringAttrs.get("hitl.action")).isEqualTo("send-wire-transfer");
    }

    @Test
    @DisplayName("noop telemetry (default constructor) does not throw")
    void defaultConstructor_noopTelemetry_doesNotThrow() {
        when(gate.requestApproval(any()))
                .thenReturn(CompletableFuture.completedFuture(new ApprovalDecision.Approved()));

        HumanCheckpointBehavior<String> behavior = new HumanCheckpointBehavior<>(
                "hitl-noop",
                gate,
                notifier,
                "payload",
                "noop-action",
                Duration.ofMinutes(1),
                decision -> {}
        );

        // Must not throw even without a custom telemetry instance
        behavior.execute().join();
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
        @Override public SpanScope makeCurrent() { return () -> {}; }
        @Override public void end()                              { this.ended = true; }
    }
}
