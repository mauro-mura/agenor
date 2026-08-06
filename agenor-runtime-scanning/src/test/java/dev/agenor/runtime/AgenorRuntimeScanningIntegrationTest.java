package dev.agenor.runtime;

import dev.agenor.core.AgenorConfiguration;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgenorRuntime} behavior that requires {@code agenor-runtime-scanning} on the
 * classpath: {@code createAgent(Class)}, {@code scanPackage(...)}-driven discovery at
 * {@code start()}, and {@code @AgenorMessageHandler} auto-subscription (wired via
 * {@code AgentDiscoveryEngine.processAnnotations}, ADR-027 task 1.5's optional-module
 * seam).
 *
 * <p>Split out of {@code AgenorRuntimeTest} in {@code agenor-runtime} (ADR-027, task 4):
 * that suite covers {@link AgenorRuntime} behavior that works with zero optional
 * modules on the classpath; these tests only pass once {@code agenor-runtime-scanning}
 * (which provides the {@code AgentDiscoveryEngine} SPI implementation) is present —
 * {@code agenor-runtime} cannot depend on it.
 */
class AgenorRuntimeScanningIntegrationTest {

    private AgenorRuntime runtimeUnderTest;

    @AfterEach
    void stopRuntime() {
        if (runtimeUnderTest != null && runtimeUnderTest.isRunning()) {
            runtimeUnderTest.stop().join();
        }
    }

    // ========== createAgent ==========

    @Test
    void shouldCreateAgentFromClass() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        TestAgent agent = runtime.createAgent(TestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(runtime.getAgents()).contains(agent);
        assertThat(agent.getMessageDispatcher()).isNotNull();
    }

    @Test
    void createAgent_shouldProcessAnnotationsIfRuntimeAlreadyRunning() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        runtimeUnderTest.start().join();

        // Exercises the `if (running)` branch inside createAgent
        TestAgent agent = runtimeUnderTest.createAgent(TestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(runtimeUnderTest.getAgents()).contains(agent);
    }

    // ========== STATS ==========

    @Test
    void shouldGetRuntimeStats() {
        runtimeUnderTest = AgenorRuntime.builder()
            .scanPackage("com.example")
            .build();
        runtimeUnderTest.registerAgent(new TestAgent("agent-1", "Agent 1"));
        runtimeUnderTest.registerAgent(new TestAgent("agent-2", "Agent 2"));
        runtimeUnderTest.start().join();

        AgenorRuntime.RuntimeStats stats = runtimeUnderTest.getStats();

        assertThat(stats.totalAgents()).isEqualTo(2);
        assertThat(stats.runningAgents()).isEqualTo(2);
        assertThat(stats.scannedPackages()).isEqualTo(1);
        assertThat(stats.registeredServices()).isEqualTo(0);
    }

    // ========== BUILDER OPTIONS ==========

    @Test
    void shouldDiscoverAndStartAgentsFromConfigurationScanPackages() {
        AgenorConfiguration config = new AgenorConfiguration(
                new AgenorConfiguration.RuntimeConfig("test-runtime", "development", null),
                new AgenorConfiguration.AgentsConfig(
                        true,
                        null,
                        null,
                        List.of("dev.agenor.runtime"),
                        null
                ),
                null,
                null,
                null
        );

        runtimeUnderTest = AgenorRuntime.builder()
                .withConfiguration(config)
                .build();

        runtimeUnderTest.start().join();

        assertThat(runtimeUnderTest.getAgents()).isNotEmpty();
        assertThat(runtimeUnderTest.getAgents())
                .allSatisfy(agent -> assertThat(agent.isRunning()).isTrue());
    }

    // ========== REQUEST-REPLY INTEGRATION ==========

    /**
     * Correct agent-to-agent request-reply: the requester (a registered agent) subscribes for direct
     * replies via subscribeRecipient(getAgentId()), publishes the request, then awaits the reply.
     * The responder can sendTo(requester agentId) because it is registered in the AgentDirectory.
     * This is the pattern that OrderOrchestratorAgent must use after the Item 2 (0.20.0) refactor.
     */
    @Test
    void requestReply_agentToAgent_viaDispatcher_shouldDeliverAndReceiveReply() throws Exception {
        runtimeUnderTest = AgenorRuntime.builder().build();
        CountDownLatch replyLatch = new CountDownLatch(1);
        AtomicReference<Message> received = new AtomicReference<>();

        RequesterAgent requester = new RequesterAgent("rr-requester", replyLatch, received);
        EchoResponderAgent responder = new EchoResponderAgent("rr-responder-new");
        runtimeUnderTest.registerAgent(requester);
        runtimeUnderTest.registerAgent(responder);
        runtimeUnderTest.start().join();

        requester.doRequestReply();

        assertThat(replyLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().correlationId()).isEqualTo(requester.lastRequestId());
        assertThat(received.get().getContent(String.class)).isEqualTo("pong");
    }

