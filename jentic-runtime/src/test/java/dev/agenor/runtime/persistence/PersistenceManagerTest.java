package dev.agenor.runtime.persistence;

import dev.agenor.core.AgentStatus;
import dev.agenor.core.persistence.AgentState;
import dev.agenor.core.persistence.PersistenceService;
import dev.agenor.core.persistence.PersistenceStrategy;
import dev.agenor.core.persistence.Stateful;
import dev.agenor.core.annotations.JenticPersistenceConfig;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PersistenceManagerTest {

    @TempDir
    Path tempDir;

    private FilePersistenceService persistenceService;
    private PersistenceManager persistenceManager;

    @BeforeEach
    void setUp() throws Exception {
        persistenceService = new FilePersistenceService(tempDir);
        persistenceManager = new PersistenceManager(persistenceService);
        persistenceManager.start().get();
    }

    @AfterEach
    void tearDown() throws Exception {
        persistenceManager.stop().get();
    }

    @Test
    @DisplayName("Should register agent with manual persistence")
    void testRegisterManualAgent() {
        // Given
        TestAgent agent = new TestAgent("test-agent", PersistenceStrategy.MANUAL);

        // When
        persistenceManager.registerAgent(agent);

        // Then
        assertThat(persistenceManager.getStats())
            .containsEntry("registeredAgents", 1);
    }

    @Test
    @DisplayName("Should save and load agent manually")
    void testManualSaveLoad() throws Exception {
        // Given
        TestAgent agent = new TestAgent("test-agent", PersistenceStrategy.MANUAL);
        agent.counter.set(42);

        // When
        persistenceManager.saveAgent(agent).get();

        TestAgent newAgent = new TestAgent("test-agent", PersistenceStrategy.MANUAL);
        boolean loaded = persistenceManager.loadAgent(newAgent).get();

        // Then
        assertThat(loaded).isTrue();
        assertThat(newAgent.counter.get()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should handle periodic persistence")
    void testPeriodicPersistence() throws Exception {
        // Given
        PeriodicTestAgent agent = new PeriodicTestAgent("test-agent");
        agent.setRunning(true);
        agent.counter.set(10);

        // When
        persistenceManager.registerAgent(agent);
        Thread.sleep(500); // Wait for ~5 saves
        agent.counter.set(20);
        Thread.sleep(200); // Wait for another save

        // Then
        TestAgent newAgent = new TestAgent("test-agent", PersistenceStrategy.MANUAL);
        persistenceManager.loadAgent(newAgent).get();
        assertThat(newAgent.counter.get()).isEqualTo(20);
    }

    @Test
    @DisplayName("Should create and restore snapshots")
    void testSnapshotManagement() throws Exception {
        // Given
        TestAgent agent = new TestAgent("test-agent", PersistenceStrategy.MANUAL);
        agent.counter.set(100);
        persistenceManager.registerAgent(agent);
        persistenceManager.saveAgent(agent).get();

        // Create snapshot
        String snapshotId = persistenceManager.createSnapshot("test-agent").get();

        // Verify snapshot was created
        assertThat(snapshotId).isNotNull();
        assertThat(snapshotId).isNotEmpty();

        // Modify state
        agent.counter.set(200);
        persistenceManager.saveAgent(agent).get();

        // Verify current state is 200
        assertThat(agent.counter.get()).isEqualTo(200);

        // When
        boolean restored = persistenceManager.restoreSnapshot("test-agent", snapshotId).get();

        // Then
        assertThat(restored)
                .withFailMessage("Failed to restore snapshot. SnapshotId: %s", snapshotId)
                .isTrue();
        assertThat(agent.counter.get())
                .withFailMessage("Expected counter to be restored to 100, but was %d", agent.counter.get())
                .isEqualTo(100); // Restored to snapshot value
    }

    @Test
    @DisplayName("Should get persistence statistics")
    void testGetStats() throws Exception {
        // Given
        PeriodicTestAgent agent1 = new PeriodicTestAgent("agent-1");
        PeriodicTestAgent agent2 = new PeriodicTestAgent("agent-2");

        // When
        persistenceManager.registerAgent(agent1);
        persistenceManager.registerAgent(agent2);

        var stats = persistenceManager.getStats();

        // Then
        assertThat(stats).containsEntry("running", true);
        assertThat(stats).containsKey("registeredAgents");
    }

    // Test helper classes

    @JenticPersistenceConfig(strategy = PersistenceStrategy.MANUAL)
    static class TestAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);
        private boolean running = false;

        TestAgent(String agentId, PersistenceStrategy strategy) {
            this(agentId, strategy, "60s");
        }

        TestAgent(String agentId, PersistenceStrategy strategy, String interval) {
            super(agentId, agentId);
            // Dynamically set persistence config via annotation would require
            // runtime annotation manipulation, so we'll use a simpler approach
        }

        void setRunning(boolean running) {
            this.running = running;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                .agentName(getAgentName())
                .agentType("TestAgent")
                .status(isRunning() ? AgentStatus.RUNNING : AgentStatus.STOPPED)
                .data("counter", counter.get())
                .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer counterValue = (Integer) state.data().get("counter");
            if (counterValue != null) {
                counter.set(counterValue);
            }
        }
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.PERIODIC, interval = "100ms")
    static class PeriodicTestAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);
        private boolean running = false;

        PeriodicTestAgent(String agentId) {
            super(agentId, agentId);
        }

        void setRunning(boolean running) {
            this.running = running;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                    .agentName(getAgentName())
                    .agentType("PeriodicTestAgent")
                    .status(isRunning() ? AgentStatus.RUNNING : AgentStatus.STOPPED)
                    .data("counter", counter.get())
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer counterValue = (Integer) state.data().get("counter");
            if (counterValue != null) {
                counter.set(counterValue);
            }
        }
    }

    // =========================================================================
    // getPersistenceService
    // =========================================================================

    @Test
    @DisplayName("Should return the configured persistence service")
    void testGetPersistenceService() {
        assertThat(persistenceManager.getPersistenceService()).isSameAs(persistenceService);
    }

    // =========================================================================
    // start / stop idempotency
    // =========================================================================

    @Test
    @DisplayName("Should be idempotent when started twice")
    void testStartTwiceIsIdempotent() throws Exception {
        // already started in setUp; starting again should not throw
        assertThatCode(() -> persistenceManager.start().get()).doesNotThrowAnyException();

        Map<String, Object> stats = persistenceManager.getStats();
        assertThat(stats).containsEntry("running", true);
    }

    @Test
    @DisplayName("Should be idempotent when stopped twice")
    void testStopTwiceIsIdempotent() throws Exception {
        persistenceManager.stop().get();
        // second stop should not throw
        assertThatCode(() -> persistenceManager.stop().get()).doesNotThrowAnyException();
    }

    // =========================================================================
    // registerAgent - non-Stateful
    // =========================================================================

    @Test
    @DisplayName("Should skip registration for non-Stateful agent")
    void testRegisterNonStatefulAgent() {
        NonStatefulAgent agent = new NonStatefulAgent("non-stateful");
        // Should not throw and should NOT register
        persistenceManager.registerAgent(agent);

        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 0);
    }

    @Test
    @DisplayName("Should skip registration when agent has no @JenticPersistenceConfig")
    void testRegisterAgentWithoutAnnotation() {
        NoAnnotationAgent agent = new NoAnnotationAgent("no-annotation");
        persistenceManager.registerAgent(agent);

        // Agent without annotation is not registered
        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 0);
    }

    // =========================================================================
    // unregisterAgent
    // =========================================================================

    @Test
    @DisplayName("Should unregister agent and save final state")
    void testUnregisterAgent() throws Exception {
        ManualTestAgent agent = new ManualTestAgent("agent-unreg");
        agent.counter.set(99);
        persistenceManager.registerAgent(agent);

        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 1);

        persistenceManager.unregisterAgent("agent-unreg");

        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 0);

        // Verify final state was saved
        Optional<AgentState> saved = persistenceService.loadState("agent-unreg").get();
        assertThat(saved).isPresent();
        assertThat(saved.get().data().get("counter")).isEqualTo(99);
    }

    @Test
    @DisplayName("Should not fail when unregistering unknown agent")
    void testUnregisterUnknownAgent() {
        assertThatCode(() -> persistenceManager.unregisterAgent("does-not-exist"))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // saveAgent / loadAgent - non-Stateful
    // =========================================================================

    @Test
    @DisplayName("Should fail saveAgent for non-Stateful agent")
    void testSaveNonStatefulAgent() {
        NonStatefulAgent agent = new NonStatefulAgent("ns");
        CompletableFuture<Void> future = persistenceManager.saveAgent(agent);

        assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should fail loadAgent for non-Stateful agent")
    void testLoadNonStatefulAgent() {
        NonStatefulAgent agent = new NonStatefulAgent("ns");
        CompletableFuture<Boolean> future = persistenceManager.loadAgent(agent);

        assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should return false when loading agent with no saved state")
    void testLoadAgentNoSavedState() throws Exception {
        ManualTestAgent agent = new ManualTestAgent("fresh-agent");
        boolean loaded = persistenceManager.loadAgent(agent).get();

        assertThat(loaded).isFalse();
    }

    // =========================================================================
    // createSnapshot / restoreSnapshot - unregistered agent
    // =========================================================================

    @Test
    @DisplayName("Should fail createSnapshot for unregistered agent")
    void testCreateSnapshotUnregistered() {
        CompletableFuture<String> future = persistenceManager.createSnapshot("not-registered");

        assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should fail restoreSnapshot for unregistered agent")
    void testRestoreSnapshotUnregistered() {
        CompletableFuture<Boolean> future = persistenceManager.restoreSnapshot("not-registered", "snap-1");

        assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should return false when restoring non-existent snapshot")
    void testRestoreNonExistentSnapshot() throws Exception {
        ManualTestAgent agent = new ManualTestAgent("agent-snap");
        persistenceManager.registerAgent(agent);
        persistenceManager.saveAgent(agent).get();

        boolean restored = persistenceManager.restoreSnapshot("agent-snap", "no-such-snap").get();
        assertThat(restored).isFalse();
    }

    // =========================================================================
    // ON_STOP strategy
    // =========================================================================

    @Test
    @DisplayName("Should register ON_STOP agent and save state on stop")
    void testOnStopStrategy() throws Exception {
        OnStopTestAgent agent = new OnStopTestAgent("on-stop-agent");
        agent.counter.set(55);
        persistenceManager.registerAgent(agent);

        // Start and stop the agent to trigger on-stop hook
        agent.setMessageDispatcher(new dev.agenor.runtime.messaging.InMemoryMessageDispatcher(new dev.agenor.runtime.directory.InMemoryAgentDirectory(), dev.agenor.core.telemetry.JenticTelemetry.noop()));
        agent.setBehaviorScheduler(new dev.agenor.runtime.scheduler.SimpleBehaviorScheduler());
        agent.start().get();
        agent.stop().get();

        // Verify state was saved
        Optional<AgentState> saved = persistenceService.loadState("on-stop-agent").get();
        assertThat(saved).isPresent();
        assertThat(saved.get().data().get("counter")).isEqualTo(55);
    }

    // =========================================================================
    // DEBOUNCED strategy
    // =========================================================================

    @Test
    @DisplayName("Should register DEBOUNCED agent without error")
    void testDebouncedStrategy() throws Exception {
        DebouncedTestAgent agent = new DebouncedTestAgent("debounced-agent");
        agent.counter.set(77);
        persistenceManager.registerAgent(agent);

        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 1);

        // Manually save to verify agent is functional
        persistenceManager.saveAgent(agent).get();
        Optional<AgentState> saved = persistenceService.loadState("debounced-agent").get();
        assertThat(saved).isPresent();
        assertThat(saved.get().data().get("counter")).isEqualTo(77);
    }

    // =========================================================================
    // SNAPSHOT strategy
    // =========================================================================

    @Test
    @DisplayName("Should register SNAPSHOT strategy agent")
    void testSnapshotStrategy() {
        SnapshotTestAgent agent = new SnapshotTestAgent("snapshot-agent");
        persistenceManager.registerAgent(agent);

        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 1);
    }

    // =========================================================================
    // IMMEDIATE strategy
    // =========================================================================

    @Test
    @DisplayName("Should register IMMEDIATE strategy agent (logs warning)")
    void testImmediateStrategy() {
        ImmediateTestAgent agent = new ImmediateTestAgent("immediate-agent");
        // Should not throw - just logs a warning
        assertThatCode(() -> persistenceManager.registerAgent(agent)).doesNotThrowAnyException();
        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 1);
    }

    // =========================================================================
    // autoSnapshot
    // =========================================================================

    @Test
    @DisplayName("Should register agent with autoSnapshot=true")
    void testAutoSnapshotEnabled() throws Exception {
        AutoSnapshotAgent agent = new AutoSnapshotAgent("auto-snap-agent");
        agent.counter.set(33);
        persistenceManager.registerAgent(agent);
        persistenceManager.saveAgent(agent).get();

        // Just verify registration works without error
        assertThat(persistenceManager.getStats()).containsEntry("registeredAgents", 1);
    }

    // =========================================================================
    // getStats with non-File persistence service
    // =========================================================================

    @Test
    @DisplayName("Should return basic stats when using non-File persistence service")
    void testGetStatsWithNonFilePersistence() throws Exception {
        InMemoryPersistenceService inMemory = new InMemoryPersistenceService();
        PersistenceManager mgr = new PersistenceManager(inMemory);
        mgr.start().get();

        try {
            ManualTestAgent agent = new ManualTestAgent("mem-agent");
            mgr.registerAgent(agent);

            Map<String, Object> stats = mgr.getStats();
            assertThat(stats).containsEntry("registeredAgents", 1);
            assertThat(stats).containsEntry("running", true);
            // No "totalStates" key since not a FilePersistenceService
            assertThat(stats).doesNotContainKey("totalStates");
        } finally {
            mgr.stop().get();
        }
    }

    // =========================================================================
    // Test agent helpers
    // =========================================================================

    static class NonStatefulAgent extends BaseAgent {
        NonStatefulAgent(String id) { super(id, id); }
    }

    static class NoAnnotationAgent extends BaseAgent implements Stateful {
        NoAnnotationAgent(String id) { super(id, id); }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId()).build();
        }

        @Override
        public void restoreState(AgentState state) {}
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.MANUAL)
    static class ManualTestAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);

        ManualTestAgent(String id) { super(id, id); }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                    .agentName(getAgentName())
                    .status(AgentStatus.STOPPED)
                    .data("counter", counter.get())
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer v = (Integer) state.data().get("counter");
            if (v != null) counter.set(v);
        }
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.ON_STOP)
    static class OnStopTestAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);

        OnStopTestAgent(String id) { super(id, id); }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                    .data("counter", counter.get())
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer v = (Integer) state.data().get("counter");
            if (v != null) counter.set(v);
        }
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.DEBOUNCED, interval = "200ms")
    static class DebouncedTestAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);

        DebouncedTestAgent(String id) { super(id, id); }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                    .data("counter", counter.get())
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer v = (Integer) state.data().get("counter");
            if (v != null) counter.set(v);
        }
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.SNAPSHOT, snapshotInterval = "500ms")
    static class SnapshotTestAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);

        SnapshotTestAgent(String id) { super(id, id); }

        @Override
        public boolean isRunning() { return false; }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                    .data("counter", counter.get())
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {}
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.IMMEDIATE)
    static class ImmediateTestAgent extends BaseAgent implements Stateful {
        ImmediateTestAgent(String id) { super(id, id); }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId()).build();
        }

        @Override
        public void restoreState(AgentState state) {}
    }

    @JenticPersistenceConfig(strategy = PersistenceStrategy.PERIODIC, interval = "1h",
            autoSnapshot = true, snapshotInterval = "500ms", maxSnapshots = 2)
    static class AutoSnapshotAgent extends BaseAgent implements Stateful {
        final AtomicInteger counter = new AtomicInteger(0);

        AutoSnapshotAgent(String id) { super(id, id); }

        @Override
        public boolean isRunning() { return false; }

        @Override
        public AgentState captureState() {
            return AgentState.builder(getAgentId())
                    .data("counter", counter.get())
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer v = (Integer) state.data().get("counter");
            if (v != null) counter.set(v);
        }
    }

    // Minimal in-memory persistence service for testing non-File branch
    static class InMemoryPersistenceService implements PersistenceService {
        private final Map<String, AgentState> states = new ConcurrentHashMap<>();
        private final Map<String, Map<String, AgentState>> snapshots = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Void> saveState(String agentId, AgentState state) {
            return CompletableFuture.runAsync(() -> states.put(agentId, state));
        }

        @Override
        public CompletableFuture<Optional<AgentState>> loadState(String agentId) {
            return CompletableFuture.completedFuture(Optional.ofNullable(states.get(agentId)));
        }

        @Override
        public CompletableFuture<Void> deleteState(String agentId) {
            return CompletableFuture.runAsync(() -> {
                states.remove(agentId);
                snapshots.remove(agentId);
            });
        }

        @Override
        public CompletableFuture<Boolean> existsState(String agentId) {
            return CompletableFuture.completedFuture(states.containsKey(agentId));
        }

        @Override
        public CompletableFuture<String> createSnapshot(String agentId, String snapshotId) {
            return CompletableFuture.supplyAsync(() -> {
                AgentState state = states.get(agentId);
                if (state == null) return null;
                snapshots.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>())
                        .put(snapshotId, state);
                return snapshotId;
            });
        }

        @Override
        public CompletableFuture<Optional<AgentState>> restoreSnapshot(String agentId, String snapshotId) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, AgentState> s = snapshots.get(agentId);
                return s != null ? Optional.ofNullable(s.get(snapshotId)) : Optional.empty();
            });
        }

        @Override
        public CompletableFuture<List<String>> listSnapshots(String agentId) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, AgentState> s = snapshots.get(agentId);
                return s != null ? List.copyOf(s.keySet()) : List.of();
            });
        }
    }
}
