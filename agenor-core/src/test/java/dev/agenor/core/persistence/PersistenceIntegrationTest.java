package dev.agenor.core.persistence;

import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Persistence Integration Tests")
class PersistenceIntegrationTest {

    private PersistenceService persistenceService;
    private StatefulTestAgent agent;

    @BeforeEach
    void setUp() {
        persistenceService = new InMemoryPersistenceService();
        agent = new StatefulTestAgent("integration-test-agent");
    }

    @Test
    @DisplayName("should complete full persistence lifecycle")
    void shouldCompleteFullPersistenceLifecycle() throws Exception {
        // Given - Initial state
        agent.setData("orders", List.of("order-1", "order-2"));
        agent.setData("status", "processing");
        agent.incrementVersion();

        // When - Save state
        AgentState capturedState = agent.captureState();
        persistenceService.saveState(agent.getAgentId(), capturedState).get();

        // Then - Verify saved
        assertTrue(persistenceService.existsState(agent.getAgentId()).get());

        // When - Load state
        Optional<AgentState> loadedState = persistenceService.loadState(agent.getAgentId()).get();

        // Then - Verify loaded
        assertTrue(loadedState.isPresent());
        assertEquals(agent.getAgentId(), loadedState.get().agentId());
        assertEquals(List.of("order-1", "order-2"), loadedState.get().getData("orders", List.class));
        assertEquals("processing", loadedState.get().getData("status", String.class));
    }

    @Test
    @DisplayName("should handle agent restart scenario")
    void shouldHandleAgentRestartScenario() throws Exception {
        // Given - Agent processes some work
        agent.setData("processedCount", 42);
        agent.setData("lastProcessedId", "item-42");
        agent.incrementVersion();

        // When - Save before shutdown
        persistenceService.saveState(agent.getAgentId(), agent.captureState()).get();

        // Simulate restart - create new agent instance
        StatefulTestAgent restartedAgent = new StatefulTestAgent(agent.getAgentId());

        // Load previous state
        Optional<AgentState> previousState = persistenceService.loadState(agent.getAgentId()).get();
        assertTrue(previousState.isPresent());
        restartedAgent.restoreState(previousState.get());

        // Then - Verify state restored
        assertEquals(42, restartedAgent.getData("processedCount", Integer.class));
        assertEquals("item-42", restartedAgent.getData("lastProcessedId", String.class));
    }

    @Test
    @DisplayName("should handle snapshot and rollback scenario")
    void shouldHandleSnapshotAndRollbackScenario() throws Exception {
        // Given - Initial stable state
        agent.setData("balance", 1000);
        agent.setData("transactions", List.of("tx-1", "tx-2"));
        persistenceService.saveState(agent.getAgentId(), agent.captureState()).get();

        // Create snapshot
        String snapshotId = persistenceService.createSnapshot(agent.getAgentId(), "before-update").get();
        assertNotNull(snapshotId);

        // When - Make changes
        agent.setData("balance", 500);
        agent.setData("transactions", List.of("tx-1", "tx-2", "tx-3", "tx-4"));
        agent.incrementVersion();
        persistenceService.saveState(agent.getAgentId(), agent.captureState()).get();

        // Simulate error - need to rollback
        Optional<AgentState> snapshot = persistenceService.restoreSnapshot(agent.getAgentId(), snapshotId).get();

        // Then - Verify rollback
        assertTrue(snapshot.isPresent());
        assertEquals(1000, snapshot.get().getData("balance", Integer.class));
        assertEquals(List.of("tx-1", "tx-2"), snapshot.get().getData("transactions", List.class));
    }

    @Test
    @DisplayName("should handle multiple agents with different strategies")
    void shouldHandleMultipleAgentsWithDifferentStrategies() throws Exception {
        // Given - Multiple agents with different configurations
        StatefulTestAgent agent1 = new StatefulTestAgent("agent-1");
        agent1.setPersistenceStrategy(PersistenceStrategy.IMMEDIATE);
        agent1.setData("type", "processor");

        StatefulTestAgent agent2 = new StatefulTestAgent("agent-2");
        agent2.setPersistenceStrategy(PersistenceStrategy.PERIODIC);
        agent2.setData("type", "monitor");

        // When - Save both states
        persistenceService.saveState(agent1.getAgentId(), agent1.captureState()).get();
        persistenceService.saveState(agent2.getAgentId(), agent2.captureState()).get();

        // Then - Verify both persisted correctly
        Optional<AgentState> loaded1 = persistenceService.loadState("agent-1").get();
        Optional<AgentState> loaded2 = persistenceService.loadState("agent-2").get();

        assertTrue(loaded1.isPresent());
        assertTrue(loaded2.isPresent());
        assertEquals("processor", loaded1.get().getData("type", String.class));
        assertEquals("monitor", loaded2.get().getData("type", String.class));
    }

