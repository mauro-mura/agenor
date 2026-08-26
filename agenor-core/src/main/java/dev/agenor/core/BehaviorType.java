package dev.agenor.core;

/**
 * How the scheduler should drive a behavior.
 *
 * <p>This enum answers one question — <em>when does this work run</em> — for the three shapes
 * that answer differs for: once, repeatedly, or as a state machine that decides for itself.
 *
 * <p>It deliberately does <strong>not</strong> describe how work is wrapped. Retrying, rate
 * limiting, batching, gating on a condition and breaking a circuit are decorators around a call:
 * they are triggered by whatever calls them rather than by a scheduler, which is why
 * {@link dev.agenor.core.composite.SchedulingHint} had to exist before them. Each constant that
 * described a wrapper is deprecated, and its Javadoc names where that concern belongs instead.
 *
 * <p>Composition is a third thing again, and it lives in
 * {@link dev.agenor.core.composite.CompositeBehavior}: build the composite, and let its
 * {@code SchedulingHint} tell the scheduler how to drive it.
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
     * Wake up at specific times or conditions.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Use {@code ONE_SHOT} with
     *             {@code @Behavior(initialDelay = "...")}, which now honours the delay.
     *             A waker is a polling behavior, and the scheduler drove it exactly once at
     *             registration — evaluating a wake condition that was, by construction, still
     *             false — so through the annotation it never fired at all.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    WAKER,

    /**
     * Event-driven behavior (responds to messages/events).
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Use {@code @AgenorMessageHandler}, which
     *             is the route that works. This one never did: the processor subscribed the
     *             behavior to a topic derived from the method name and to nothing else, and the
     *             scheduler skips event-driven behaviors on purpose, so nothing ever called it.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    EVENT_DRIVEN,

    /**
     * Custom behavior type.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Named by nothing and documented nowhere.
     *             Its scheduling path also parks a platform thread per behavior in the common
     *             pool and cannot be cancelled; a behavior that needs its own timing should
     *             implement {@link Behavior} and be driven by its owner.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    CUSTOM,

    /**
     * Execute only when condition is satisfied.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Gating is a property of a behavior, not
     *             a kind of behavior — check the condition at the top of the method, or hold it
     *             in the behavior you schedule.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    CONDITIONAL,

    /**
     * Execute with rate limiting.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Rate limiting is a wrapper, not a
     *             schedule. For outbound work use a resilience library around the call; for
     *             inbound pressure the mailbox drain is where receive-side policy belongs
     *             (ADR-032, ADR-033).
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    THROTTLED,

    /**
     * Batch processing.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Buffering inbound messages is the
     *             mailbox's concern since ADR-032, not a behavior type's; collect in your own
     *             handler if the batching is about your data rather than about delivery.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    BATCH,

    /**
     * Retry with backoff on failure.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Retrying is a wrapper around a call.
     *             Resilience4j and Failsafe do it better than a scheduler can, and they compose
     *             with the timeouts and bulkheads that come with the same problem.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    RETRY,

    /**
     * Circuit breaker pattern.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, together with
     *             {@code CircuitBreakerBehavior}, deprecated in 0.27.0. Circuit breaking is a
     *             resilience pattern rather than an agent concept, and dedicated libraries do
     *             it better.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    CIRCUIT_BREAKER,

    /**
     * Cron-like scheduled execution.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0. Cron is a scheduling concern: drive the
     *             agent from whatever already owns your schedule, or use {@code CYCLIC} with an
     *             interval when the cadence is fixed.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    SCHEDULED,

    /**
     * Multi-stage pipeline processing.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, together with {@code PipelineBehavior},
     *             deprecated in 0.27.0. {@code SequentialBehavior} covers ordered stages and
     *             has real callers.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    PIPELINE,

    /**
     * Execute behaviors sequentially one after another.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0 — the annotation route only. Build a
     *             {@code SequentialBehavior} and add it with {@code agent.addBehavior()}; its
     *             {@link dev.agenor.core.composite.SchedulingHint} already tells the scheduler
     *             how to drive it, which the annotation cannot express.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    SEQUENTIAL,

    /**
     * Execute multiple behaviors in parallel.
     *
     * @deprecated since 0.28.0, for removal in 0.30.0 — the annotation route only. Build a
     *             {@code ParallelBehavior} and add it with {@code agent.addBehavior()}, as with
     *             {@code SEQUENTIAL}.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    PARALLEL,

    /**
     * Finite State Machine behavior with state transitions
     */
    FSM
}
