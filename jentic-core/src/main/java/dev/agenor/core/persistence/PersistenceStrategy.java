package dev.agenor.core.persistence;

/**
 * Strategy for when to persist agent state
 */
public enum PersistenceStrategy {
    /**
     * Save state manually when requested
     */
    MANUAL,

    /**
     * Save state on every change
     */
    IMMEDIATE,

    /**
     * Save state at fixed intervals
     */
    PERIODIC,

    /**
     * Save state when agent stops
     */
    ON_STOP,

    /**
     * Save state on changes with debouncing
     */
    DEBOUNCED,

    /**
     * Create periodic snapshots
     */
    SNAPSHOT
}
