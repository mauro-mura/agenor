package dev.agenor.runtime.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.annotations.JenticPersistenceConfig;
import dev.agenor.core.persistence.AgentState;
import dev.agenor.core.persistence.PersistenceService;
import dev.agenor.core.persistence.PersistenceStrategy;
import dev.agenor.core.persistence.Stateful;
import dev.agenor.runtime.agent.BaseAgent;

/**
 * Manages automatic persistence for agents based on their configuration.
 * Handles periodic saves, snapshots, and lifecycle-based persistence.
 *
 * INTEGRATION WITH JENTIC RUNTIME:
 * - Register as a service: runtime.builder().service(PersistenceManager.class, manager)
 * - Agents are automatically registered when added to runtime
 * - Lifecycle hooks integrate with agent stop process
 */
public class PersistenceManager {

    private static final Logger log = LoggerFactory.getLogger(PersistenceManager.class);

    private final PersistenceService persistenceService;
    private final ScheduledExecutorService scheduler;
    private final Map<String, AgentPersistenceContext> agentContexts;
    private volatile boolean running = false;

    public PersistenceManager(PersistenceService persistenceService) {
        this.persistenceService = persistenceService;
        this.scheduler = Executors.newScheduledThreadPool(
            2,
            Thread.ofVirtual().name("persistence-manager-", 0).factory()
        );
        this.agentContexts = new ConcurrentHashMap<>();
    }

    /**
     * Get the persistence service instance
     */
    public PersistenceService getPersistenceService() {
        return persistenceService;
    }

    /**
     * Start the persistence manager
     */
    public CompletableFuture<Void> start() {
        if (running) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Starting persistence manager");
        running = true;

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Stop the persistence manager
     */
    public CompletableFuture<Void> stop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Stopping persistence manager");
        running = false;

        return CompletableFuture.runAsync(() -> {
            // Cancel all scheduled tasks
            for (AgentPersistenceContext context : agentContexts.values()) {
                context.cancelScheduledTasks();
            }

            // Save all agent states before shutdown
            log.info("Saving all agent states before shutdown...");
            for (AgentPersistenceContext context : agentContexts.values()) {
                try {
                    AgentState state = context.statefulAgent.captureState();
                    persistenceService.saveState(context.agent.getAgentId(), state)
                        .get(5, TimeUnit.SECONDS);
                    log.debug("Saved final state for agent: {}", context.agent.getAgentId());
                } catch (Exception e) {
                    log.error("Failed to save final state for agent: {}",
                             context.agent.getAgentId(), e);
                }
            }

            // Shutdown scheduler
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            agentContexts.clear();
            log.info("Persistence manager stopped successfully");
        });
    }

    /**
     * Register an agent for automatic persistence.
     * Called automatically by JenticRuntime when agents are registered.
     */
    public void registerAgent(Agent agent) {
        if (!(agent instanceof Stateful)) {
            log.debug("Agent {} does not implement Stateful, skipping persistence registration",
                     agent.getAgentId());
            return;
        }

        Stateful statefulAgent = (Stateful) agent;
        JenticPersistenceConfig config = agent.getClass().getAnnotation(JenticPersistenceConfig.class);

        if (config == null) {
            log.debug("Agent {} has no @JenticPersistenceConfig, using manual persistence",
                     agent.getAgentId());
            return;
        }

        AgentPersistenceContext context = new AgentPersistenceContext(
            agent,
            statefulAgent,
            config
        );

        agentContexts.put(agent.getAgentId(), context);

        // Setup automatic persistence based on strategy
        setupAutomaticPersistence(context);

        log.info("Registered agent {} for {} persistence",
                agent.getAgentId(), config.strategy());
    }

    /**
     * Unregister an agent from automatic persistence.
     * Called automatically by JenticRuntime when agents are unregistered.
     */
    public void unregisterAgent(String agentId) {
        AgentPersistenceContext context = agentContexts.remove(agentId);
        if (context != null) {
            context.cancelScheduledTasks();

            // Save final state if agent is stateful
            try {
                AgentState state = context.statefulAgent.captureState();
                persistenceService.saveState(agentId, state)
                    .get(5, TimeUnit.SECONDS);
                log.info("Saved final state for unregistered agent: {}", agentId);
            } catch (Exception e) {
                log.error("Failed to save final state for agent: {}", agentId, e);
            }
        }
    }

    /**
     * Manually save agent state
     */
    public CompletableFuture<Void> saveAgent(Agent agent) {
        if (!(agent instanceof Stateful statefulAgent)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Agent does not implement Stateful")
            );
        }

