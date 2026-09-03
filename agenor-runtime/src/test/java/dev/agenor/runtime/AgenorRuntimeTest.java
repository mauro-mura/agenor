package dev.agenor.runtime;

import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgenorConfiguration;
import dev.agenor.core.Behavior;
import dev.agenor.core.Message;
import dev.agenor.core.deadletter.DeadLetterQueue;
import dev.agenor.core.messaging.LocalEndpointProvider;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.config.ConfigurationException;
import dev.agenor.core.llm.LLMMemoryAware;
import dev.agenor.core.memory.llm.LLMMemoryManager;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.deadletter.InMemoryDeadLetterQueue;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgenorRuntimeTest {

    // Most tests create their own runtime inline to keep them self-contained.
    // Tests that need start()/stop() use a shared instance managed by @AfterEach.
    private AgenorRuntime runtimeUnderTest;

    @AfterEach
    void stopRuntime() {
        if (runtimeUnderTest != null && runtimeUnderTest.isRunning()) {
            runtimeUnderTest.stop().join();
        }
    }

    // ========== CREATION ==========

    @Test
    void shouldCreateRuntimeWithDefaults() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        assertThat(runtime).isNotNull();
        assertThat(runtime.isRunning()).isFalse();
        assertThat(runtime.getAgents()).isEmpty();
    }

    // ========== REGISTER / FIND ==========

    @Test
    void shouldRegisterAgents() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");

        runtime.registerAgent(agent1);
        runtime.registerAgent(agent2);

        Collection<dev.agenor.core.Agent> agents = runtime.getAgents();
        assertThat(agents).hasSize(2)
                          .containsExactlyInAnyOrder(agent1, agent2);
    }

    @Test
    void shouldFindAgentById() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        TestAgent agent = new TestAgent("test-agent", "Test Agent");
        runtime.registerAgent(agent);

        var found    = runtime.getAgent("test-agent");
        var notFound = runtime.getAgent("non-existent");

        assertThat(found).isPresent().contains(agent);
        assertThat(notFound).isEmpty();
    }

    @Test
    void registerAgent_shouldThrowForNullAgent() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        assertThatThrownBy(() -> runtime.registerAgent(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Agent cannot be null");
    }

    @Test
    void registerAgent_shouldGenerateRandomIdWhenAgentIdIsNullOrBlank() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        // BaseAgent no-arg constructor leaves agentId unset
        NoIdAgent agent = new NoIdAgent();

        assertThatCode(() -> runtime.registerAgent(agent)).doesNotThrowAnyException();
        assertThat(runtime.getAgents()).hasSize(1);
    }

    // ========== START / STOP ==========

    @Test
    void shouldStartAndStopRuntime() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        TestAgent agent = new TestAgent("test-agent", "Test Agent");
        runtimeUnderTest.registerAgent(agent);

        runtimeUnderTest.start().join();
        assertThat(runtimeUnderTest.isRunning()).isTrue();
        assertThat(agent.isRunning()).isTrue();

        runtimeUnderTest.stop().join();
        assertThat(runtimeUnderTest.isRunning()).isFalse();
        assertThat(agent.isRunning()).isFalse();
    }

    @Test
    void start_shouldBeIdempotentWhenAlreadyRunning() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        runtimeUnderTest.start().join();

        // The second start must return immediately without error or state change
        assertThatCode(() -> runtimeUnderTest.start().join()).doesNotThrowAnyException();
        assertThat(runtimeUnderTest.isRunning()).isTrue();
    }

    @Test
    void stop_shouldBeIdempotentWhenNotRunning() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        assertThat(runtimeUnderTest.isRunning()).isFalse();

        assertThatCode(() -> runtimeUnderTest.stop().join()).doesNotThrowAnyException();
        assertThat(runtimeUnderTest.isRunning()).isFalse();
    }

    @Test
    void stop_shouldBeIdempotentAfterAlreadyStopped() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        runtimeUnderTest.start().join();
        runtimeUnderTest.stop().join();

        assertThatCode(() -> runtimeUnderTest.stop().join()).doesNotThrowAnyException();
        assertThat(runtimeUnderTest.isRunning()).isFalse();
    }

    // ========== UNREGISTER ==========

    @Test
    void unregisterAgent_shouldRemoveAgentFromRuntime() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        TestAgent agent = new TestAgent("agent-1", "Agent 1");
        runtime.registerAgent(agent);

        runtime.unregisterAgent("agent-1").join();

        assertThat(runtime.getAgents()).isEmpty();
        assertThat(runtime.getAgent("agent-1")).isEmpty();
    }

    @Test
    void unregisterAgent_shouldStopRunningAgentBeforeRemoving() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        TestAgent agent = new TestAgent("agent-1", "Agent 1");
        runtimeUnderTest.registerAgent(agent);
        runtimeUnderTest.start().join();
        assertThat(agent.isRunning()).isTrue();

        runtimeUnderTest.unregisterAgent("agent-1").join();

        assertThat(agent.isRunning()).isFalse();
        assertThat(runtimeUnderTest.getAgent("agent-1")).isEmpty();
    }

    @Test
    void unregisterAgent_shouldHandleNonExistentAgentGracefully() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        assertThatCode(() -> runtime.unregisterAgent("does-not-exist").join())
            .doesNotThrowAnyException();
    }

    // ========== GETTERS ==========

    @Test
    void shouldExposeCorrectServices() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        assertThat(runtime.getAgentDirectory()).isNotNull();
        assertThat(runtime.getMessageDispatcher()).isNotNull();
        assertThat(runtime.getBehaviorScheduler()).isNotNull();
        assertThat(runtime.getLifecycleManager()).isNotNull();
        assertThat(runtime.getConfiguration()).isNotNull();
    }

    // ========== STATS ==========

    @Test
    void getStats_shouldReflectNoRunningAgentsBeforeStart() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new TestAgent("a1", "Agent 1"));

        AgenorRuntime.RuntimeStats stats = runtime.getStats();

        assertThat(stats.totalAgents()).isEqualTo(1);
        assertThat(stats.runningAgents()).isEqualTo(0);
    }

    @Test
    void getStats_shouldReturnZerosWhenEmpty() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        AgenorRuntime.RuntimeStats stats = runtime.getStats();

        assertThat(stats.totalAgents()).isEqualTo(0);
        assertThat(stats.runningAgents()).isEqualTo(0);
    }

    // ========== RuntimeStats record ==========

    @Test
    void runtimeStats_toString_shouldContainKeyValues() {
        AgenorRuntime.RuntimeStats stats = new AgenorRuntime.RuntimeStats(5, 3, 2, 1);
        String str = stats.toString();
        assertThat(str).contains("5").contains("3").contains("RuntimeStats");
    }

    @Test
    void runtimeStats_accessors_shouldReturnCorrectValues() {
        AgenorRuntime.RuntimeStats stats = new AgenorRuntime.RuntimeStats(10, 7, 3, 4);
        assertThat(stats.totalAgents()).isEqualTo(10);
        assertThat(stats.runningAgents()).isEqualTo(7);
        assertThat(stats.scannedPackages()).isEqualTo(3);
        assertThat(stats.registeredServices()).isEqualTo(4);
    }

    // ========== BUILDER OPTIONS ==========

    @Test
    void builder_shouldAcceptCustomAgentRegistry() {
        InMemoryAgentDirectory dir = new InMemoryAgentDirectory();
        AgenorRuntime r = AgenorRuntime.builder().agentRegistry(dir).build();
        assertThat(r.getAgentDirectory()).isNotNull();
    }

    @Test
    void builder_shouldAcceptCustomBehaviorScheduler() {
        SimpleBehaviorScheduler scheduler = new SimpleBehaviorScheduler();
        AgenorRuntime r = AgenorRuntime.builder().behaviorScheduler(scheduler).build();
        assertThat(r.getBehaviorScheduler()).isSameAs(scheduler);
    }

    @Test
    void builder_shouldAcceptScanPackagesVarargs() {
        AgenorRuntime r = AgenorRuntime.builder()
            .scanPackages("com.example.a", "com.example.b")
            .build();
        assertThat(r.getStats().scannedPackages()).isEqualTo(2);
    }

    @Test
    void builder_shouldAcceptScanPackagesCollection() {
        AgenorRuntime r = AgenorRuntime.builder()
            .scanPackages(List.of("com.example.a", "com.example.b", "com.example.c"))
            .build();
        assertThat(r.getStats().scannedPackages()).isEqualTo(3);
    }

    @Test
    void builder_scanPackage_shouldIgnoreNullAndBlankPackages() {
        AgenorRuntime r = AgenorRuntime.builder()
            .scanPackage(null)
            .scanPackage("   ")
            .build();
        assertThat(r.getStats().scannedPackages()).isEqualTo(0);
    }

    @Test
    void builder_shouldRegisterServices() {
        SampleService svc = new SampleService();
        AgenorRuntime r = AgenorRuntime.builder()
            .service(SampleService.class, svc)
            .build();
        assertThat(r.getStats().registeredServices()).isEqualTo(1);
    }

    @Test
    void builder_service_shouldIgnoreNullValues() {
        AgenorRuntime r = AgenorRuntime.builder()
            .service(null, null)
            .build();
        assertThat(r.getStats().registeredServices()).isEqualTo(0);
    }

    @Test
    void builder_shouldAcceptLlmMemoryManagerFactory() {
        AgenorRuntime r = AgenorRuntime.builder()
            .llmMemoryManagerFactory(agentId -> null)
            .build();
        assertThat(r).isNotNull();
    }

    @Test
    void builder_withConfiguration_shouldUseProvidedConfig() {
        AgenorConfiguration config = AgenorConfiguration.defaults();
        AgenorRuntime r = AgenorRuntime.builder().withConfiguration(config).build();
        assertThat(r.getConfiguration()).isEqualTo(config);
    }

    @Test
    void builder_withDefaultConfig_shouldNotThrow() {
        assertThatCode(() -> AgenorRuntime.builder().withDefaultConfig().build())
            .doesNotThrowAnyException();
    }

    @Test
    void getStats_shouldReflectScanPackagesFromConfiguration() {
        AgenorConfiguration config = new AgenorConfiguration(
                new AgenorConfiguration.RuntimeConfig("test-runtime", "development", null),
                new AgenorConfiguration.AgentsConfig(
                        true,
                        null,
                        null,
                        List.of("dev.agenor.runtime.discovery"),
                        null
                ),
                null,
                null,
                null
        );

        AgenorRuntime runtime = AgenorRuntime.builder()
                .withConfiguration(config)
                .build();

        AgenorRuntime.RuntimeStats stats = runtime.getStats();

        assertThat(stats.scannedPackages()).isEqualTo(1);
    }

    @Test
    void builder_withDefaultConfig_shouldThrowWhenLoadedConfigIsInvalid() throws Exception {

        // Build a config with an empty runtime name (violates validation rule).
        var badRuntime = new AgenorConfiguration.RuntimeConfig("", "development", Map.of());
        var badConfig  = new AgenorConfiguration(badRuntime, null, null, null, null);

        // withConfiguration must now throw instead of silently accepting.
        assertThatThrownBy(() -> AgenorRuntime.builder().withConfiguration(badConfig))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("runtime.name");
    }

    @Test
    void withConfiguration_shouldThrowWhenConfigIsNull() {
        assertThatThrownBy(() -> AgenorRuntime.builder().withConfiguration(null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void withConfiguration_shouldThrowWhenConfigIsInvalid() {
        // runtime.name blank → validation must fail
        var badRuntime = new AgenorConfiguration.RuntimeConfig("   ", "development", Map.of());
        var badConfig  = new AgenorConfiguration(badRuntime, null, null, null, null);

        assertThatThrownBy(() -> AgenorRuntime.builder().withConfiguration(badConfig))
                .isInstanceOf(ConfigurationException.class);
    }

    // ========== LLM MEMORY INJECTION ==========

    @Test
    void registerAgent_shouldInjectLLMMemoryManagerIntoLLMMemoryAwareBaseAgent() {
        LLMMemoryManager manager = mock(LLMMemoryManager.class);
        var agent = new LLMMemoryAwareTestAgent("llm-aware-agent", "LLM Aware Agent");
        AgenorRuntime runtime = AgenorRuntime.builder()
            .llmMemoryManagerFactory(agentId -> manager)
            .build();

        runtime.registerAgent(agent);

        assertThat(agent.getReceivedManager()).isSameAs(manager);
    }

    @Test
    void registerAgent_shouldInjectLLMMemoryManagerEvenWithoutMemoryStore() {
        // Regression: before the fix, injection was gated behind a memoryStore null-check
        LLMMemoryManager manager = mock(LLMMemoryManager.class);
        var agent = new LLMMemoryAwareTestAgent("llm-no-store", "LLM No Store");
        AgenorRuntime runtime = AgenorRuntime.builder()
            .llmMemoryManagerFactory(agentId -> manager)
            // no memoryStore configured
            .build();

        runtime.registerAgent(agent);

        assertThat(agent.getReceivedManager()).isSameAs(manager);
    }

    @Test
    void registerAgent_shouldInjectLLMMemoryManagerIntoPlainLLMMemoryAwareAgent() {
        // Regression: before the fix, only BaseAgent subclasses were wired
        LLMMemoryManager manager = mock(LLMMemoryManager.class);
        var agent = new PlainLLMMemoryAwareAgent("plain-llm-agent");
        AgenorRuntime runtime = AgenorRuntime.builder()
            .llmMemoryManagerFactory(agentId -> manager)
            .build();

        runtime.registerAgent(agent);

        assertThat(agent.getReceivedManager()).isSameAs(manager);
    }

    @Test
    void registerAgent_shouldNotInjectLLMMemoryManagerWhenFactoryIsAbsent() {
        var agent = new LLMMemoryAwareTestAgent("no-factory-agent", "No Factory");
        AgenorRuntime runtime = AgenorRuntime.builder()
            // no llmMemoryManagerFactory configured
            .build();

        assertThatCode(() -> runtime.registerAgent(agent)).doesNotThrowAnyException();
        assertThat(agent.getReceivedManager()).isNull();
    }

    // ========== DIRECT MESSAGE DISPATCH INTEGRATION ==========

    @Test
    void directMessage_shouldBeReceivedByAgentWhenSentViaGetMessageDispatcher() throws Exception {
        runtimeUnderTest = AgenorRuntime.builder().build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        DirectMessageAgent agent = new DirectMessageAgent("direct-agent", latch, received);
        runtimeUnderTest.registerAgent(agent);
        runtimeUnderTest.start().join();

        runtimeUnderTest.getMessageDispatcher().sendTo(
                Message.builder().receiverId("direct-agent").content("direct-hello").build());

        assertThat(latch.await(2, TimeUnit.SECONDS))
                .as("agent should receive direct message sent via getMessageDispatcher().sendTo()")
                .isTrue();
        assertThat(received.get()).isEqualTo("direct-hello");
    }

    // ========== MULTI-INSTANCE AGENT ROUTING ==========

    /**
     * Regression: @Agent("worker") annotation value was used as the descriptor agentId for
     * ALL instances of the class, so resolveEndpoint("worker-1") returned empty and sendTo never
     * delivered. The descriptor agentId must come from getAgentId(), not the annotation.
     * Same root cause: autoSubscribeDirectMessages() used the internal UUID field instead of
     * getAgentId(), so even if resolution worked the subscription key was wrong.
     */
    @Test
    void multiInstanceAgent_withOverriddenGetAgentId_receivesDirectMessageByLogicalId() throws Exception {
        runtimeUnderTest = AgenorRuntime.builder().build();
        CountDownLatch w1Latch = new CountDownLatch(1);
        CountDownLatch w2Latch = new CountDownLatch(1);
        AtomicReference<String> w1Content = new AtomicReference<>();
        AtomicReference<String> w2Content = new AtomicReference<>();

        MultiInstanceWorker worker1 = new MultiInstanceWorker("worker-1", w1Latch, w1Content);
        MultiInstanceWorker worker2 = new MultiInstanceWorker("worker-2", w2Latch, w2Content);
        runtimeUnderTest.registerAgent(worker1);
        runtimeUnderTest.registerAgent(worker2);
        runtimeUnderTest.start().join();

        runtimeUnderTest.getMessageDispatcher()
                .sendTo(Message.builder().receiverId("worker-1").content("task-for-1").build())
                .join();

        assertThat(w1Latch.await(2, TimeUnit.SECONDS))
                .as("worker-1 must receive the message addressed to it")
                .isTrue();
        assertThat(w1Content.get()).isEqualTo("task-for-1");
        assertThat(w2Latch.getCount())
                .as("worker-2 must NOT receive the message addressed to worker-1")
                .isEqualTo(1);
    }

    // ========== REQUEST-REPLY / MESSAGE DISPATCH INTEGRATION ==========
    // Moved from AgenorRuntimeScanningIntegrationTest (ADR-027 amendment): these use
    // @AgenorMessageHandler on manually-registerAgent(Agent)'d instances, with zero
    // classpath scanning involved, so they have no scanning-module dependency and now
    // belong here — this is also the regression-test proof that annotation processing
    // no longer silently no-ops when agenor-runtime-scanning is absent.

    /**
     * Correct agent-to-agent request-reply: the requester (a registered agent) subscribes for direct
     * replies via subscribeRecipient(getAgentId()), publishes the request, then awaits the reply.
     * The responder can sendTo(requester agentId) because it is registered in the AgentDirectory.
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

    // ========== BEHAVIOR ANNOTATION / OPTIONAL-MODULE FAILURE ==========

    /**
     * agenor-runtime's test classpath genuinely has no agenor-runtime-ext (this module
     * doesn't depend on it), so this proves an ext-only {@code @Behavior} type fails
     * start() loudly rather than being silently skipped (ADR-027 amendment).
     */
    @Test
    void start_shouldFailWhenExtOnlyBehaviorTypeUsedWithoutAgenorRuntimeExt() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        runtimeUnderTest.registerAgent(new FsmWithoutExtensionAgent());

        assertThatThrownBy(() -> runtimeUnderTest.start().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasStackTraceContaining("agenor-runtime-ext");
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

    /** BaseAgent with no ID set — runtime must generate a random one. */
    static class NoIdAgent extends BaseAgent {
        NoIdAgent() {
            super();
        }
    }

    static class SampleService {}

    static class LLMMemoryAwareTestAgent extends BaseAgent implements LLMMemoryAware {
        private LLMMemoryManager receivedManager;

        LLMMemoryAwareTestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }

        @Override
        public void setLLMMemoryManager(LLMMemoryManager manager) {
            this.receivedManager = manager;
        }

        public LLMMemoryManager getReceivedManager() {
            return receivedManager;
        }
    }

    @Agent("plain-llm-agent")
    static class PlainLLMMemoryAwareAgent implements dev.agenor.core.Agent, LLMMemoryAware {
        private final String agentId;
        private LLMMemoryManager receivedManager;

        PlainLLMMemoryAwareAgent(String agentId) {
            this.agentId = agentId;
        }

        @Override public String getAgentId() { return agentId; }
        @Override public String getAgentName() { return agentId; }
        @Override public boolean isRunning() { return false; }
        @Override public CompletableFuture<Void> start() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> stop() { return CompletableFuture.completedFuture(null); }
        @Override public void addBehavior(Behavior behavior) {}
        @Override public void removeBehavior(String behaviorId) {}
        @Override public dev.agenor.core.messaging.MessageDispatcher getMessageDispatcher() { return null; }

        @Override
        public void setLLMMemoryManager(LLMMemoryManager manager) {
            this.receivedManager = manager;
        }

        public LLMMemoryManager getReceivedManager() {
            return receivedManager;
        }
    }

    @Agent("direct-message-agent")
    static class DirectMessageAgent extends BaseAgent {
        private final CountDownLatch latch;
        private final AtomicReference<String> captured;

        DirectMessageAgent(String agentId, CountDownLatch latch, AtomicReference<String> captured) {
            super(agentId, agentId);
            this.latch = latch;
            this.captured = captured;
        }

        @Override
        protected void handleDirectMessage(Message message) {
            captured.set(message.getContent(String.class));
            latch.countDown();
        }
    }

    /** Multi-instance worker: @Agent annotation holds the class-level type label ("worker"),
     *  while getAgentId() returns the instance-specific logical ID. Uses the no-arg super()
     *  constructor so that BaseAgent.agentId = UUID (simulating the ContractNetExample pattern). */
    @Agent("worker")
    static class MultiInstanceWorker extends BaseAgent {
        private final String instanceId;
        private final CountDownLatch latch;
        private final AtomicReference<String> captured;

        MultiInstanceWorker(String instanceId, CountDownLatch latch, AtomicReference<String> captured) {
            super(); // BaseAgent.agentId = UUID; getAgentId() overridden below
            this.instanceId = instanceId;
            this.latch = latch;
            this.captured = captured;
        }

        @Override public String getAgentId() { return instanceId; }
        @Override public String getAgentName() { return "Worker " + instanceId; }

        @Override
        protected void handleDirectMessage(Message message) {
            captured.set(message.getContent(String.class));
            latch.countDown();
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

            getMessageDispatcher()
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

    // ========== ADVERTISED ENDPOINT (LocalEndpointProvider) ==========

    @Test
    void shouldAdvertiseTheDispatchersEndpointWhenItDeclaresOne() {
        // Given a dispatcher that knows how its node is reached — the shape of a networked
        // transport such as Redis
        var advertised = new AgentEndpoint("node-7", "redis", Map.of());
        var directory = new InMemoryAgentDirectory();
        AgenorRuntime runtime = AgenorRuntime.builder()
                .messageDispatcher(new EndpointAdvertisingDispatcher(directory, advertised))
                .agentRegistry(directory)
                .agentResolver(directory)
                .build();

        // When an agent registers
        runtime.registerAgent(new TestAgent("agent-1", "Agent 1"));

        // Then the directory carries that endpoint, so a peer resolving this agent learns which
        // node owns it. Without this the JDBC registry stores an empty node id and cross-node
        // delivery fails silently.
        var stored = directory.findById("agent-1").join().orElseThrow();
        assertThat(stored.endpoint()).isEqualTo(advertised);
    }

    @Test
    void shouldLeaveTheDirectoryDefaultWhenTheDispatcherDeclaresNothing() {
        // Given the default in-memory dispatcher, which has no node identity to publish
        var directory = new InMemoryAgentDirectory();
        AgenorRuntime runtime = AgenorRuntime.builder()
                .agentRegistry(directory)
                .agentResolver(directory)
                .build();

        runtime.registerAgent(new TestAgent("agent-1", "Agent 1"));

        // Then the directory applies its own local-endpoint default, exactly as before
        var stored = directory.findById("agent-1").join().orElseThrow();
        assertThat(stored.endpoint()).isNotNull();
        assertThat(stored.endpoint().transportType()).isEqualTo("local");
    }

    @Test
    void shouldKeepTheAdvertisedEndpointAfterTheAgentStarts() {
        // Given an agent registered with an endpoint-advertising dispatcher
        var advertised = new AgentEndpoint("node-7", "redis", Map.of());
        var directory = new InMemoryAgentDirectory();
        runtimeUnderTest = AgenorRuntime.builder()
                .messageDispatcher(new EndpointAdvertisingDispatcher(directory, advertised))
                .agentRegistry(directory)
                .agentResolver(directory)
                .build();
        runtimeUnderTest.registerAgent(new TestAgent("agent-1", "Agent 1"));

        // When the runtime starts — BaseAgent re-registers itself as RUNNING at that point
        runtimeUnderTest.start().join();

        // Then the endpoint is still there. Asserting only after registerAgent() is what let a
        // start() that rebuilt the descriptor without the endpoint go unnoticed: the directory
        // row reverted to a blank node id and cross-node delivery went silently nowhere.
        var stored = directory.findById("agent-1").join().orElseThrow();
        assertThat(stored.endpoint()).isEqualTo(advertised);
    }

    @Test
    void shouldGiveTheAgentTheDescriptorItWillReRegisterWith() {
        // Given an agent whose metadata comes from its @Agent annotation
        var directory = new InMemoryAgentDirectory();
        runtimeUnderTest = AgenorRuntime.builder()
                .agentRegistry(directory)
                .agentResolver(directory)
                .build();
        runtimeUnderTest.registerAgent(new AnnotatedTypeAgent());

        // When it starts and re-registers from its own descriptor
        runtimeUnderTest.start().join();

        // Then the annotation-derived type and capabilities survive. An agent left holding the
        // bare default it builds in its constructor overwrites them with its class name.
        var stored = directory.findById("annotated-agent").join().orElseThrow();
        assertThat(stored.agentType()).isEqualTo("billing");
        assertThat(stored.capabilities()).containsExactly("invoicing");
    }

    /** Agent whose type and capabilities exist only in the annotation, not in the constructor. */
    @Agent(value = "annotated-agent", type = "billing", capabilities = {"invoicing"})
    static class AnnotatedTypeAgent extends BaseAgent {
        AnnotatedTypeAgent() {
            super("annotated-agent", "Annotated Agent");
        }
    }

    /** Minimal dispatcher that advertises a fixed endpoint; only registration is exercised. */
    static class EndpointAdvertisingDispatcher extends InMemoryMessageDispatcher
            implements LocalEndpointProvider {

        private final AgentEndpoint endpoint;

        EndpointAdvertisingDispatcher(InMemoryAgentDirectory resolver, AgentEndpoint endpoint) {
            super(resolver);
            this.endpoint = endpoint;
        }

        @Override
        public Optional<AgentEndpoint> localEndpoint() {
            return Optional.of(endpoint);
        }
    }

    /** No agenor-runtime-ext on this module's test classpath — FSM must fail start() loudly. */
    static class FsmWithoutExtensionAgent extends BaseAgent {
        FsmWithoutExtensionAgent() {
            super("fsm-no-ext-runtime-test", "FSM Without Extension");
        }

        @dev.agenor.core.annotations.Behavior(type = dev.agenor.core.BehaviorType.FSM, fsmInitialState = "IDLE")
        public void fsmAction() {
        }
    }

    // -------------------------------------------------------------------------
    // Dead-letter queue (0.32.0)
    // -------------------------------------------------------------------------

    @Test
    void shouldDefaultToABoundedInMemoryDeadLetterQueue() {
        runtimeUnderTest = AgenorRuntime.builder().build();

        assertThat(runtimeUnderTest.getDeadLetterQueue())
                .isInstanceOf(InMemoryDeadLetterQueue.class);
        assertThat(runtimeUnderTest.getDeadLetterQueue().recent(10)).isEmpty();
    }

    @Test
    void shouldWireItsOwnQueueIntoTheDispatcherItBuilt() throws Exception {
        var queue = new InMemoryDeadLetterQueue(8);
        runtimeUnderTest = AgenorRuntime.builder().deadLetterQueue(queue).build();

        assertThat(runtimeUnderTest.getDeadLetterQueue()).isSameAs(queue);

        // The wiring is what matters: a failure on the dispatcher the runtime assembled must
        // reach the queue the runtime hands out, without the caller connecting the two.
        runtimeUnderTest.getMessageDispatcher().subscribeTopic("orders", msg -> {
            throw new IllegalStateException("boom");
        });
        runtimeUnderTest.getMessageDispatcher()
                .publish(Message.builder().topic("orders").content("x").build()).join();

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queue.recent(1).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(queue.recent(1)).hasSize(1);
    }

    @Test
    void shouldLeaveASuppliedDispatcherAloneWhenItIsNotTheInMemoryOne() {
        // A dispatcher the runtime did not build cannot be wired blindly: the runtime says so
        // in the builder javadoc, and this pins that it does not try.
        var dispatcher = mock(dev.agenor.core.messaging.MessageDispatcher.class);
        runtimeUnderTest = AgenorRuntime.builder().messageDispatcher(dispatcher).build();

        assertThat(runtimeUnderTest.getDeadLetterQueue()).isNotSameAs(DeadLetterQueue.noop());
        assertThat(runtimeUnderTest.getMessageDispatcher()).isSameAs(dispatcher);
    }
}
