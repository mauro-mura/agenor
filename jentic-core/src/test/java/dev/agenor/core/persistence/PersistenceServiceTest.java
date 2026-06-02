package dev.agenor.core.persistence;

import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PersistenceService Contract Tests")
class PersistenceServiceTest {

    private PersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new InMemoryPersistenceService();
    }

    @Test
    @DisplayName("should save and load agent state")
    void shouldSaveAndLoadAgentState() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState state = AgentState.builder(agentId)
                .agentName("Test Agent")
                .status(AgentStatus.RUNNING)
                .data("counter", 42)
                .build();

        // When
        persistenceService.saveState(agentId, state).get();
        Optional<AgentState> loaded = persistenceService.loadState(agentId).get();

        // Then
        assertTrue(loaded.isPresent());
        assertEquals(agentId, loaded.get().agentId());
        assertEquals("Test Agent", loaded.get().agentName());
        assertEquals(42, loaded.get().getData("counter", Integer.class));
    }

    @Test
    @DisplayName("should return empty optional for non-existent state")
    void shouldReturnEmptyForNonExistentState() throws Exception {
        // When
        Optional<AgentState> loaded = persistenceService.loadState("non-existent").get();

        // Then
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("should delete agent state")
    void shouldDeleteAgentState() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState state = AgentState.builder(agentId).build();
        persistenceService.saveState(agentId, state).get();

        // When
        persistenceService.deleteState(agentId).get();
        Optional<AgentState> loaded = persistenceService.loadState(agentId).get();

        // Then
        assertTrue(loaded.isEmpty());
    }

    @Test
    @DisplayName("should check if state exists")
    void shouldCheckIfStateExists() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState state = AgentState.builder(agentId).build();

        // When
        boolean beforeSave = persistenceService.existsState(agentId).get();
        persistenceService.saveState(agentId, state).get();
        boolean afterSave = persistenceService.existsState(agentId).get();

        // Then
        assertFalse(beforeSave);
        assertTrue(afterSave);
    }

    @Test
    @DisplayName("should create snapshot")
    void shouldCreateSnapshot() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState state = AgentState.builder(agentId)
                .data("counter", 10)
                .build();
        persistenceService.saveState(agentId, state).get();

        // When
        String snapshotId = persistenceService.createSnapshot(agentId, "snapshot-1").get();

        // Then
        assertNotNull(snapshotId);
        assertEquals("snapshot-1", snapshotId);
    }

    @Test
    @DisplayName("should restore state from snapshot")
    void shouldRestoreStateFromSnapshot() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState original = AgentState.builder(agentId)
                .data("counter", 10)
                .version(1)
                .build();
        persistenceService.saveState(agentId, original).get();
        String snapshotId = persistenceService.createSnapshot(agentId, "snapshot-1").get();

        // Modify state
        AgentState modified = AgentState.builder(agentId)
                .data("counter", 20)
                .version(2)
                .build();
        persistenceService.saveState(agentId, modified).get();

        // When
        Optional<AgentState> restored = persistenceService.restoreSnapshot(agentId, snapshotId).get();

        // Then
        assertTrue(restored.isPresent());
        assertEquals(10, restored.get().getData("counter", Integer.class));
    }

    @Test
    @DisplayName("should list snapshots")
    void shouldListSnapshots() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState state = AgentState.builder(agentId).build();
        persistenceService.saveState(agentId, state).get();

        persistenceService.createSnapshot(agentId, "snapshot-1").get();
        persistenceService.createSnapshot(agentId, "snapshot-2").get();

        // When
        List<String> snapshots = persistenceService.listSnapshots(agentId).get();

        // Then
        assertEquals(2, snapshots.size());
        assertTrue(snapshots.contains("snapshot-1"));
        assertTrue(snapshots.contains("snapshot-2"));
    }

    @Test
    @DisplayName("should return empty list for agent without snapshots")
    void shouldReturnEmptyListForAgentWithoutSnapshots() throws Exception {
        // When
        List<String> snapshots = persistenceService.listSnapshots("non-existent").get();

        // Then
        assertTrue(snapshots.isEmpty());
    }

    @Test
    @DisplayName("should handle concurrent saves")
    void shouldHandleConcurrentSaves() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState state1 = AgentState.builder(agentId)
                .data("counter", 1)
                .version(1)
                .build();
        AgentState state2 = AgentState.builder(agentId)
                .data("counter", 2)
                .version(2)
                .build();

        // When
        CompletableFuture<Void> save1 = persistenceService.saveState(agentId, state1);
        CompletableFuture<Void> save2 = persistenceService.saveState(agentId, state2);
        CompletableFuture.allOf(save1, save2).get();

        // Then
        Optional<AgentState> loaded = persistenceService.loadState(agentId).get();
        assertTrue(loaded.isPresent());
        assertNotNull(loaded.get().getData("counter", Integer.class));
    }

    @Test
    @DisplayName("should update existing state")
    void shouldUpdateExistingState() throws Exception {
        // Given
        String agentId = "test-agent-1";
        AgentState initial = AgentState.builder(agentId)
                .data("counter", 1)
                .version(1)
                .build();
        persistenceService.saveState(agentId, initial).get();

        // When
        AgentState updated = AgentState.builder(agentId)
                .data("counter", 2)
                .version(2)
                .build();
        persistenceService.saveState(agentId, updated).get();

        // Then
        Optional<AgentState> loaded = persistenceService.loadState(agentId).get();
        assertTrue(loaded.isPresent());
        assertEquals(2, loaded.get().getData("counter", Integer.class));
        assertEquals(2, loaded.get().version());
    }

    @Test
    @DisplayName("should handle snapshot for non-existent agent")
    void shouldHandleSnapshotForNonExistentAgent() throws Exception {
        // When
        String snapshotId = persistenceService.createSnapshot("non-existent", "snapshot-1").get();

        // Then
        assertNull(snapshotId);
    }

    @Test
    @DisplayName("should return empty when restoring non-existent snapshot")
    void shouldReturnEmptyWhenRestoringNonExistentSnapshot() throws Exception {
        // When
        Optional<AgentState> restored = persistenceService.restoreSnapshot("agent-1", "non-existent").get();

        // Then
        assertTrue(restored.isEmpty());
    }

    /**
     * Simple in-memory implementation for testing the PersistenceService contract
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
                if (state == null) {
                    return null;
                }
                snapshots.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>())
                        .put(snapshotId, state);
                return snapshotId;
            });
        }

        @Override
        public CompletableFuture<Optional<AgentState>> restoreSnapshot(String agentId, String snapshotId) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, AgentState> agentSnapshots = snapshots.get(agentId);
                if (agentSnapshots == null) {
                    return Optional.empty();
                }
                return Optional.ofNullable(agentSnapshots.get(snapshotId));
            });
        }

        @Override
        public CompletableFuture<List<String>> listSnapshots(String agentId) {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, AgentState> agentSnapshots = snapshots.get(agentId);
                if (agentSnapshots == null) {
                    return List.of();
                }
                return List.copyOf(agentSnapshots.keySet());
            });
        }
    }
}
