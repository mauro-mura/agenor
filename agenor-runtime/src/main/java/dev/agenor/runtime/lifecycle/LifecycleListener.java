package dev.agenor.runtime.lifecycle;

import dev.agenor.core.AgentStatus;

/**
 * Interface for listening to agent lifecycle events
 */
public interface LifecycleListener {

    /**
     * Called when an agent's status changes
     *
     * @param agentId   the agent ID
     * @param oldStatus the previous status (may be null)
     * @param newStatus the new status
     */
    void onStatusChange(String agentId, AgentStatus oldStatus, AgentStatus newStatus);

    /**
     * Default implementation that logs status changes
     */
    static LifecycleListener logging() {
        return new LoggingLifecycleListener();
    }
}