        AgentState state = statefulAgent.captureState();
        return persistenceService.saveState(agent.getAgentId(), state)
            .thenRun(() -> log.debug("Manually saved state for agent: {}", agent.getAgentId()));
    }

    /**
     * Manually load and restore agent state
     */
    public CompletableFuture<Boolean> loadAgent(Agent agent) {
        if (!(agent instanceof Stateful statefulAgent)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Agent does not implement Stateful")
            );
        }

        return persistenceService.loadState(agent.getAgentId())
            .thenApply(optionalState -> {
                if (optionalState.isPresent()) {
                    statefulAgent.restoreState(optionalState.get());
                    log.info("Restored state for agent: {} (version: {})",
                            agent.getAgentId(), optionalState.get().version());
                    return true;
                } else {
                    log.debug("No saved state found for agent: {}", agent.getAgentId());
                    return false;
                }
            });
    }

    /**
     * Create a snapshot for an agent
     */
    public CompletableFuture<String> createSnapshot(String agentId) {
        AgentPersistenceContext context = agentContexts.get(agentId);
        if (context == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Agent not registered: " + agentId)
            );
        }

        // First save current state
        AgentState state = context.statefulAgent.captureState();
        return persistenceService.saveState(agentId, state)
            .thenCompose(v -> persistenceService.createSnapshot(agentId, null))
            .thenApply(snapshotId -> {
                log.info("Created snapshot {} for agent {}", snapshotId, agentId);

                // Cleanup old snapshots if configured
                JenticPersistenceConfig config = context.config;
                if (config.maxSnapshots() > 0 && persistenceService instanceof FilePersistenceService fps) {
                    fps.cleanupSnapshots(agentId, config.maxSnapshots())
                        .thenAccept(deleted -> {
                            if (deleted > 0) {
                                log.debug("Cleaned up {} old snapshots for agent {}",
                                         deleted, agentId);
                            }
                        });
                }

                return snapshotId;
            });
    }

    /**
     * Restore agent from a specific snapshot
     */
    public CompletableFuture<Boolean> restoreSnapshot(String agentId, String snapshotId) {
        AgentPersistenceContext context = agentContexts.get(agentId);
        if (context == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Agent not registered: " + agentId)
            );
        }

        return persistenceService.restoreSnapshot(agentId, snapshotId)
            .thenApply(optionalState -> {
                if (optionalState.isPresent()) {
                    context.statefulAgent.restoreState(optionalState.get());
                    log.info("Restored agent {} from snapshot {}", agentId, snapshotId);
                    return true;
                } else {
                    log.warn("Snapshot {} not found for agent {}", snapshotId, agentId);
                    return false;
                }
            });
    }

    /**
     * Get persistence statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("registeredAgents", agentContexts.size());
        stats.put("running", running);

        // Count agents by strategy
        Map<PersistenceStrategy, Long> strategyCount = new ConcurrentHashMap<>();
        for (AgentPersistenceContext context : agentContexts.values()) {
            PersistenceStrategy strategy = context.config.strategy();
            strategyCount.merge(strategy, 1L, Long::sum);
        }
        stats.put("agentsByStrategy", strategyCount);

        if (persistenceService instanceof FilePersistenceService fps) {
            FilePersistenceService.PersistenceStats persistenceStats = fps.getStats();
            stats.put("totalStates", persistenceStats.totalStates());
            stats.put("totalSnapshots", persistenceStats.totalSnapshots());
            stats.put("totalSize", persistenceStats.formatTotalSize());
        }

        return stats;
    }

    // ========== PRIVATE HELPER METHODS ==========

    private void setupAutomaticPersistence(AgentPersistenceContext context) {
        PersistenceStrategy strategy = context.config.strategy();

        switch (strategy) {
            case PERIODIC -> setupPeriodicPersistence(context);
            case ON_STOP -> setupOnStopPersistence(context);
            case DEBOUNCED -> setupDebouncedPersistence(context);
            case SNAPSHOT -> setupSnapshotPersistence(context);
            case IMMEDIATE -> log.warn("IMMEDIATE persistence not yet implemented for agent {}",
                                      context.agent.getAgentId());
            case MANUAL -> {} // No automatic persistence
        }

        // Setup automatic snapshots if enabled
        if (context.config.autoSnapshot()) {
            setupAutoSnapshots(context);
        }
    }

    private void setupPeriodicPersistence(AgentPersistenceContext context) {
        Duration interval = parseDuration(context.config.interval());

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    if (context.agent.isRunning()) {
                        AgentState state = context.statefulAgent.captureState();
                        persistenceService.saveState(context.agent.getAgentId(), state)
                            .exceptionally(throwable -> {
                                log.error("Failed to save periodic state for agent {}",
                                         context.agent.getAgentId(), throwable);
                                return null;
                            });
                    }
                } catch (Exception e) {
                    log.error("Error in periodic persistence for agent {}",
                             context.agent.getAgentId(), e);
                }
            },
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        context.scheduledTasks.add(future);
        log.debug("Setup periodic persistence for agent {} (interval: {})",
                 context.agent.getAgentId(), interval);
    }

    private void setupOnStopPersistence(AgentPersistenceContext context) {
        // Add shutdown hook if agent is BaseAgent
        if (context.agent instanceof BaseAgent baseAgent) {
            baseAgent.onStopHook(() -> {
                try {
                    AgentState state = context.statefulAgent.captureState();
                    persistenceService.saveState(context.agent.getAgentId(), state)
                        .get(5, TimeUnit.SECONDS);
                    log.info("Saved state on stop for agent {}", context.agent.getAgentId());
                } catch (Exception e) {
                    log.error("Failed to save state on stop for agent {}",
                             context.agent.getAgentId(), e);
                }
            });
        }

        log.debug("Setup on-stop persistence for agent {}", context.agent.getAgentId());
    }

    private void setupDebouncedPersistence(AgentPersistenceContext context) {
        Duration debounceInterval = parseDuration(context.config.interval());

        // Create a debounced save task
        ScheduledFuture<?>[] currentTask = new ScheduledFuture<?>[1];

        context.debouncedSave = () -> {
            // Cancel previous task if exists
            if (currentTask[0] != null && !currentTask[0].isDone()) {
                currentTask[0].cancel(false);
            }

            // Schedule new save
            currentTask[0] = scheduler.schedule(
                () -> {
                    try {
                        AgentState state = context.statefulAgent.captureState();
                        persistenceService.saveState(context.agent.getAgentId(), state)
                            .exceptionally(throwable -> {
                                log.error("Failed to save debounced state for agent {}",
                                         context.agent.getAgentId(), throwable);
                                return null;
                            });
                    } catch (Exception e) {
                        log.error("Error in debounced persistence for agent {}",
                                 context.agent.getAgentId(), e);
                    }
                },
                debounceInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
        };

        log.debug("Setup debounced persistence for agent {} (interval: {})",
                 context.agent.getAgentId(), debounceInterval);
    }

    private void setupSnapshotPersistence(AgentPersistenceContext context) {
        Duration interval = parseDuration(context.config.snapshotInterval());

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    if (context.agent.isRunning()) {
                        createSnapshot(context.agent.getAgentId())
                            .exceptionally(throwable -> {
                                log.error("Failed to create automatic snapshot for agent {}",
                                         context.agent.getAgentId(), throwable);
                                return null;
                            });
                    }
                } catch (Exception e) {
                    log.error("Error in snapshot creation for agent {}",
                             context.agent.getAgentId(), e);
                }
            },
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        context.scheduledTasks.add(future);
        log.debug("Setup snapshot persistence for agent {} (interval: {})",
                 context.agent.getAgentId(), interval);
    }

    private void setupAutoSnapshots(AgentPersistenceContext context) {
        Duration interval = parseDuration(context.config.snapshotInterval());

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    if (context.agent.isRunning()) {
                        createSnapshot(context.agent.getAgentId())
                            .exceptionally(throwable -> {
                                log.error("Failed to create auto-snapshot for agent {}",
                                         context.agent.getAgentId(), throwable);
                                return null;
                            });
                    }
                } catch (Exception e) {
                    log.error("Error in auto-snapshot for agent {}",
                             context.agent.getAgentId(), e);
                }
            },
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        context.scheduledTasks.add(future);
        log.debug("Setup auto-snapshots for agent {} (interval: {})",
                 context.agent.getAgentId(), interval);
    }

    private Duration parseDuration(String durationString) {
        if (durationString == null || durationString.trim().isEmpty()) {
            return Duration.ofSeconds(60);
        }

        try {
            durationString = durationString.trim().toLowerCase();

            if (durationString.endsWith("ms")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 2));
                return Duration.ofMillis(value);
            } else if (durationString.endsWith("s")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofSeconds(value);
            } else if (durationString.endsWith("m")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofMinutes(value);
            } else if (durationString.endsWith("h")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofHours(value);
            } else if (durationString.endsWith("d")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofDays(value);
            } else {
                long value = Long.parseLong(durationString);
                return Duration.ofSeconds(value);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid duration format: '{}', using 60s default", durationString);
            return Duration.ofSeconds(60);
        }
    }

    /**
     * Context for managing persistence of a single agent
     */
    private static class AgentPersistenceContext {
        final Agent agent;
        final Stateful statefulAgent;
        final JenticPersistenceConfig config;
        final java.util.List<ScheduledFuture<?>> scheduledTasks;
        Runnable debouncedSave;

        AgentPersistenceContext(Agent agent, Stateful statefulAgent, JenticPersistenceConfig config) {
            this.agent = agent;
            this.statefulAgent = statefulAgent;
            this.config = config;
            this.scheduledTasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        }

        void cancelScheduledTasks() {
            for (ScheduledFuture<?> task : scheduledTasks) {
                task.cancel(false);
            }
            scheduledTasks.clear();
        }
    }
}
