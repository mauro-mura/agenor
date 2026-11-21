package dev.agenor.runtime.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.LifecycleHooks;
import dev.agenor.core.annotations.PersistenceConfig;
import dev.agenor.core.persistence.AgentState;
import dev.agenor.core.persistence.PersistenceService;
import dev.agenor.core.persistence.PersistenceStrategy;
import dev.agenor.core.persistence.Stateful;

/**
 * Manages automatic persistence for agents based on their configuration.
 * Handles periodic saves, snapshots, and lifecycle-based persistence.
 *
 * INTEGRATION WITH AGENOR RUNTIME:
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
            log.info("Saving all agent states before shutdown...");
            for (AgentPersistenceContext context : agentContexts.values()) {
                saveFinalState(context);
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
     * Called automatically by AgenorRuntime when agents are registered.
     */
    public void registerAgent(Agent agent) {
        if (!(agent instanceof Stateful)) {
            log.debug("Agent {} does not implement Stateful, skipping persistence registration",
                     agent.getAgentId());
            return;
        }

        Stateful statefulAgent = (Stateful) agent;
        PersistenceConfig config = agent.getClass().getAnnotation(PersistenceConfig.class);

        if (config == null) {
            log.debug("Agent {} has no @PersistenceConfig, using manual persistence",
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
     * Called automatically by AgenorRuntime when agents are unregistered.
     */
    public void unregisterAgent(String agentId) {
        AgentPersistenceContext context = agentContexts.remove(agentId);
        if (context != null) {
            saveFinalState(context);
        }
    }

    /**
     * Stops the agent's scheduled writes and saves its state one last time.
     *
     * <p>Cancelling a scheduled task does not stop an execution already running, and that
     * execution captured its state earlier. Taking the context's write lock waits for it to
     * finish, so it cannot land after the final save and overwrite it with an older state, and
     * nothing is still writing when this returns.
     */
    private void saveFinalState(AgentPersistenceContext context) {
        String agentId = context.agent.getAgentId();
        context.closed = true;
        context.cancelScheduledTasks();
        context.writeLock.lock();
        try {
            AgentState state = context.statefulAgent.captureState();
            persistenceService.saveState(agentId, state).get(5, TimeUnit.SECONDS);
            log.debug("Saved final state for agent: {}", agentId);
        } catch (Exception e) {
            log.error("Failed to save final state for agent: {}", agentId, e);
        } finally {
            context.writeLock.unlock();
        }
    }

    /**
     * Runs one scheduled write and waits for it to complete, under the context's write lock,
     * so that {@link #saveFinalState} can wait for it in turn. Does nothing once the context
     * is closed.
     */
    private void runScheduledWrite(AgentPersistenceContext context, String what,
                                   Supplier<CompletableFuture<?>> write) {
        context.writeLock.lock();
        try {
            if (!context.closed) {
                write.get().join();
            }
        } catch (Exception e) {
            log.error("Failed to {} for agent {}", what, context.agent.getAgentId(), e);
        } finally {
            context.writeLock.unlock();
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
                PersistenceConfig config = context.config;
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
            () -> runScheduledWrite(context, "save periodic state", () ->
                context.agent.isRunning()
                    ? persistenceService.saveState(context.agent.getAgentId(),
                                                   context.statefulAgent.captureState())
                    : CompletableFuture.completedFuture(null)),
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        context.scheduledTasks.add(future);
        log.debug("Setup periodic persistence for agent {} (interval: {})",
                 context.agent.getAgentId(), interval);
    }

    private void setupOnStopPersistence(AgentPersistenceContext context) {
        // Add shutdown hook if the agent supports lifecycle hooks (BaseAgent does)
        if (context.agent instanceof LifecycleHooks hooks) {
            hooks.onStopHook(() -> {
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
                () -> runScheduledWrite(context, "save debounced state", () ->
                    persistenceService.saveState(context.agent.getAgentId(),
                                                 context.statefulAgent.captureState())),
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
            () -> runScheduledWrite(context, "create automatic snapshot", () ->
                context.agent.isRunning()
                    ? createSnapshot(context.agent.getAgentId())
                    : CompletableFuture.completedFuture(null)),
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
            () -> runScheduledWrite(context, "create auto-snapshot", () ->
                context.agent.isRunning()
                    ? createSnapshot(context.agent.getAgentId())
                    : CompletableFuture.completedFuture(null)),
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
        final PersistenceConfig config;
        final java.util.List<ScheduledFuture<?>> scheduledTasks;
        /** Held for each scheduled write and for the final save, which must not interleave. */
        final ReentrantLock writeLock = new ReentrantLock();
        /** Set before the final save; a scheduled write that runs after it does nothing. */
        volatile boolean closed;
        Runnable debouncedSave;

        AgentPersistenceContext(Agent agent, Stateful statefulAgent, PersistenceConfig config) {
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
