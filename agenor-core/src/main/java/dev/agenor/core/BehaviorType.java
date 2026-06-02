package dev.agenor.core;

/**
 * Enumeration of supported behavior types
 */
public enum BehaviorType {
    /**
     * Execute once and stop
     */
    ONE_SHOT,

    /**
     * Execute repeatedly at fixed intervals
     */
    CYCLIC,

    /**
     * Wake up at specific times or conditions
     */
    WAKER,

    /**
     * Event-driven behavior (responds to messages/events)
     */
    EVENT_DRIVEN,

    /**
     * Custom behavior type
     */
    CUSTOM,

    /**
     * Execute only when condition is satisfied
     */
    CONDITIONAL,

    /**
     * Execute with rate limiting
     */
    THROTTLED,

    /**
     * Batch processing
     */
    BATCH,

    /**
     * Retry with backoff on failure
     */
    RETRY,

    /**
     * Circuit breaker pattern
     */
    CIRCUIT_BREAKER,

    /**
     * Cron-like scheduled execution
     */
    SCHEDULED,

    /**
     * Multi-stage pipeline processing
     */
    PIPELINE,

    /**
     * Execute behaviors sequentially one after another
     */
    SEQUENTIAL,

    /**
     * Execute multiple behaviors in parallel
     */
    PARALLEL,

    /**
     * Finite State Machine behavior with state transitions
     */
    FSM
}
