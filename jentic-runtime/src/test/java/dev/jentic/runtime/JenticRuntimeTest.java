package dev.jentic.runtime;

import dev.jentic.core.Agent;
import dev.jentic.core.Behavior;
import dev.jentic.core.JenticConfiguration;
import dev.jentic.core.Message;
import dev.jentic.core.MessageService;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.core.config.ConfigurationException;
import dev.jentic.core.llm.LLMMemoryAware;
import dev.jentic.core.memory.llm.LLMMemoryManager;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.directory.LocalAgentDirectory;
import dev.jentic.runtime.messaging.InMemoryMessageService;
import dev.jentic.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class JenticRuntimeTest {

    // Most tests create their own runtime inline to keep them self-contained.
    // Tests that need start()/stop() use a shared instance managed by @AfterEach.
    private JenticRuntime runtimeUnderTest;

    @AfterEach
    void stopRuntime() {
        if (runtimeUnderTest != null && runtimeUnderTest.isRunning()) {
            runtimeUnderTest.stop().join();
        }
    }

    // ========== CREATION ==========

    @Test
    void shouldCreateRuntimeWithDefaults() {
        JenticRuntime runtime = JenticRuntime.builder().build();

        assertThat(runtime).isNotNull();
        assertThat(runtime.isRunning()).isFalse();
        assertThat(runtime.getAgents()).isEmpty();
    }

    // ========== REGISTER / FIND ==========

    @Test
    void shouldRegisterAgents() {
        JenticRuntime runtime = JenticRuntime.builder().build();
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");

        runtime.registerAgent(agent1);
        runtime.registerAgent(agent2);

        Collection<Agent> agents = runtime.getAgents();
        assertThat(agents).hasSize(2)
                          .containsExactlyInAnyOrder(agent1, agent2);
    }

    @Test
    void shouldFindAgentById() {
        JenticRuntime runtime = JenticRuntime.builder().build();
        TestAgent agent = new TestAgent("test-agent", "Test Agent");
        runtime.registerAgent(agent);

        var found    = runtime.getAgent("test-agent");
        var notFound = runtime.getAgent("non-existent");

        assertThat(found).isPresent().contains(agent);
        assertThat(notFound).isEmpty();
    }

    @Test
    void registerAgent_shouldThrowForNullAgent() {
        JenticRuntime runtime = JenticRuntime.builder().build();
        assertThatThrownBy(() -> runtime.registerAgent(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Agent cannot be null");
    }

    @Test
    void registerAgent_shouldGenerateRandomIdWhenAgentIdIsNullOrBlank() {
        JenticRuntime runtime = JenticRuntime.builder().build();
        // BaseAgent no-arg constructor leaves agentId unset
        NoIdAgent agent = new NoIdAgent();

        assertThatCode(() -> runtime.registerAgent(agent)).doesNotThrowAnyException();
        assertThat(runtime.getAgents()).hasSize(1);
    }

    // ========== START / STOP ==========

    @Test
    void shouldStartAndStopRuntime() {
        runtimeUnderTest = JenticRuntime.builder().build();
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
        runtimeUnderTest = JenticRuntime.builder().build();
        runtimeUnderTest.start().join();

        // The second start must return immediately without error or state change
        assertThatCode(() -> runtimeUnderTest.start().join()).doesNotThrowAnyException();
        assertThat(runtimeUnderTest.isRunning()).isTrue();
    }

    @Test
    void stop_shouldBeIdempotentWhenNotRunning() {
        runtimeUnderTest = JenticRuntime.builder().build();
        assertThat(runtimeUnderTest.isRunning()).isFalse();

        assertThatCode(() -> runtimeUnderTest.stop().join()).doesNotThrowAnyException();
        assertThat(runtimeUnderTest.isRunning()).isFalse();
    }

    @Test
    void stop_shouldBeIdempotentAfterAlreadyStopped() {
        runtimeUnderTest = JenticRuntime.builder().build();
        runtimeUnderTest.start().join();
        runtimeUnderTest.stop().join();

        assertThatCode(() -> runtimeUnderTest.stop().join()).doesNotThrowAnyException();
        assertThat(runtimeUnderTest.isRunning()).isFalse();
    }

    // ========== UNREGISTER ==========

    @Test
    void unregisterAgent_shouldRemoveAgentFromRuntime() {
        JenticRuntime runtime = JenticRuntime.builder().build();
        TestAgent agent = new TestAgent("agent-1", "Agent 1");
        runtime.registerAgent(agent);

        runtime.unregisterAgent("agent-1").join();

        assertThat(runtime.getAgents()).isEmpty();
        assertThat(runtime.getAgent("agent-1")).isEmpty();
    }

    @Test
    void unregisterAgent_shouldStopRunningAgentBeforeRemoving() {
        runtimeUnderTest = JenticRuntime.builder().build();
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
        JenticRuntime runtime = JenticRuntime.builder().build();
        assertThatCode(() -> runtime.unregisterAgent("does-not-exist").join())
            .doesNotThrowAnyException();
    }

    // ========== createAgent ==========

    @Test
    void shouldCreateAgentFromClass() {
        JenticRuntime runtime = JenticRuntime.builder().build();

        TestAgent agent = runtime.createAgent(TestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(runtime.getAgents()).contains(agent);
        assertThat(agent.getMessageDispatcher()).isNotNull();
    }

    @Test
    void createAgent_shouldProcessAnnotationsIfRuntimeAlreadyRunning() {
        runtimeUnderTest = JenticRuntime.builder().build();
        runtimeUnderTest.start().join();

        // Exercises the `if (running)` branch inside createAgent
        TestAgent agent = runtimeUnderTest.createAgent(TestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(runtimeUnderTest.getAgents()).contains(agent);
    }

    // ========== GETTERS ==========

    @Test
    void shouldExposeCorrectServices() {
        JenticRuntime runtime = JenticRuntime.builder().build();

        assertThat(runtime.getAgentDirectory()).isNotNull();
        assertThat(runtime.getMessageService()).isNotNull();
        assertThat(runtime.getBehaviorScheduler()).isNotNull();
        assertThat(runtime.getLifecycleManager()).isNotNull();
        assertThat(runtime.getConfiguration()).isNotNull();
    }

    // ========== STATS ==========

    @Test
    void shouldGetRuntimeStats() {
        runtimeUnderTest = JenticRuntime.builder()
            .scanPackage("com.example")
            .build();
        runtimeUnderTest.registerAgent(new TestAgent("agent-1", "Agent 1"));
        runtimeUnderTest.registerAgent(new TestAgent("agent-2", "Agent 2"));
        runtimeUnderTest.start().join();

        JenticRuntime.RuntimeStats stats = runtimeUnderTest.getStats();

        assertThat(stats.totalAgents()).isEqualTo(2);
        assertThat(stats.runningAgents()).isEqualTo(2);
        assertThat(stats.scannedPackages()).isEqualTo(1);
        assertThat(stats.registeredServices()).isEqualTo(0);
    }

    @Test
    void getStats_shouldReflectNoRunningAgentsBeforeStart() {
        JenticRuntime runtime = JenticRuntime.builder().build();
        runtime.registerAgent(new TestAgent("a1", "Agent 1"));

        JenticRuntime.RuntimeStats stats = runtime.getStats();

        assertThat(stats.totalAgents()).isEqualTo(1);
        assertThat(stats.runningAgents()).isEqualTo(0);
    }

    @Test
    void getStats_shouldReturnZerosWhenEmpty() {
        JenticRuntime runtime = JenticRuntime.builder().build();

        JenticRuntime.RuntimeStats stats = runtime.getStats();

        assertThat(stats.totalAgents()).isEqualTo(0);
        assertThat(stats.runningAgents()).isEqualTo(0);
    }

    // ========== RuntimeStats record ==========

    @Test
    void runtimeStats_toString_shouldContainKeyValues() {
        JenticRuntime.RuntimeStats stats = new JenticRuntime.RuntimeStats(5, 3, 2, 1);
        String str = stats.toString();
        assertThat(str).contains("5").contains("3").contains("RuntimeStats");
    }

    @Test
    void runtimeStats_accessors_shouldReturnCorrectValues() {
        JenticRuntime.RuntimeStats stats = new JenticRuntime.RuntimeStats(10, 7, 3, 4);
        assertThat(stats.totalAgents()).isEqualTo(10);
        assertThat(stats.runningAgents()).isEqualTo(7);
        assertThat(stats.scannedPackages()).isEqualTo(3);
        assertThat(stats.registeredServices()).isEqualTo(4);
    }

    // ========== BUILDER OPTIONS ==========

    @Test
    void builder_shouldAcceptCustomMessageService() {
        InMemoryMessageService customMs = new InMemoryMessageService();
        JenticRuntime r = JenticRuntime.builder().messageService(customMs).build();
        assertThat(r.getMessageService()).isSameAs(customMs);
    }

    @Test
    void builder_shouldAcceptCustomAgentDirectory() {
        LocalAgentDirectory dir = new LocalAgentDirectory();
        JenticRuntime r = JenticRuntime.builder().agentDirectory(dir).build();
        assertThat(r.getAgentDirectory()).isSameAs(dir);
    }

    @Test
    void builder_shouldAcceptCustomBehaviorScheduler() {
        SimpleBehaviorScheduler scheduler = new SimpleBehaviorScheduler();
        JenticRuntime r = JenticRuntime.builder().behaviorScheduler(scheduler).build();
        assertThat(r.getBehaviorScheduler()).isSameAs(scheduler);
    }

    @Test
    void builder_shouldAcceptScanPackagesVarargs() {
        JenticRuntime r = JenticRuntime.builder()
            .scanPackages("com.example.a", "com.example.b")
            .build();
        assertThat(r.getStats().scannedPackages()).isEqualTo(2);
    }

    @Test
    void builder_shouldAcceptScanPackagesCollection() {
        JenticRuntime r = JenticRuntime.builder()
            .scanPackages(List.of("com.example.a", "com.example.b", "com.example.c"))
            .build();
        assertThat(r.getStats().scannedPackages()).isEqualTo(3);
    }

    @Test
    void builder_scanPackage_shouldIgnoreNullAndBlankPackages() {
        JenticRuntime r = JenticRuntime.builder()
            .scanPackage(null)
            .scanPackage("   ")
            .build();
        assertThat(r.getStats().scannedPackages()).isEqualTo(0);
    }

    @Test
    void builder_shouldRegisterServices() {
        SampleService svc = new SampleService();
        JenticRuntime r = JenticRuntime.builder()
            .service(SampleService.class, svc)
            .build();
        assertThat(r.getStats().registeredServices()).isEqualTo(1);
    }

    @Test
    void builder_service_shouldIgnoreNullValues() {
        JenticRuntime r = JenticRuntime.builder()
            .service(null, null)
            .build();
        assertThat(r.getStats().registeredServices()).isEqualTo(0);
    }

    @Test
    void builder_shouldAcceptLlmMemoryManagerFactory() {
        JenticRuntime r = JenticRuntime.builder()
            .llmMemoryManagerFactory(agentId -> null)
            .build();
        assertThat(r).isNotNull();
    }

    @Test
    void builder_withConfiguration_shouldUseProvidedConfig() {
        JenticConfiguration config = JenticConfiguration.defaults();
        JenticRuntime r = JenticRuntime.builder().withConfiguration(config).build();
        assertThat(r.getConfiguration()).isEqualTo(config);
    }

    @Test
    void builder_withDefaultConfig_shouldNotThrow() {
        assertThatCode(() -> JenticRuntime.builder().withDefaultConfig().build())
            .doesNotThrowAnyException();
    }

    @Test
    void shouldDiscoverAndStartAgentsFromConfigurationScanPackages() {
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("test-runtime", "development", null),
                new JenticConfiguration.AgentsConfig(
                        true,
                        null,
                        null,
                        List.of("dev.jentic.runtime"),
                        null
                ),
                null,
                null,
                null
        );

        runtimeUnderTest = JenticRuntime.builder()
                .withConfiguration(config)
                .build();

        runtimeUnderTest.start().join();

        assertThat(runtimeUnderTest.getAgents()).isNotEmpty();
        assertThat(runtimeUnderTest.getAgents())
                .allSatisfy(agent -> assertThat(agent.isRunning()).isTrue());
    }

    @Test
    void getStats_shouldReflectScanPackagesFromConfiguration() {
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("test-runtime", "development", null),
                new JenticConfiguration.AgentsConfig(
                        true,
                        null,
                        null,
                        List.of("dev.jentic.runtime.discovery"),
                        null
                ),
                null,
                null,
                null
        );

        JenticRuntime runtime = JenticRuntime.builder()
                .withConfiguration(config)
                .build();

        JenticRuntime.RuntimeStats stats = runtime.getStats();

        assertThat(stats.scannedPackages()).isEqualTo(1);
    }

    @Test
    void builder_withDefaultConfig_shouldThrowWhenLoadedConfigIsInvalid() throws Exception {

        // Build a config with an empty runtime name (violates validation rule).
        var badRuntime = new JenticConfiguration.RuntimeConfig("", "development", Map.of());
        var badConfig  = new JenticConfiguration(badRuntime, null, null, null, null);

        // withConfiguration must now throw instead of silently accepting.
        assertThatThrownBy(() -> JenticRuntime.builder().withConfiguration(badConfig))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("runtime.name");
    }

    @Test
    void withConfiguration_shouldThrowWhenConfigIsNull() {
        assertThatThrownBy(() -> JenticRuntime.builder().withConfiguration(null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void withConfiguration_shouldThrowWhenConfigIsInvalid() {
        // runtime.name blank → validation must fail
        var badRuntime = new JenticConfiguration.RuntimeConfig("   ", "development", Map.of());
        var badConfig  = new JenticConfiguration(badRuntime, null, null, null, null);

        assertThatThrownBy(() -> JenticRuntime.builder().withConfiguration(badConfig))
                .isInstanceOf(ConfigurationException.class);
    }

    // ========== LLM MEMORY INJECTION ==========

    @Test
    void registerAgent_shouldInjectLLMMemoryManagerIntoLLMMemoryAwareBaseAgent() {
        LLMMemoryManager manager = mock(LLMMemoryManager.class);
        var agent = new LLMMemoryAwareTestAgent("llm-aware-agent", "LLM Aware Agent");
        JenticRuntime runtime = JenticRuntime.builder()
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
        JenticRuntime runtime = JenticRuntime.builder()
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
        JenticRuntime runtime = JenticRuntime.builder()
            .llmMemoryManagerFactory(agentId -> manager)
            .build();

        runtime.registerAgent(agent);

        assertThat(agent.getReceivedManager()).isSameAs(manager);
    }

    @Test
    void registerAgent_shouldNotInjectLLMMemoryManagerWhenFactoryIsAbsent() {
        var agent = new LLMMemoryAwareTestAgent("no-factory-agent", "No Factory");
        JenticRuntime runtime = JenticRuntime.builder()
            // no llmMemoryManagerFactory configured
            .build();

        assertThatCode(() -> runtime.registerAgent(agent)).doesNotThrowAnyException();
        assertThat(agent.getReceivedManager()).isNull();
    }

    // ========== REQUEST-REPLY INTEGRATION ==========

    /**
     * Bug documentation: sendAndWait() on getMessageService() sends on the legacy InMemoryMessageService bus,
     * while @JenticMessageHandler subscriptions live on getMessageDispatcher() (InMemoryMessageDispatcher).
     * The two buses are separate — request never reaches the handler and the future always times out.
     */
    @Test
    @SuppressWarnings("deprecation")
    void requestReply_viaGetMessageService_sendAndWait_shouldTimeoutWhenHandlerIsOnDispatcher() throws Exception {
        runtimeUnderTest = JenticRuntime.builder().build();
        EchoResponderAgent responder = new EchoResponderAgent("rr-responder-legacy");
        runtimeUnderTest.registerAgent(responder);
        runtimeUnderTest.start().join();

        // sendAndWait sends on InMemoryMessageService bus; @JenticMessageHandler subscribes on
        // InMemoryMessageDispatcher bus — two separate instances → request never reaches the handler
        Message request = Message.builder()
                .senderId("rr-caller")
                .topic("rr.request")
                .content("ping")
                .build();

        CompletableFuture<Message> reply = runtimeUnderTest.getMessageService()
                .sendAndWait(request, 400);

        assertThatThrownBy(reply::join)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class)
                .as("sendAndWait via getMessageService() must time out because it uses a separate bus from the dispatcher");
    }

    /**
     * Correct agent-to-agent request-reply: the requester (a registered agent) subscribes for direct
     * replies via subscribeRecipient(getAgentId()), publishes the request, then awaits the reply.
     * The responder can sendTo(requester agentId) because it is registered in the AgentDirectory.
     * This is the pattern that OrderOrchestratorAgent must use after the Item 2 (0.20.0) refactor.
     */
    @Test
    void requestReply_agentToAgent_viaDispatcher_shouldDeliverAndReceiveReply() throws Exception {
        runtimeUnderTest = JenticRuntime.builder().build();
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

    // ========== DIRECT MESSAGE DISPATCH INTEGRATION ==========

    @Test
    void directMessage_shouldBeReceivedByAgentWhenSentViaGetMessageDispatcher() throws Exception {
        runtimeUnderTest = JenticRuntime.builder().build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        DirectMessageAgent agent = new DirectMessageAgent("direct-agent", latch, received);
        runtimeUnderTest.registerAgent(agent);
        runtimeUnderTest.start().join();

        runtimeUnderTest.getMessageDispatcher().sendTo("direct-agent",
                Message.builder().receiverId("direct-agent").content("direct-hello").build());

        assertThat(latch.await(2, TimeUnit.SECONDS))
                .as("agent should receive direct message sent via getMessageDispatcher().sendTo()")
                .isTrue();
        assertThat(received.get()).isEqualTo("direct-hello");
    }

    // ========== MULTI-INSTANCE AGENT ROUTING ==========

    /**
     * Regression: @JenticAgent("worker") annotation value was used as the descriptor agentId for
     * ALL instances of the class, so resolveEndpoint("worker-1") returned empty and sendTo never
     * delivered. The descriptor agentId must come from getAgentId(), not the annotation.
     * Same root cause: autoSubscribeDirectMessages() used the internal UUID field instead of
     * getAgentId(), so even if resolution worked the subscription key was wrong.
     */
    @Test
    void multiInstanceAgent_withOverriddenGetAgentId_receivesDirectMessageByLogicalId() throws Exception {
        runtimeUnderTest = JenticRuntime.builder().build();
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
                .sendTo("worker-1",
                        Message.builder().receiverId("worker-1").content("task-for-1").build())
                .join();

        assertThat(w1Latch.await(2, TimeUnit.SECONDS))
                .as("worker-1 must receive the message addressed to it")
                .isTrue();
        assertThat(w1Content.get()).isEqualTo("task-for-1");
        assertThat(w2Latch.getCount())
                .as("worker-2 must NOT receive the message addressed to worker-1")
                .isEqualTo(1);
    }

    // ========== MESSAGE DISPATCH INTEGRATION ==========

    @Test
    void messageHandler_shouldReceiveMessagePublishedViaGetMessageDispatcher() throws Exception {
        runtimeUnderTest = JenticRuntime.builder().build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        MessageCapturingAgent agent = new MessageCapturingAgent("capture-agent", latch, received);
        runtimeUnderTest.registerAgent(agent);
        runtimeUnderTest.start().join();

        runtimeUnderTest.getMessageDispatcher().publish("capture.topic",
                Message.builder().topic("capture.topic").content("hello").build());

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isEqualTo("hello");
    }

    @Test
    void getMessageDispatcher_shouldBeTheSameInstanceUsedByAgentHandlers() throws Exception {
        runtimeUnderTest = JenticRuntime.builder().build();
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

        runtimeUnderTest.getMessageDispatcher().publish("capture.topic",
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

    /** BaseAgent with no ID set — runtime must generate a random one. */
    static class NoIdAgent extends BaseAgent {
        NoIdAgent() {
            super();
        }
    }

    @JenticAgent("discoverable-test-agent")
    static class DiscoverableTestAgent extends BaseAgent {
        public DiscoverableTestAgent() {
            super("discoverable-test-agent", "Discoverable Test Agent");
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

    @JenticAgent("plain-llm-agent")
    static class PlainLLMMemoryAwareAgent implements Agent, LLMMemoryAware {
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
        @Override public dev.jentic.core.messaging.MessageDispatcher getMessageDispatcher() { return null; }

        @Override
        public void setLLMMemoryManager(LLMMemoryManager manager) {
            this.receivedManager = manager;
        }

        public LLMMemoryManager getReceivedManager() {
            return receivedManager;
        }
    }

    @JenticAgent("direct-message-agent")
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

            dev.jentic.core.messaging.Subscription sub = getMessageDispatcher()
                    .subscribeRecipient(getAgentId(), msg -> {
                        if (request.id().equals(msg.correlationId())) {
                            received.set(msg);
                            latch.countDown();
                        }
                        return CompletableFuture.completedFuture(null);
                    });
            getMessageDispatcher().publish("rr.request", request);
        }

        String lastRequestId() { return lastRequestId; }
    }

    /** Handles "rr.request" topic and replies via getMessageDispatcher().sendTo(). */
    @JenticAgent("echo-responder-agent")
    static class EchoResponderAgent extends BaseAgent {
        EchoResponderAgent(String agentId) {
            super(agentId, agentId);
        }

        @JenticMessageHandler("rr.request")
        public void onRequest(Message message) {
            Message reply = message.reply("pong")
                    .topic("rr.reply")
                    .build();
            getMessageDispatcher().sendTo(reply.receiverId(), reply);
        }
    }

    /** Multi-instance worker: @JenticAgent annotation holds the class-level type label ("worker"),
     *  while getAgentId() returns the instance-specific logical ID. Uses the no-arg super()
     *  constructor so that BaseAgent.agentId = UUID (simulating the ContractNetExample pattern). */
    @JenticAgent("worker")
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

    @JenticAgent("message-capturing-agent")
    static class MessageCapturingAgent extends BaseAgent {
        private final CountDownLatch latch;
        private final AtomicReference<String> captured;

        MessageCapturingAgent(String agentId, CountDownLatch latch, AtomicReference<String> captured) {
            super(agentId, agentId);
            this.latch = latch;
            this.captured = captured;
        }

        @JenticMessageHandler("capture.topic")
        public void onMessage(Message message) {
            captured.set(message.getContent(String.class));
            latch.countDown();
        }
    }
}