package dev.jentic.core.composite;

/**
 * Declares how a {@link CompositeBehavior} wants to be driven by the scheduler
 * when registered via {@code agent.addBehavior()}.
 *
 * <p>The scheduler inspects this hint instead of relying on {@link dev.jentic.core.BehaviorType}
 * alone, because composite types have heterogeneous execution semantics:
 * <ul>
 *   <li>Workflow composites ({@code SEQUENTIAL}, {@code PARALLEL}) are autonomous —
 *       they have a clear start/finish and must be driven by the scheduler.</li>
 *   <li>Control-flow composites ({@code RETRY}, {@code CIRCUIT_BREAKER}, {@code FSM},
 *       {@code PIPELINE}) are wrappers triggered by external events and must remain
 *       on-demand.</li>
 * </ul>
 *
 * @since 0.14.0
 */
public enum SchedulingHint {

    /**
     * Execute {@code execute()} once immediately after registration, then become inactive.
     * Equivalent to {@link dev.jentic.core.BehaviorType#ONE_SHOT} scheduling semantics.
     * Default for non-repeating {@code SequentialBehavior} and {@code ParallelBehavior}.
     */
    ONCE,

    /**
     * Execute {@code execute()} repeatedly at the interval returned by {@link CompositeBehavior#getInterval()}.
     * Equivalent to {@link dev.jentic.core.BehaviorType#CYCLIC} scheduling semantics.
     * Requires a non-null interval; the scheduler will throw {@link IllegalStateException}
     * if {@code getInterval()} returns {@code null}.
     */
    CYCLIC,

    /**
     * Register the behavior (so the framework injects the agent reference) but never
     * call {@code execute()} automatically. The caller is responsible for triggering
     * execution explicitly.
     * Default for {@code FSM}, {@code RETRY}, {@code CIRCUIT_BREAKER}, {@code PIPELINE}.
     */
    ON_DEMAND
}