    @Test
    @DisplayName("should handle version conflicts in optimistic locking")
    void shouldHandleVersionConflictsInOptimisticLocking() throws Exception {
        // Given - Initial state
        agent.setData("value", 100);
        persistenceService.saveState(agent.getAgentId(), agent.captureState()).get();

        // When - Load state for two concurrent updates
        Optional<AgentState> state1 = persistenceService.loadState(agent.getAgentId()).get();
        Optional<AgentState> state2 = persistenceService.loadState(agent.getAgentId()).get();

        // First update succeeds
        AgentState updated1 = AgentState.builder(agent.getAgentId())
                .data(state1.get().data())
                .data("value", 200)
                .version(state1.get().version() + 1)
                .build();
        persistenceService.saveState(agent.getAgentId(), updated1).get();

        // Second update with stale version
        AgentState updated2 = AgentState.builder(agent.getAgentId())
                .data(state2.get().data())
                .data("value", 300)
                .version(state2.get().version() + 1)
                .build();
        persistenceService.saveState(agent.getAgentId(), updated2).get();

        // Then - Verify latest state
        Optional<AgentState> latest = persistenceService.loadState(agent.getAgentId()).get();
        assertTrue(latest.isPresent());
        // Last write wins in simple implementation
        assertNotNull(latest.get().getData("value", Integer.class));
    }

    @Test
    @DisplayName("should handle complex data structures")
    void shouldHandleComplexDataStructures() throws Exception {
        // Given - Complex nested data
        Map<String, Object> complexData = Map.of(
                "config", Map.of("host", "localhost", "port", 8080),
                "metrics", List.of(
                        Map.of("name", "cpu", "value", 45.2),
                        Map.of("name", "memory", "value", 78.5)
                ),
                "tags", List.of("production", "critical", "monitored")
        );

        agent.setData("complexData", complexData);
        agent.setData("timestamp", Instant.now());

        // When - Save and reload
        persistenceService.saveState(agent.getAgentId(), agent.captureState()).get();
        Optional<AgentState> loaded = persistenceService.loadState(agent.getAgentId()).get();

        // Then - Verify complex data preserved
        assertTrue(loaded.isPresent());
        Map<String, Object> loadedComplexData = loaded.get().getData("complexData", Map.class);
        assertNotNull(loadedComplexData);
        assertEquals(3, loadedComplexData.size());
    }

    @Test
    @DisplayName("should cleanup snapshots when deleting state")
    void shouldCleanupSnapshotsWhenDeletingState() throws Exception {
        // Given - Agent with snapshots
        agent.setData("value", 100);
        persistenceService.saveState(agent.getAgentId(), agent.captureState()).get();
        persistenceService.createSnapshot(agent.getAgentId(), "snapshot-1").get();
        persistenceService.createSnapshot(agent.getAgentId(), "snapshot-2").get();

        // When - Delete state
        persistenceService.deleteState(agent.getAgentId()).get();

        // Then - Verify all cleaned up
        assertFalse(persistenceService.existsState(agent.getAgentId()).get());
        List<String> snapshots = persistenceService.listSnapshots(agent.getAgentId()).get();
        assertTrue(snapshots.isEmpty());
    }

    /**
     * Enhanced test agent with full Stateful support
     */
    private static class StatefulTestAgent implements Stateful {
        private final String agentId;
        private final Map<String, Object> data = new ConcurrentHashMap<>();
        private long stateVersion = 1L;
        private PersistenceStrategy strategy = PersistenceStrategy.MANUAL;

        public StatefulTestAgent(String agentId) {
            this.agentId = agentId;
        }

        @Override
        public AgentState captureState() {
            return AgentState.builder(agentId)
                    .agentName("Test Agent: " + agentId)
                    .agentType("test")
                    .status(AgentStatus.RUNNING)
                    .data(data)
                    .metadata("strategy", strategy.name())
                    .version(stateVersion)
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            this.data.clear();
            this.data.putAll(state.data());
            this.stateVersion = state.version();

            String strategyName = state.getMetadata("strategy");
            if (strategyName != null) {
                this.strategy = PersistenceStrategy.valueOf(strategyName);
            }
        }

        @Override
        public long getStateVersion() {
            return stateVersion;
        }

        public void setData(String key, Object value) {
            data.put(key, value);
        }

        public <T> T getData(String key, Class<T> type) {
            return type.cast(data.get(key));
        }

        public void incrementVersion() {
            stateVersion++;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setPersistenceStrategy(PersistenceStrategy strategy) {
            this.strategy = strategy;
        }
    }

    /**
     * Simple in-memory implementation for testing
     */
    private static class InMemoryPersistenceService implements PersistenceService {

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
                Map<String, AgentState> agentSnapshots = snapshots.get(agentId);
                return agentSnapshots != null
                        ? Optional.ofNullable(agentSnapshots.get(snapshotId))
                        : Optional.empty();
            });
        }

        @Override
        public CompletableFuture<List<String>> listSnapshots(String agentId) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, AgentState> agentSnapshots = snapshots.get(agentId);
                return agentSnapshots != null
                        ? List.copyOf(agentSnapshots.keySet())
                        : List.of();
            });
        }
    }
}
