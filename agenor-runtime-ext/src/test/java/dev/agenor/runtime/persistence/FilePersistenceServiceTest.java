package dev.agenor.runtime.persistence;

import dev.agenor.core.AgentStatus;
import dev.agenor.core.persistence.AgentState;
import dev.agenor.core.persistence.PersistenceException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

class FilePersistenceServiceTest {

    @TempDir
    Path tempDir;

    private FilePersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new FilePersistenceService(tempDir, true);
    }

    @Test
    @DisplayName("Should save and load agent state")
    void testSaveAndLoad() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .agentType("TestType")
            .status(AgentStatus.RUNNING)
            .data("counter", 42)
            .data("name", "John")
            .metadata("version", "1.0")
            .build();

        // When
        persistenceService.saveState("test-agent", state).get();
        Optional<AgentState> loaded = persistenceService.loadState("test-agent").get();

        // Then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().agentId()).isEqualTo("test-agent");
        assertThat(loaded.get().agentName()).isEqualTo("Test Agent");
        assertThat(loaded.get().status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(loaded.get().data().get("counter")).isEqualTo(42);
        assertThat(loaded.get().data().get("name")).isEqualTo("John");
    }

    @Test
    @DisplayName("Should return empty optional when state does not exist")
    void testLoadNonExistentState() throws Exception {
        // When
        Optional<AgentState> loaded = persistenceService.loadState("non-existent").get();

        // Then
        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Should check if state exists")
    void testExistsState() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .build();

        // When
        boolean existsBefore = persistenceService.existsState("test-agent").get();
        persistenceService.saveState("test-agent", state).get();
        boolean existsAfter = persistenceService.existsState("test-agent").get();

        // Then
        assertThat(existsBefore).isFalse();
        assertThat(existsAfter).isTrue();
    }

    @Test
    @DisplayName("Should delete agent state")
    void testDeleteState() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .build();
        persistenceService.saveState("test-agent", state).get();

        // When
        persistenceService.deleteState("test-agent").get();
        Optional<AgentState> loaded = persistenceService.loadState("test-agent").get();

        // Then
        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Should create and list snapshots")
    void testCreateAndListSnapshots() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .data("value", 100)
            .build();
        persistenceService.saveState("test-agent", state).get();

        // When
        String snapshot1 = persistenceService.createSnapshot("test-agent", "snapshot-1").get();
        String snapshot2 = persistenceService.createSnapshot("test-agent", "snapshot-2").get();
        List<String> snapshots = persistenceService.listSnapshots("test-agent").get();

        // Then
        assertThat(snapshot1).isEqualTo("snapshot-1");
        assertThat(snapshot2).isEqualTo("snapshot-2");
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).contains("snapshot-1", "snapshot-2");
    }

    @Test
    @DisplayName("Should restore state from snapshot")
    void testRestoreSnapshot() throws Exception {
        // Given
        AgentState originalState = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .data("counter", 100)
            .build();
        persistenceService.saveState("test-agent", originalState).get();
        String snapshotId = persistenceService.createSnapshot("test-agent", "backup").get();

        // Modify current state
        AgentState modifiedState = AgentState.builder("test-agent")
            .agentName("Modified Agent")
            .data("counter", 200)
            .build();
        persistenceService.saveState("test-agent", modifiedState).get();

        // When
        Optional<AgentState> restored = persistenceService.restoreSnapshot("test-agent", snapshotId).get();
        Optional<AgentState> currentState = persistenceService.loadState("test-agent").get();

        // Then
        assertThat(restored).isPresent();
        assertThat(restored.get().data().get("counter")).isEqualTo(100);
        assertThat(currentState).isPresent();
        assertThat(currentState.get().data().get("counter")).isEqualTo(100); // Restored
    }

    @Test
    @DisplayName("Should auto-generate snapshot ID if not provided")
    void testAutoGenerateSnapshotId() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .build();
        persistenceService.saveState("test-agent", state).get();

        // When
        String snapshotId = persistenceService.createSnapshot("test-agent", null).get();

        // Then
        assertThat(snapshotId).isNotNull();
        assertThat(snapshotId).matches("\\d{8}-\\d{6}-\\d{3}"); // yyyyMMdd-HHmmss-SSS
    }

    @Test
    @DisplayName("Should cleanup old snapshots")
    void testCleanupSnapshots() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .build();
        persistenceService.saveState("test-agent", state).get();

        // Create 5 snapshots
        for (int i = 1; i <= 5; i++) {
            persistenceService.createSnapshot("test-agent", "snapshot-" + i).get();
            Thread.sleep(10); // Ensure different timestamps
        }

        // When
        int deleted = persistenceService.cleanupSnapshots("test-agent", 3).get();
        List<String> remaining = persistenceService.listSnapshots("test-agent").get();

        // Then
        assertThat(deleted).isEqualTo(2);
        assertThat(remaining).hasSize(3);
    }

    @Test
    @DisplayName("Should handle concurrent saves safely")
    void testConcurrentSaves() throws Exception {
        // Given
        int concurrency = 10;
        CompletableFuture<?>[] futures = new CompletableFuture[concurrency];

        // When
        for (int i = 0; i < concurrency; i++) {
            final int value = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                AgentState state = AgentState.builder("test-agent")
                    .agentName("Test Agent")
                    .data("value", value)
                    .build();
                try {
                    persistenceService.saveState("test-agent", state).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        CompletableFuture.allOf(futures).get();

        // Then
        Optional<AgentState> loaded = persistenceService.loadState("test-agent").get();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().data()).containsKey("value");
    }

    @Test
    @DisplayName("Should get persistence statistics")
    void testGetStats() throws Exception {
        // Given
        AgentState state1 = AgentState.builder("agent-1").agentName("Agent 1").build();
        AgentState state2 = AgentState.builder("agent-2").agentName("Agent 2").build();

        persistenceService.saveState("agent-1", state1).get();
        persistenceService.saveState("agent-2", state2).get();
        persistenceService.createSnapshot("agent-1", "snapshot-1").get();

        // When
        FilePersistenceService.PersistenceStats stats = persistenceService.getStats();

        // Then
        assertThat(stats.totalStates()).isEqualTo(2);
        assertThat(stats.totalSnapshots()).isEqualTo(1);
        assertThat(stats.totalSizeBytes()).isGreaterThan(0);
        assertThat(stats.formatTotalSize()).contains("B");
    }

    @Test
    @DisplayName("Should preserve complex data types")
    void testComplexDataTypes() throws Exception {
        // Given
        Map<String, Object> complexData = Map.of(
            "string", "value",
            "number", 42,
            "boolean", true,
            "list", List.of(1, 2, 3),
            "nested", Map.of("key", "value")
        );

        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .data(complexData)
            .build();

        // When
        persistenceService.saveState("test-agent", state).get();
        Optional<AgentState> loaded = persistenceService.loadState("test-agent").get();

        // Then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().data().get("string")).isEqualTo("value");
        assertThat(loaded.get().data().get("number")).isEqualTo(42);
        assertThat(loaded.get().data().get("boolean")).isEqualTo(true);
        assertThat(loaded.get().data().get("list")).asList().containsExactly(1, 2, 3);
        assertThat(loaded.get().data().get("nested")).asInstanceOf(InstanceOfAssertFactories.MAP)
            .containsEntry("key", "value");
    }

    @Test
    @DisplayName("Should handle empty data and metadata")
    void testEmptyDataAndMetadata() throws Exception {
        // Given
        AgentState state = AgentState.builder("test-agent")
            .agentName("Test Agent")
            .build();

        // When
        persistenceService.saveState("test-agent", state).get();
        Optional<AgentState> loaded = persistenceService.loadState("test-agent").get();

        // Then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().data()).isEmpty();
        assertThat(loaded.get().metadata()).isEmpty();
    }

    // =========================================================================
    // Constructor variants
    // =========================================================================

    @Test
    @DisplayName("Should create service with 2-arg constructor (non-pretty-print)")
    void testConstructorNoPrettyPrint() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir, false);

        AgentState state = AgentState.builder("agent-1").agentName("Agent 1").data("x", 1).build();
        service.saveState("agent-1", state).get();

        Optional<AgentState> loaded = service.loadState("agent-1").get();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().data().get("x")).isEqualTo(1);
    }

    @Test
    @DisplayName("Should create service with default directory constructor")
    void testDefaultConstructorDoesNotThrow() {
        // Default constructor uses "data/persistence" which should be creatable
        // We just verify it can be instantiated without throwing
        FilePersistenceService service = new FilePersistenceService(tempDir.resolve("default-test"));
        assertThat(service).isNotNull();
    }

    // =========================================================================
    // restoreSnapshot edge cases
    // =========================================================================

    @Test
    @DisplayName("Should return empty optional when restoring non-existent snapshot")
    void testRestoreNonExistentSnapshot() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        Optional<AgentState> result = service.restoreSnapshot("agent-1", "does-not-exist").get();
        assertThat(result).isEmpty();
    }

    // =========================================================================
    // deleteState edge cases
    // =========================================================================

    @Test
    @DisplayName("Should handle deleteState when state file does not exist")
    void testDeleteNonExistentState() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        // Should not throw
        assertThatCode(() -> service.deleteState("non-existent").get()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should delete state and associated snapshots")
    void testDeleteStateWithSnapshots() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        AgentState state = AgentState.builder("agent-del").agentName("Del Agent").build();
        service.saveState("agent-del", state).get();
        service.createSnapshot("agent-del", "snap-1").get();
        service.createSnapshot("agent-del", "snap-2").get();

        // Verify snapshots exist
        assertThat(service.listSnapshots("agent-del").get()).hasSize(2);

        // Delete
        service.deleteState("agent-del").get();

        // Verify state gone
        assertThat(service.existsState("agent-del").get()).isFalse();
        // Snapshots also gone
        assertThat(service.listSnapshots("agent-del").get()).isEmpty();
    }

    // =========================================================================
    // listSnapshots edge cases
    // =========================================================================

    @Test
    @DisplayName("Should return empty list when no snapshot directory exists")
    void testListSnapshotsNoDirectory() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        List<String> snapshots = service.listSnapshots("agent-no-snapshots").get();
        assertThat(snapshots).isEmpty();
    }

    // =========================================================================
    // cleanupSnapshots edge cases
    // =========================================================================

    @Test
    @DisplayName("Should return 0 when no snapshot directory exists")
    void testCleanupSnapshotsNoDirectory() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        int deleted = service.cleanupSnapshots("agent-no-dir", 3).get();
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    @DisplayName("Should return 0 when snapshot count is within keepCount")
    void testCleanupSnapshotsWithinLimit() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        AgentState state = AgentState.builder("agent-within").agentName("Agent").build();
        service.saveState("agent-within", state).get();
        service.createSnapshot("agent-within", "s1").get();
        service.createSnapshot("agent-within", "s2").get();

        // keepCount >= actual count, nothing deleted
        int deleted = service.cleanupSnapshots("agent-within", 5).get();
        assertThat(deleted).isEqualTo(0);

        assertThat(service.listSnapshots("agent-within").get()).hasSize(2);
    }

    // =========================================================================
    // PersistenceStats
    // =========================================================================

    @Test
    @DisplayName("Should format size in KB")
    void testFormatTotalSizeKb() {
        long kbSize = 2048L; // 2 KB
        FilePersistenceService.PersistenceStats stats = new FilePersistenceService.PersistenceStats(1, 0, kbSize);

        assertThat(stats.formatTotalSize()).contains("KB");
    }

    @Test
    @DisplayName("Should format size in MB")
    void testFormatTotalSizeMb() {
        long mbSize = 2L * 1024 * 1024; // 2 MB
        FilePersistenceService.PersistenceStats stats = new FilePersistenceService.PersistenceStats(1, 0, mbSize);

        assertThat(stats.formatTotalSize()).contains("MB");
    }

    @Test
    @DisplayName("Should format size in bytes")
    void testFormatTotalSizeBytes() {
        FilePersistenceService.PersistenceStats stats = new FilePersistenceService.PersistenceStats(1, 0, 512L);

        assertThat(stats.formatTotalSize()).endsWith("B");
        assertThat(stats.formatTotalSize()).doesNotContain("KB");
        assertThat(stats.formatTotalSize()).doesNotContain("MB");
    }

    @Test
    @DisplayName("PersistenceStats.toString should contain expected fields")
    void testPersistenceStatsToString() {
        FilePersistenceService.PersistenceStats stats = new FilePersistenceService.PersistenceStats(3, 7, 512L);

        String str = stats.toString();
        assertThat(str).contains("3");
        assertThat(str).contains("7");
        assertThat(str).contains("PersistenceStats");
    }

    // =========================================================================
    // createSnapshot with empty string triggers auto-generation
    // =========================================================================

    @Test
    @DisplayName("Should auto-generate snapshot ID when empty string provided")
    void testAutoGenerateSnapshotIdForEmptyString() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        AgentState state = AgentState.builder("agent-snap").agentName("Snap Agent").build();
        service.saveState("agent-snap", state).get();

        String snapshotId = service.createSnapshot("agent-snap", "").get();

        assertThat(snapshotId).isNotNull();
        assertThat(snapshotId).matches("\\d{8}-\\d{6}-\\d{3}");
    }

    @Test
    @DisplayName("Should throw PersistenceException when creating snapshot with no state")
    void testCreateSnapshotNoState() {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        assertThatThrownBy(() -> service.createSnapshot("no-state-agent", "snap").get())
                .hasCauseInstanceOf(PersistenceException.class);
    }

    // =========================================================================
    // getStats edge case - empty directories
    // =========================================================================

    @Test
    @DisplayName("Should return zero stats when directories are empty")
    void testGetStatsEmpty() {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        FilePersistenceService.PersistenceStats stats = service.getStats();

        assertThat(stats.totalStates()).isEqualTo(0);
        assertThat(stats.totalSnapshots()).isEqualTo(0);
        assertThat(stats.totalSizeBytes()).isEqualTo(0);
    }

    // =========================================================================
    // Overwrite existing state
    // =========================================================================

    @Test
    @DisplayName("Should overwrite existing state on repeated saves")
    void testOverwriteExistingState() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        AgentState v1 = AgentState.builder("agent-ow").data("v", 1).build();
        AgentState v2 = AgentState.builder("agent-ow").data("v", 2).build();

        service.saveState("agent-ow", v1).get();
        service.saveState("agent-ow", v2).get();

        Optional<AgentState> loaded = service.loadState("agent-ow").get();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().data().get("v")).isEqualTo(2);
    }

    // =========================================================================
    // Multiple agents isolation
    // =========================================================================

    @Test
    @DisplayName("Should isolate states between different agents")
    void testAgentIsolation() throws Exception {
        FilePersistenceService service = new FilePersistenceService(tempDir);

        service.saveState("agent-a", AgentState.builder("agent-a").data("k", "a").build()).get();
        service.saveState("agent-b", AgentState.builder("agent-b").data("k", "b").build()).get();

        Optional<AgentState> a = service.loadState("agent-a").get();
        Optional<AgentState> b = service.loadState("agent-b").get();

        assertThat(a.get().data().get("k")).isEqualTo("a");
        assertThat(b.get().data().get("k")).isEqualTo("b");
    }
}
