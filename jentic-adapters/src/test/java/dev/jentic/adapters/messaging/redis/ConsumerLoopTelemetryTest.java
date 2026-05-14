package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.Message;
import dev.jentic.core.telemetry.*;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies that {@link ConsumerLoop} emits {@code message.receive} spans with correct
 * attributes and status codes when processing stream messages.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsumerLoop telemetry")
class ConsumerLoopTelemetryTest {

    @Mock
    private RedisStreamClient streamClient;

    private RecordingTelemetry telemetry;
    private RedisMessagingConfig config;

    @BeforeEach
    void setUp() {
        telemetry = new RecordingTelemetry();
        config    = new RedisMessagingConfig(
                "redis://localhost", "node-1", "jentic",
                100, 1000, 30_000, 3);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ConsumerLoop loop(dev.jentic.core.MessageHandler handler) {
        return new ConsumerLoop(
                "jentic:topic:payments", "cg-test", "consumer-1",
                handler, streamClient, config, telemetry);
    }

    private StreamMessage<String, String> streamMsg(String id, String msgId,
                                                     String topic, String sender,
                                                     String correlationId, String content) {
        var body = MessageCodec.encode(
                Message.builder()
                        .id(msgId)
                        .topic(topic)
                        .senderId(sender)
                        .correlationId(correlationId)
                        .content(content)
                        .build());
        return new StreamMessage<>("stream", id, body);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("successful handler emits message.receive span with OK status")
    void success_emitsSpanWithOkStatus() {
        var loop = loop(msg -> CompletableFuture.completedFuture(null));

        loop.processMessage(streamMsg("1-0", "msg-1", "payments", "agent-a", "corr-1", "data"));

        assertThat(telemetry.spans).hasSize(1);
        var span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("message.receive");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("successful handler span carries all expected attributes")
    void success_spanHasCorrectAttributes() {
        var loop = loop(msg -> CompletableFuture.completedFuture(null));

        loop.processMessage(streamMsg("1-0", "msg-42", "payments", "agent-a", "corr-xyz", "payload"));

        var span = telemetry.spans.get(0);
        assertThat(span.stringAttrs.get("message.id")).isEqualTo("msg-42");
        assertThat(span.stringAttrs.get("message.topic")).isEqualTo("payments");
        assertThat(span.stringAttrs.get("agent.sender")).isEqualTo("agent-a");
        assertThat(span.stringAttrs.get("message.correlation_id")).isEqualTo("corr-xyz");
        assertThat(span.stringAttrs.get("transport.type")).isEqualTo("redis");
    }

    @Test
    @DisplayName("failed handler emits message.receive span with ERROR status; span still ended")
    void handlerFailure_emitsSpanWithErrorStatus() {
        var loop = loop(msg -> CompletableFuture.failedFuture(new RuntimeException("handler boom")));

        loop.processMessage(streamMsg("1-0", "msg-err", "payments", "agent-b", null, "bad"));

        assertThat(telemetry.spans).hasSize(1);
        var span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("message.receive");
        assertThat(span.status).isEqualTo(SpanStatus.ERROR);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("failed handler does not XACK the entry so it stays in PEL")
    void handlerFailure_doesNotXack() {
        var loop = loop(msg -> CompletableFuture.failedFuture(new RuntimeException("fail")));

        loop.processMessage(streamMsg("1-0", "msg-1", "payments", "s", null, "x"));

        verify(streamClient, never()).xack(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("successful handler XACKs the entry")
    void success_xacksEntry() {
        var loop = loop(msg -> CompletableFuture.completedFuture(null));

        loop.processMessage(streamMsg("5-3", "msg-ok", "payments", "s", null, "x"));

        verify(streamClient).xack("jentic:topic:payments", "cg-test", "5-3");
    }

    @Test
    @DisplayName("entry exceeding maxDeliveryAttempts goes to DLQ with no span emitted")
    void dlq_noSpanEmitted() {
        var loop = loop(msg -> CompletableFuture.failedFuture(new RuntimeException("fail")));

        // 3 failed attempts to exhaust maxDeliveryAttempts=3, then 4th triggers DLQ
        var msg = streamMsg("9-0", "msg-dlq", "payments", "s", null, "x");
        for (int i = 0; i < 4; i++) loop.processMessage(msg);

        // Only 3 spans — the 4th call goes straight to DLQ before reaching handler/span
        assertThat(telemetry.spans).hasSize(3);
        verify(streamClient).moveToDlq(any(), any(), any());
    }

    @Test
    @DisplayName("null optional fields produce empty string attributes, not NPE")
    void nullFields_produceEmptyAttributes() {
        // topic, senderId, correlationId are null; id is auto-generated by Message.builder()
        var body = MessageCodec.encode(
                Message.builder().content("bare").build());
        var streamMsg = new StreamMessage<String, String>("stream", "1-0", body);

        var loop = loop(msg -> CompletableFuture.completedFuture(null));
        loop.processMessage(streamMsg);

        var span = telemetry.spans.get(0);
        assertThat(span.stringAttrs.get("message.id")).isNotEmpty();       // auto-generated UUID
        assertThat(span.stringAttrs.get("message.topic")).isEmpty();
        assertThat(span.stringAttrs.get("agent.sender")).isEmpty();
        assertThat(span.stringAttrs.get("message.correlation_id")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Recording telemetry (local to this test class)
    // -------------------------------------------------------------------------

    static class RecordingTelemetry implements JenticTelemetry {
        final List<RecordingSpan> spans = new ArrayList<>();

        @Override
        public SpanBuilder spanBuilder(String name) {
            return new RecordingSpanBuilder(name, spans);
        }
    }

    static class RecordingSpan implements Span {
        final String name;
        SpanStatus status = SpanStatus.UNSET;
        boolean ended = false;
        final Map<String, String> stringAttrs = new LinkedHashMap<>();
        final Map<String, Long>   longAttrs   = new LinkedHashMap<>();

        RecordingSpan(String name) { this.name = name; }

        @Override public Span setAttribute(String k, String v)  { stringAttrs.put(k, v != null ? v : ""); return this; }
        @Override public Span setAttribute(String k, long v)    { longAttrs.put(k, v); return this; }
        @Override public Span setAttribute(String k, boolean v) { return this; }
        @Override public Span setAttribute(String k, double v) { return this; }
        @Override public Span setStatus(SpanStatus s)           { this.status = s; return this; }
        @Override public Span recordException(Throwable t)      { return this; }
        @Override public SpanScope makeCurrent() { return () -> {}; }
        @Override public void end()                             { this.ended = true; }
    }

    static class RecordingSpanBuilder implements SpanBuilder {
        private final String name;
        private final List<RecordingSpan> registry;
        private final RecordingSpan span;

        RecordingSpanBuilder(String name, List<RecordingSpan> registry) {
            this.name     = name;
            this.registry = registry;
            this.span     = new RecordingSpan(name);
        }

        @Override public SpanBuilder setAttribute(String k, String v)  { span.setAttribute(k, v); return this; }
        @Override public SpanBuilder setAttribute(String k, long v)    { span.setAttribute(k, v); return this; }
        @Override public SpanBuilder setAttribute(String k, boolean v) { return this; }

        @Override
        public Span startSpan() {
            registry.add(span);
            return span;
        }
    }
}