    // ========== MESSAGE DISPATCH INTEGRATION ==========

    @Test
    void messageHandler_shouldReceiveMessagePublishedViaGetMessageDispatcher() throws Exception {
        runtimeUnderTest = AgenorRuntime.builder().build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        MessageCapturingAgent agent = new MessageCapturingAgent("capture-agent", latch, received);
        runtimeUnderTest.registerAgent(agent);
        runtimeUnderTest.start().join();

        runtimeUnderTest.getMessageDispatcher().publish(
                Message.builder().topic("capture.topic").content("hello").build());

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isEqualTo("hello");
    }

    @Test
    void getMessageDispatcher_shouldBeTheSameInstanceUsedByAgentHandlers() throws Exception {
        runtimeUnderTest = AgenorRuntime.builder().build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        MessageCapturingAgent agent = new MessageCapturingAgent("capture-agent-2", latch, received);
        runtimeUnderTest.registerAgent(agent);
        runtimeUnderTest.start().join();

        // Agent publishes back on "capture.reply" — subscribe via the same dispatcher
        AtomicReference<String> reply = new AtomicReference<>();
        CountDownLatch replyLatch = new CountDownLatch(1);
        runtimeUnderTest.getMessageDispatcher().subscribeTopic("capture.reply", msg -> {
            reply.set(msg.getContent(String.class));
            replyLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        runtimeUnderTest.getMessageDispatcher().publish(
                Message.builder().topic("capture.topic").content("ping").build());

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isEqualTo("ping");
    }

    // ========== HELPERS ==========

    static class TestAgent extends BaseAgent {
        TestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }

        public TestAgent() {
            super();
        }
    }

    @Agent("discoverable-test-agent")
    static class DiscoverableTestAgent extends BaseAgent {
        public DiscoverableTestAgent() {
            super("discoverable-test-agent", "Discoverable Test Agent");
        }
    }

    /** Sends a request via the dispatcher request-reply pattern (subscribeRecipient + publish). */
    static class RequesterAgent extends BaseAgent {
        private final CountDownLatch latch;
        private final AtomicReference<Message> received;
        private volatile String lastRequestId;

        RequesterAgent(String agentId, CountDownLatch latch, AtomicReference<Message> received) {
            super(agentId, agentId);
            this.latch = latch;
            this.received = received;
        }

        void doRequestReply() {
            Message request = Message.builder()
                    .senderId(getAgentId())
                    .topic("rr.request")
                    .content("ping")
                    .build();
            lastRequestId = request.id();

            dev.agenor.core.messaging.Subscription sub = getMessageDispatcher()
                    .subscribeRecipient(getAgentId(), msg -> {
                        if (request.id().equals(msg.correlationId())) {
                            received.set(msg);
                            latch.countDown();
                        }
                        return CompletableFuture.completedFuture(null);
                    });
            getMessageDispatcher().publish(request);
        }

        String lastRequestId() { return lastRequestId; }
    }

    /** Handles "rr.request" topic and replies via getMessageDispatcher().sendTo(). */
    @Agent("echo-responder-agent")
    static class EchoResponderAgent extends BaseAgent {
        EchoResponderAgent(String agentId) {
            super(agentId, agentId);
        }

        @AgenorMessageHandler("rr.request")
        public void onRequest(Message message) {
            Message reply = message.reply("pong")
                    .topic("rr.reply")
                    .build();
            getMessageDispatcher().sendTo(reply);
        }
    }

    @Agent("message-capturing-agent")
    static class MessageCapturingAgent extends BaseAgent {
        private final CountDownLatch latch;
        private final AtomicReference<String> captured;

        MessageCapturingAgent(String agentId, CountDownLatch latch, AtomicReference<String> captured) {
            super(agentId, agentId);
            this.latch = latch;
            this.captured = captured;
        }

        @AgenorMessageHandler("capture.topic")
        public void onMessage(Message message) {
            captured.set(message.getContent(String.class));
            latch.countDown();
        }
    }
}
