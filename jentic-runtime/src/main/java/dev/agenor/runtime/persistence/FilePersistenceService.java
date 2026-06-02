package dev.agenor.runtime.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.agenor.core.persistence.AgentState;
import dev.agenor.core.persistence.PersistenceException;
import dev.agenor.core.persistence.PersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-based persistence service using JSON format.
 * Stores agent states as JSON files with support for snapshots.
 */
public class FilePersistenceService implements PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(FilePersistenceService.class);

    private final Path dataDirectory;
    private final Path snapshotsDirectory;
    private final ObjectMapper objectMapper;
    private final Map<String, ReadWriteLock> agentLocks;
    private final boolean prettyPrint;

    /**
     * Create a file persistence service with default directory
     */
    public FilePersistenceService() {
        this(Paths.get("data", "persistence"));
    }

    /**
     * Create a file persistence service with custom directory
     */
    public FilePersistenceService(Path dataDirectory) {
        this(dataDirectory, true);
    }

    /**
     * Create a file persistence service with custom settings
     */
    public FilePersistenceService(Path dataDirectory, boolean prettyPrint) {
        this.dataDirectory = dataDirectory;
        this.snapshotsDirectory = dataDirectory.resolve("snapshots");
        this.prettyPrint = prettyPrint;
        this.agentLocks = new HashMap<>();

        // Configure JSON mapper
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        if (prettyPrint) {
            this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        }

        // Create directories
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(snapshotsDirectory);
            log.info("File persistence service initialized: {}", dataDirectory.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create persistence directories", e);
            throw new PersistenceException(
                "system",
                PersistenceException.PersistenceOperation.SAVE,
                "Failed to create persistence directories: " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public CompletableFuture<Void> saveState(String agentId, AgentState state) {
        return CompletableFuture.runAsync(() -> {
            ReadWriteLock lock = getLock(agentId);
            lock.writeLock().lock();
            try {
                Path stateFile = getStateFile(agentId);

                // Write to temporary file first
                Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                objectMapper.writeValue(tempFile.toFile(), state);

                // Atomic move to actual file
                Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING,
                          StandardCopyOption.ATOMIC_MOVE);

                log.debug("Saved state for agent: {} (version: {})", agentId, state.version());

            } catch (IOException e) {
                log.error("Failed to save state for agent: {}", agentId, e);
                throw new PersistenceException(
                    agentId,
                    PersistenceException.PersistenceOperation.SAVE,
                    "Failed to save agent state: " + e.getMessage(),
                    e
                );
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<AgentState>> loadState(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getLock(agentId);
            lock.readLock().lock();
            try {
                Path stateFile = getStateFile(agentId);

                if (!Files.exists(stateFile)) {
                    log.debug("No saved state found for agent: {}", agentId);
                    return Optional.empty();
                }

                AgentState state = objectMapper.readValue(stateFile.toFile(), AgentState.class);
                log.debug("Loaded state for agent: {} (version: {})", agentId, state.version());

                return Optional.of(state);

            } catch (IOException e) {
                log.error("Failed to load state for agent: {}", agentId, e);
                throw new PersistenceException(
                    agentId,
                    PersistenceException.PersistenceOperation.LOAD,
                    "Failed to load agent state: " + e.getMessage(),
                    e
                );
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteState(String agentId) {
        return CompletableFuture.runAsync(() -> {
            ReadWriteLock lock = getLock(agentId);
            lock.writeLock().lock();
            try {
                Path stateFile = getStateFile(agentId);

                if (Files.exists(stateFile)) {
                    Files.delete(stateFile);
                    log.info("Deleted state for agent: {}", agentId);
                }

                // Also delete all snapshots
                Path agentSnapshotDir = getAgentSnapshotDirectory(agentId);
                if (Files.exists(agentSnapshotDir)) {
                    try (Stream<Path> paths = Files.walk(agentSnapshotDir)) {
                        paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.warn("Failed to delete snapshot file: {}", path, e);
                                }
                            });
                    }
                    log.info("Deleted all snapshots for agent: {}", agentId);
                }

            } catch (IOException e) {
                log.error("Failed to delete state for agent: {}", agentId, e);
                throw new PersistenceException(
                    agentId,
                    PersistenceException.PersistenceOperation.DELETE,
                    "Failed to delete agent state: " + e.getMessage(),
                    e
                );
            } finally {
                lock.writeLock().unlock();
                agentLocks.remove(agentId);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> existsState(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            Path stateFile = getStateFile(agentId);
            return Files.exists(stateFile);
        });
    }


    @Override
    public CompletableFuture<String> createSnapshot(String agentId, String snapshotId) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getLock(agentId);
            lock.readLock().lock();
            try {
                Path stateFile = getStateFile(agentId);

                if (!Files.exists(stateFile)) {
                    throw new PersistenceException(
                            agentId,
                            PersistenceException.PersistenceOperation.SNAPSHOT,
                            "Cannot create snapshot: no state file found"
                    );
                }

                // Generate snapshot ID if not provided
                final String effectiveSnapshotId;
                if (snapshotId == null || snapshotId.trim().isEmpty()) {
                    effectiveSnapshotId = generateSnapshotId();
                } else {
                    effectiveSnapshotId = snapshotId;
                }

                // Create agent snapshot directory
                Path agentSnapshotDir = getAgentSnapshotDirectory(agentId);
                Files.createDirectories(agentSnapshotDir);

                // Copy state file to snapshot - USA effectiveSnapshotId invece di snapshotId
                Path snapshotFile = agentSnapshotDir.resolve(effectiveSnapshotId + ".json");
                Files.copy(stateFile, snapshotFile, StandardCopyOption.REPLACE_EXISTING);

                log.info("Created snapshot for agent: {} (snapshot: {})", agentId, effectiveSnapshotId);

                return effectiveSnapshotId;

            } catch (IOException e) {
                log.error("Failed to create snapshot for agent: {}", agentId, e);
                throw new PersistenceException(
                        agentId,
                        PersistenceException.PersistenceOperation.SNAPSHOT,
                        "Failed to create snapshot: " + e.getMessage(),
                        e
                );
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<AgentState>> restoreSnapshot(String agentId, String snapshotId) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getLock(agentId);
            lock.writeLock().lock();
            try {
                Path snapshotFile = getAgentSnapshotDirectory(agentId).resolve(snapshotId + ".json");

                if (!Files.exists(snapshotFile)) {
                    log.warn("Snapshot not found: {} for agent: {}", snapshotId, agentId);
                    return Optional.empty();
                }

                // Load snapshot
                AgentState state = objectMapper.readValue(snapshotFile.toFile(), AgentState.class);

                // Restore to current state file
                Path stateFile = getStateFile(agentId);
                objectMapper.writeValue(stateFile.toFile(), state);

                log.info("Restored snapshot for agent: {} (snapshot: {})", agentId, snapshotId);

                return Optional.of(state);

            } catch (IOException e) {
                log.error("Failed to restore snapshot for agent: {}", agentId, e);
                throw new PersistenceException(
                    agentId,
                    PersistenceException.PersistenceOperation.RESTORE,
                    "Failed to restore snapshot: " + e.getMessage(),
                    e
                );
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listSnapshots(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path agentSnapshotDir = getAgentSnapshotDirectory(agentId);

                if (!Files.exists(agentSnapshotDir)) {
                    return Collections.emptyList();
                }

                try (Stream<Path> paths = Files.list(agentSnapshotDir)) {
                    List<String> snapshots = paths
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> {
                            String fileName = p.getFileName().toString();
                            return fileName.substring(0, fileName.length() - 5); // Remove .json
                        })
                        .sorted(Comparator.reverseOrder()) // Most recent first
                        .collect(Collectors.toList());

                    log.debug("Found {} snapshots for agent: {}", snapshots.size(), agentId);
                    return snapshots;
                }

            } catch (IOException e) {
                log.error("Failed to list snapshots for agent: {}", agentId, e);
                return Collections.emptyList();
            }
        });
    }

    /**
     * Clean up old snapshots, keeping only the most recent N
     */
    public CompletableFuture<Integer> cleanupSnapshots(String agentId, int keepCount) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path agentSnapshotDir = getAgentSnapshotDirectory(agentId);

                if (!Files.exists(agentSnapshotDir)) {
                    return 0;
                }

                try (Stream<Path> paths = Files.list(agentSnapshotDir)) {
                    List<Path> snapshots = paths
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted((a, b) -> {
                            try {
                                return Files.getLastModifiedTime(b)
                                    .compareTo(Files.getLastModifiedTime(a));
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .collect(Collectors.toList());

                    if (snapshots.size() <= keepCount) {
                        return 0;
                    }

                    // Delete old snapshots
                    int deletedCount = 0;
                    for (int i = keepCount; i < snapshots.size(); i++) {
                        try {
                            Files.delete(snapshots.get(i));
                            deletedCount++;
                        } catch (IOException e) {
                            log.warn("Failed to delete snapshot: {}", snapshots.get(i), e);
                        }
                    }

                    log.info("Cleaned up {} old snapshots for agent: {}", deletedCount, agentId);
                    return deletedCount;
                }

            } catch (IOException e) {
                log.error("Failed to cleanup snapshots for agent: {}", agentId, e);
                return 0;
            }
        });
    }

    /**
     * Get statistics about persisted data
     */
    public PersistenceStats getStats() {
        try {
            long totalStates = 0;
            long totalSnapshots = 0;
            long totalSize = 0;

            // Count state files
            if (Files.exists(dataDirectory)) {
                try (Stream<Path> paths = Files.list(dataDirectory)) {
                    totalStates = paths
                        .filter(p -> p.toString().endsWith(".json"))
                        .count();
                }
            }

            // Count snapshots and calculate total size
            if (Files.exists(snapshotsDirectory)) {
                try (Stream<Path> paths = Files.walk(snapshotsDirectory)) {
                    List<Path> snapshotFiles = paths
                        .filter(p -> p.toString().endsWith(".json"))
                        .collect(Collectors.toList());

                    totalSnapshots = snapshotFiles.size();

                    for (Path file : snapshotFiles) {
                        totalSize += Files.size(file);
                    }
                }
            }

            // Calculate state file sizes
            if (Files.exists(dataDirectory)) {
                try (Stream<Path> paths = Files.list(dataDirectory)) {
                    for (Path file : paths.filter(p -> p.toString().endsWith(".json"))
                                         .collect(Collectors.toList())) {
                        totalSize += Files.size(file);
                    }
                }
            }

            return new PersistenceStats(totalStates, totalSnapshots, totalSize);

        } catch (IOException e) {
            log.error("Failed to get persistence stats", e);
            return new PersistenceStats(0, 0, 0);
        }
    }

    // Helper methods

    private Path getStateFile(String agentId) {
        return dataDirectory.resolve(agentId + ".json");
    }

    private Path getAgentSnapshotDirectory(String agentId) {
        return snapshotsDirectory.resolve(agentId);
    }

    private synchronized ReadWriteLock getLock(String agentId) {
        return agentLocks.computeIfAbsent(agentId, k -> new ReentrantReadWriteLock());
    }

    private String generateSnapshotId() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(java.time.ZoneId.systemDefault())
            .format(Instant.now());
    }

    /**
     * Statistics about persisted data
     */
    public record PersistenceStats(
        long totalStates,
        long totalSnapshots,
        long totalSizeBytes
    ) {
        public String formatTotalSize() {
            if (totalSizeBytes < 1024) {
                return totalSizeBytes + " B";
            } else if (totalSizeBytes < 1024 * 1024) {
                return String.format("%.2f KB", totalSizeBytes / 1024.0);
            } else {
                return String.format("%.2f MB", totalSizeBytes / (1024.0 * 1024.0));
            }
        }

        @Override
        public String toString() {
            return String.format("PersistenceStats[states=%d, snapshots=%d, size=%s]",
                               totalStates, totalSnapshots, formatTotalSize());
        }
    }
}
