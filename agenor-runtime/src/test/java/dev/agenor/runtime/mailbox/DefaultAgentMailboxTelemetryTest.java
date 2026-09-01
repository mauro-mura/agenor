package dev.agenor.runtime.mailbox;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.mailbox.MailboxConfig;
import dev.agenor.core.telemetry.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receive-side span ADR-032 reserved the mailbox drain for, and left empty.
 *
 * <p>Why this matters beyond having a span: until it existed, whether an inbound message was
 * traced depended on the transport that delivered it. The Redis adapter emitted
 * {@code message.receive} from its consumer loop and the in-memory dispatcher emitted nothing on
 * the receive side, so the same agent code was observable on one transport and invisible on the
 * other. The mailbox is the one point both transports go through, which is why the span belongs
 * here and is asserted here.
 */
@DisplayName("DefaultAgentMailbox telemetry")
class DefaultAgentMailboxTelemetryTest {

    private RecordingTelemetry telemetry;
    private DefaultAgentMailbox mailbox;

    @BeforeEach
    void setUp() {
        telemetry = new RecordingTelemetry();
    }

    @AfterEach
    void tearDown() {
        if (mailbox != null) {
            mailbox.stop();
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("a message routed to the push lane emits agent.receive with its addressing")
    void pushLaneEmitsSpan() {
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                m -> CompletableFuture.completedFuture(null), telemetry);
        mailbox.start();

        mailbox.offer(Message.builder()
                .id("m-1").topic("orders.new").senderId("peer").receiverId("agent-a")
                .correlationId("corr-1").content("x").build()).join();

        awaitSpans(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("agent.receive");
        assertThat(span.stringAttrs)
                .containsEntry("agent.id", "agent-a")
                .containsEntry("message.id", "m-1")
                .containsEntry("message.topic", "orders.new")
                .containsEntry("agent.sender", "peer")
                .containsEntry("message.correlation_id", "corr-1")
                .containsEntry("mailbox.lane", "push")
                .containsEntry("conversation.id", "");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @Timeout(5)
    @DisplayName("a dialogue message carries its conversation id, which is what reassembles an exchange")
    void dialogueLaneCarriesTheConversationId() {
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                m -> CompletableFuture.completedFuture(null), telemetry);
        mailbox.registerDialogueConsumer(m -> CompletableFuture.completedFuture(null));
        mailbox.start();

        mailbox.offer(DialogueMessage.builder()
                .conversationId("conv-42")
                .senderId("peer").receiverId("agent-a")
                .performative(Performative.REQUEST)
                .content("do it")
                .build()
                .toMessage()).join();

        awaitSpans(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.stringAttrs)
                .containsEntry("mailbox.lane", "dialogue")
                .containsEntry("conversation.id", "conv-42");
    }

    @Test
    @Timeout(5)
    @DisplayName("a failing handler ends the span with ERROR rather than leaving it open")
    void failingHandlerRecordsError() {
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                m -> CompletableFuture.failedFuture(new IllegalStateException("boom")), telemetry);
        mailbox.start();

        mailbox.offer(Message.builder()
                .id("m-2").senderId("peer").receiverId("agent-a").content("x").build())
                .exceptionally(e -> null)
                .join();

        awaitSpans(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.status).isEqualTo(SpanStatus.ERROR);
        assertThat(span.ended).isTrue();
    }

    @Test
    @Timeout(5)
    @DisplayName("a mailbox built without telemetry keeps working")
    void noTelemetryIsFine() {
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                m -> CompletableFuture.completedFuture(null));
        mailbox.start();

        assertThat(mailbox.offer(Message.builder()
                .senderId("peer").receiverId("agent-a").content("x").build())).succeedsWithin(
                        java.time.Duration.ofSeconds(2));
    }

    private void awaitSpans(int expected) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (telemetry.spans) {
                if (telemetry.spans.size() >= expected
                        && telemetry.spans.get(expected - 1).ended) {
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("expected " + expected + " ended span(s), saw " + telemetry.spans.size());
    }

    // -------------------------------------------------------------------------
    // Recording doubles
    // -------------------------------------------------------------------------

    static class RecordingTelemetry implements AgenorTelemetry {
        final List<RecordingSpan> spans = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public SpanBuilder spanBuilder(String name) {
            return new RecordingSpanBuilder(name, this);
        }
    }

    static class RecordingSpanBuilder implements SpanBuilder {
        private final String name;
        private final RecordingTelemetry telemetry;
        final Map<String, String> stringAttrs = new LinkedHashMap<>();

        RecordingSpanBuilder(String name, RecordingTelemetry telemetry) {
            this.name = name;
            this.telemetry = telemetry;
        }

        @Override public SpanBuilder setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public SpanBuilder setAttribute(String k, long v)    { return this; }
        @Override public SpanBuilder setAttribute(String k, boolean v) { return this; }

        @Override
        public Span startSpan() {
            var span = new RecordingSpan(name, new LinkedHashMap<>(stringAttrs));
            telemetry.spans.add(span);
            return span;
        }
    }

    static class RecordingSpan implements Span {
        final String name;
        final Map<String, String> stringAttrs;
        volatile SpanStatus status;
        volatile boolean ended;

        RecordingSpan(String name, Map<String, String> stringAttrs) {
            this.name = name;
            this.stringAttrs = stringAttrs;
        }

        @Override public Span setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public Span setAttribute(String k, long v)    { return this; }
        @Override public Span setAttribute(String k, boolean v) { return this; }
        @Override public Span setAttribute(String k, double v)  { return this; }
        @Override public Span recordException(Throwable t)      { return this; }
        @Override public Span setStatus(SpanStatus s)           { this.status = s; return this; }
        @Override public SpanScope makeCurrent() { return () -> {}; }
        @Override public void end()                             { this.ended = true; }
    }
}
