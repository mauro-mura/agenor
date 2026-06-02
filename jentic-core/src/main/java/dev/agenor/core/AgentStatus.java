package dev.agenor.core;

/**
 * Enumeration of possible agent states
 */
public enum AgentStatus {
    /**
     * Agent is running and healthy
     */
    RUNNING,

    /**
     * Agent is stopped
     */
    STOPPED,

    /**
     * Agent is starting up
     */
    STARTING,

    /**
     * Agent is shutting down
     */
    STOPPING,

    /**
     * Agent encountered an error
     */
    ERROR,

    /**
     * Agent crashed and needs recovery
     */
    CRASHED,

    /**
     * Agent is temporarily suspended
     */
    SUSPENDED,

    /**
     * Agent status is unknown
     */
    UNKNOWN
}
