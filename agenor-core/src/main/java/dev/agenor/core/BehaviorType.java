package dev.agenor.core;

/**
 * How the scheduler should drive a behavior.
 *
 * <p>This enum answers one question — <em>when does this work run</em> — for the three shapes
 * that answer differs for: once, repeatedly, or as a state machine that decides for itself.
 *
 * <p>It deliberately does <strong>not</strong> describe how work is wrapped. Retrying, rate
 * limiting, batching, gating on a condition and breaking a circuit are decorators around a call:
 * they are triggered by whatever calls them rather than by a scheduler. Twelve constants that
 * described wrappers or composites were removed in 0.30.0; the changelog for that release names
 * where each concern belongs instead.
 *
 * <p>Composition is a third thing again, and it lives in
 * {@link dev.agenor.core.composite.CompositeBehavior}: build the composite, and let its
 * {@link dev.agenor.core.composite.SchedulingHint} tell the scheduler how to drive it. A
 * composite does not declare a type of its own — it derives one from that hint.
 */
public enum BehaviorType {
    /**
     * Execute once and stop.
     *
     * <p>Pair with {@code @Behavior(initialDelay = "...")} to run once after a delay.
     */
    ONE_SHOT,

    /**
     * Execute repeatedly at fixed intervals
     */
    CYCLIC,

    /**
     * Finite State Machine behavior with state transitions
     */
    FSM
}
