package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanBuilder;
import dev.agenor.core.telemetry.SpanScope;
import dev.agenor.core.telemetry.SpanStatus;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both inbound paths of one agent, driven across a real Redis transport with the mailbox in
 * the middle (ADR-032).
 *
 * <p>The unit tests assert the mailbox routes correctly and that an agent holds one
 * subscription; the Redis dispatcher's own tests assert it delivers to every handler
 * registered for a recipient. Neither covers the seam between them, and the seam is where the
 * handler-overwrite defect lived — an annotated handler that worked in every in-memory test
 * and was dead over Redis. This drives the assembled thing: two runtimes, two node streams,
 * one agent receiving an annotated topic message and a dialogue REQUEST from the other node.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 *
 * @since 0.27.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Agent mailbox over the Redis transport — integration tests (Valkey)")
class MailboxCrossTransportIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory factory1;
    private RedisMessagingFactory factory2;
    private AgenorRuntime sender;
    private AgenorRuntime receiver;
    private MixedTrafficAgent agent;
    private RecordingTelemetry receiverTelemetry;

    @BeforeEach
    void setUp() {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);

        // One directory shared in-process: this test is about the transport seam, not about
        // directory durability — CrossRuntimeDialogueIT already covers that over PostgreSQL.
        // The dispatchers still advertise distinct node ids, so messages genuinely travel
        // through Redis streams rather than a local map.
        var directory = new InMemoryAgentDirectory();

        factory1 = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("mailbox-node-1").consumerGroupPrefix("agenor-mbx").build();
        factory2 = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("mailbox-node-2").consumerGroupPrefix("agenor-mbx").build();

        sender = AgenorRuntime.builder()
                .messageDispatcher(factory1.messageDispatcher(() -> directory))
                .agentRegistry(directory)
                .agentDiscovery(directory)
                .agentResolver(directory)
                .build();
        receiverTelemetry = new RecordingTelemetry();
        receiver = AgenorRuntime.builder()
                .messageDispatcher(factory2.messageDispatcher(() -> directory))
                .agentRegistry(directory)
                .agentDiscovery(directory)
                .agentResolver(directory)
                .telemetry(receiverTelemetry)
                .build();

        agent = new MixedTrafficAgent();
        receiver.registerAgent(agent);

        sender.start().join();
        receiver.start().join();
        awaitResolvable("mixed-traffic");
    }

    @AfterEach
    void tearDown() {
        if (sender != null && sender.isRunning()) sender.stop().join();
        if (receiver != null && receiver.isRunning()) receiver.stop().join();
        if (factory1 != null) factory1.close();
        if (factory2 != null) factory2.close();
    }

    @Test
    @DisplayName("an @AgenorMessageHandler receives a direct message sent from the other node")
    void annotatedHandlerReceivesAcrossTheTransport() {
        // When a plain direct message with the handler's topic crosses the transport
        sender.getMessageDispatcher().sendTo(Message.builder()
                .topic("task.process")
                .senderId("coordinator")
                .receiverId("mixed-traffic")
                .content("do work")
                .build());

        // Then the annotated handler runs — the assertion the overwrite defect broke
        awaitUntil(() -> agent.annotated.get() != null);
        assertThat(agent.annotated.get().content()).isEqualTo("do work");
        assertThat(agent.fallbacks.get()).isZero();
    }

    @Test
    @DisplayName("a @DialogueHandler receives a REQUEST sent from the other node")
    void dialogueHandlerReceivesAcrossTheTransport() {
        // When a dialogue REQUEST crosses the transport
        sender.getMessageDispatcher().sendTo(DialogueMessage.builder()
                .conversationId("conv-mailbox-1")
                .senderId("coordinator")
                .receiverId("mixed-traffic")
                .performative(Performative.REQUEST)
                .content("ask something")
                .build()
                .toMessage());

        // Then dialogue handled it, and it did not also fall through to the direct path
        awaitUntil(() -> agent.dialogueMessages.get() == 1);
        assertThat(agent.dialogueMessages.get()).isEqualTo(1);
        assertThat(agent.fallbacks.get()).isZero();
        assertThat(agent.annotated.get()).isNull();
    }

    @Test
    @DisplayName("both paths stay live on the same agent, through one subscription")
    void bothPathsCoexist() {
        // When both kinds arrive
        sender.getMessageDispatcher().sendTo(Message.builder()
                .topic("task.process")
                .senderId("coordinator")
                .receiverId("mixed-traffic")
                .content("do work")
                .build());
        sender.getMessageDispatcher().sendTo(DialogueMessage.builder()
                .conversationId("conv-mailbox-2")
                .senderId("coordinator")
                .receiverId("mixed-traffic")
                .performative(Performative.REQUEST)
                .content("ask something")
                .build()
                .toMessage());

        // Then neither path suppressed the other
        awaitUntil(() -> agent.annotated.get() != null && agent.dialogueMessages.get() == 1);
        assertThat(agent.annotated.get().content()).isEqualTo("do work");
        assertThat(agent.dialogueMessages.get()).isEqualTo(1);
        assertThat(agent.fallbacks.get()).isZero();
    }

    @Test
    @DisplayName("the receive span is emitted over Redis too, which is what makes tracing transport-independent")
    void agentReceiveSpanIsEmittedOverTheTransport() {
        // Before this span existed, whether an arriving message was traced depended on the
        // transport: the Redis consumer loop emitted message.receive and the in-memory
        // dispatcher emitted nothing on the receive side. The mailbox is the one point both go
        // through. The unit test proves the in-memory half; this is the other half, and
        // without it the claim would be an argument rather than a measurement.
        sender.getMessageDispatcher().sendTo(DialogueMessage.builder()
                .conversationId("conv-span-1")
                .senderId("coordinator")
                .receiverId("mixed-traffic")
                .performative(Performative.REQUEST)
                .content("trace me")
                .build()
                .toMessage());

        awaitUntil(() -> receiverTelemetry.find("agent.receive", "conversation.id", "conv-span-1") != null);

        Map<String, String> attrs = receiverTelemetry.find("agent.receive", "conversation.id", "conv-span-1");
        assertThat(attrs)
                .containsEntry("agent.id", "mixed-traffic")
                .containsEntry("agent.sender", "coordinator")
                .containsEntry("mailbox.lane", "dialogue");
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("condition never became true within " + TIMEOUT);
    }

    private void awaitResolvable(String agentId) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = sender.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Agent '" + agentId + "' never became resolvable");
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    /** One agent, both inbound paths — the arrangement the mailbox took ownership of. */
    static class MixedTrafficAgent extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicReference<Message> annotated = new AtomicReference<>();
        final AtomicInteger dialogueMessages = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();

        MixedTrafficAgent() {
            super("mixed-traffic", "Mixed Traffic");
        }

        @AgenorMessageHandler("task.process")
        public void handleTask(Message message) {
            annotated.set(message);
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) {
            dialogueMessages.incrementAndGet();
        }

        @Override
        protected void onDirectMessage(Message message) {
            fallbacks.incrementAndGet();
        }
    }

    /** Minimal recorder: enough to prove the span reached this transport, and nothing more. */
    static class RecordingTelemetry implements AgenorTelemetry {
        final List<Map<String, String>> spans = Collections.synchronizedList(new java.util.ArrayList<>());
        final List<String> names = Collections.synchronizedList(new java.util.ArrayList<>());

        Map<String, String> find(String name, String attrKey, String attrValue) {
            synchronized (spans) {
                for (int i = 0; i < spans.size(); i++) {
                    if (names.get(i).equals(name) && attrValue.equals(spans.get(i).get(attrKey))) {
                        return spans.get(i);
                    }
                }
            }
            return null;
        }

        @Override
        public SpanBuilder spanBuilder(String name) {
            Map<String, String> attrs = new LinkedHashMap<>();
            return new SpanBuilder() {
                @Override public SpanBuilder setAttribute(String k, String v)  { attrs.put(k, v); return this; }
                @Override public SpanBuilder setAttribute(String k, long v)    { return this; }
                @Override public SpanBuilder setAttribute(String k, boolean v) { return this; }

                @Override
                public Span startSpan() {
                    synchronized (spans) {
                        names.add(name);
                        spans.add(attrs);
                    }
                    return new Span() {
                        @Override public Span setAttribute(String k, String v)  { attrs.put(k, v); return this; }
                        @Override public Span setAttribute(String k, long v)    { return this; }
                        @Override public Span setAttribute(String k, boolean v) { return this; }
                        @Override public Span setAttribute(String k, double v)  { return this; }
                        @Override public Span recordException(Throwable t)      { return this; }
                        @Override public Span setStatus(SpanStatus st)          { return this; }
                        @Override public SpanScope makeCurrent() { return () -> {}; }
                        @Override public void end() { }
                    };
                }
            };
        }
    }
}
