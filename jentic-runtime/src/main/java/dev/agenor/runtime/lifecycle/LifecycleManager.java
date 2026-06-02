package dev.agenor.runtime.lifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.AgentStatus;

/**
 * Manager for agent lifecycle operations with timeout and error handling.
 * Tracks agent states and provides lifecycle events to listeners.
 */
public class LifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(LifecycleManager.class);

    private final Map<String, AgentStatus> agentStates = new ConcurrentHashMap<>();
    private final List<LifecycleListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Start an agent with timeout and state tracking.
     * <p>
     * STARTING is set synchronously before returning the future, so callers
     * can observe STARTING immediately after this method returns.
     * Chaining directly on agent.start() avoids the ForkJoinPool work-stealing
     * race that arose from wrapping agent.start().join() in a second runAsync.
     */
    public CompletableFuture<Void> startAgent(Agent agent, Duration timeout) {
        String agentId = agent.getAgentId();
        log.info("Starting agent: {} with timeout: {}", agentId, timeout);

        // Set STARTING synchronously before any async work begins
        updateStatus(agentId, AgentStatus.STARTING);

        return agent.start()
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    if (ex == null) {
                        updateStatus(agentId, AgentStatus.RUNNING);
                        log.info("Agent started successfully: {}", agentId);
                        return (Void) null;
                    }
                    Throwable cause = (ex instanceof CompletionException) ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.error("Agent startup timeout after {}: {}", timeout, agentId);
                        updateStatus(agentId, AgentStatus.ERROR);
                        throw new LifecycleException(agentId, "Startup timeout after " + timeout);
                    } else {
                        log.error("Agent startup failed: {}", agentId, cause);
                        updateStatus(agentId, AgentStatus.CRASHED);
                        throw new LifecycleException(agentId, "Startup failed");
                    }
                });
    }

    /**
     * Stop an agent with timeout and state tracking.
     * <p>
     * STOPPING is set synchronously before returning the future.
     */
    public CompletableFuture<Void> stopAgent(Agent agent, Duration timeout) {
        String agentId = agent.getAgentId();
        log.info("Stopping agent: {} with timeout: {}", agentId, timeout);

        // Set STOPPING synchronously before any async work begins
        updateStatus(agentId, AgentStatus.STOPPING);

        return agent.stop()
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((result, ex) -> {
                    if (ex == null) {
                        updateStatus(agentId, AgentStatus.STOPPED);
                        log.info("Agent stopped successfully: {}", agentId);
                        return (Void) null;
                    }
                    Throwable cause = (ex instanceof CompletionException) ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.error("Agent shutdown timeout after {}: {}", timeout, agentId);
                        updateStatus(agentId, AgentStatus.ERROR);
                        throw new LifecycleException(agentId, "Shutdown timeout after " + timeout);
                    } else {
                        log.error("Agent shutdown failed: {}", agentId, cause);
                        updateStatus(agentId, AgentStatus.ERROR);
                        throw new LifecycleException(agentId, "Shutdown failed");
                    }
                });
    }

    /**
     * Get current status of an agent
     */
    public AgentStatus getAgentStatus(String agentId) {
        return agentStates.getOrDefault(agentId, AgentStatus.UNKNOWN);
    }

    /**
     * Check if agent is in a terminal error state
     */
    public boolean isInErrorState(String agentId) {
        AgentStatus status = getAgentStatus(agentId);
        return status == AgentStatus.ERROR || status == AgentStatus.CRASHED;
    }

    /**
     * Reset agent from error state to stopped
     */
    public void resetAgent(String agentId) {
        AgentStatus currentStatus = getAgentStatus(agentId);
        if (isInErrorState(agentId)) {
            updateStatus(agentId, AgentStatus.STOPPED);
            log.info("Reset agent {} from {} to STOPPED", agentId, currentStatus);
        }
    }

    /**
     * Add lifecycle listener
     */
    public void addLifecycleListener(LifecycleListener listener) {
        listeners.add(listener);
        log.debug("Added lifecycle listener: {}", listener.getClass().getSimpleName());
    }

    /**
     * Remove lifecycle listener
     */
    public void removeLifecycleListener(LifecycleListener listener) {
        listeners.remove(listener);
        log.debug("Removed lifecycle listener: {}", listener.getClass().getSimpleName());
    }

    /**
     * Get all current agent states
     */
    public Map<String, AgentStatus> getAllAgentStates() {
        return Map.copyOf(agentStates);
    }

    private void updateStatus(String agentId, AgentStatus newStatus) {
        AgentStatus oldStatus = agentStates.put(agentId, newStatus);
        log.debug("Agent {} status changed: {} -> {}", agentId, oldStatus, newStatus);
        notifyListeners(agentId, oldStatus, newStatus);
    }

    private void notifyListeners(String agentId, AgentStatus oldStatus, AgentStatus newStatus) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.onStatusChange(agentId, oldStatus, newStatus);
            } catch (Exception e) {
                log.error("Error notifying lifecycle listener: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}
