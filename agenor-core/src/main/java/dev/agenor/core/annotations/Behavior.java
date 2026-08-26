package dev.agenor.core.annotations;

import dev.agenor.core.BehaviorType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Agenor behavior that the runtime will schedule and execute
 * automatically according to the specified {@link BehaviorType}.
 *
 * <p>The annotated method must be {@code public} and, unless the behavior receives
 * messages, take no parameters. The runtime wraps the method in the appropriate
 * {@link dev.agenor.core.Behavior} implementation at startup.
 *
 * <p>Common examples:
 * <pre>{@code
 * // Cyclic behavior — executes every 30 seconds
 * @Behavior(type = CYCLIC, interval = "30s")
 * public void pollExternalService() { ... }
 *
 * // Cyclic behavior that waits before its first tick
 * @Behavior(type = CYCLIC, interval = "30s", initialDelay = "5s")
 * public void pollAfterWarmup() { ... }
 *
 * // One-shot behavior — runs once, ten seconds after the agent starts
 * @Behavior(type = ONE_SHOT, initialDelay = "10s")
 * public void announceReady() { ... }
 *
 * // FSM behavior — a state machine that decides its own transitions
 * @Behavior(type = FSM, fsmInitialState = "START", stateTimeout = "30s")
 * public void advanceOrder() { ... }
 * }</pre>
 *
 * <p>The parameters below that carry a deprecation belong to behavior types deprecated in
 * 0.28.0. They keep working until 0.30.0, and each names what to use instead.
 *
 * @since 0.1.0
 * @see BehaviorType
 * @see dev.agenor.core.Behavior
 * @see Agent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Behavior {

    // -------------------------------------------------------------------------
    // Core parameters
    // -------------------------------------------------------------------------

    /**
     * The execution pattern for this behavior.
     *
     * <p>Defaults to {@link BehaviorType#ONE_SHOT}, which executes the method once
     * when the agent starts. For repetitive work, use {@link BehaviorType#CYCLIC}.
     *
     * @return the behavior type
     */
    BehaviorType type() default BehaviorType.ONE_SHOT;

    /**
     * Interval between executions for {@link BehaviorType#CYCLIC} behaviors.
     *
     * <p>Accepted formats: {@code "500ms"}, {@code "30s"}, {@code "2m"}, {@code "1h"}.
     * Ignored for non-CYCLIC types.
     *
     * @return the interval string, or empty string if not applicable
     */
    String interval() default "";

    /**
     * Delay before the first execution.
     *
     * <p>Accepted formats: {@code "5s"}, {@code "1m"}. When empty, the behavior starts
     * immediately. Honoured by {@link BehaviorType#ONE_SHOT} — which fires once when the
     * delay elapses — and by {@link BehaviorType#CYCLIC}, which waits before its first tick
     * and then keeps its {@link #interval()}.
     *
     * @return the initial delay string, or empty string for immediate start
     */
    String initialDelay() default "";

    /**
     * Whether this behavior should be started automatically when the agent starts.
     *
     * <p>Set to {@code false} to create the behavior in a paused state;
     * it can then be started programmatically via {@link dev.agenor.core.Agent#addBehavior}.
     *
     * @return {@code true} to start automatically (default), {@code false} for manual start
     */
    boolean autoStart() default true;

    // -------------------------------------------------------------------------
    // CONDITIONAL behavior parameters
    // -------------------------------------------------------------------------

    /**
     * Condition expression for {@link BehaviorType#CONDITIONAL} behaviors.
     *
     * <p>The behavior executes only when this expression evaluates to {@code true}.
     * Built-in predicates: {@code "system.cpu < 50"}, {@code "time.businessHours"},
     * {@code "agent.running"}. Custom predicates can be registered via
     * {@link dev.agenor.core.condition.ConditionContext}.
     *
     * @return condition expression, or empty string if not applicable
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Gating is a property of a behavior, not a kind of one: test the
     *              condition where the work happens.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String condition() default "";

    // -------------------------------------------------------------------------
    // THROTTLED behavior parameters
    // -------------------------------------------------------------------------

    /**
     * Rate limit specification for {@link BehaviorType#THROTTLED} behaviors.
     *
     * <p>Format: {@code "<count>/<unit>"} where unit is {@code s} (seconds),
     * {@code m} (minutes), or {@code h} (hours). Examples: {@code "10/s"},
     * {@code "100/m"}, {@code "1000/h"}.
     *
     * @return rate limit specification, or empty string if not applicable
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Rate limiting wraps a call rather than scheduling one — a
     *              resilience library outbound, the mailbox drain inbound.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String rateLimit() default "";

    // -------------------------------------------------------------------------
    // BATCH behavior parameters
    // -------------------------------------------------------------------------

    /**
     * Maximum number of items collected before flushing, for {@link BehaviorType#BATCH}.
     *
     * @return maximum batch size (default 10)
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Buffering inbound messages is the mailbox's concern since
     *              ADR-032; batching your own data belongs in your handler.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    int batchSize() default 10;

    /**
     * Maximum time to wait before flushing an incomplete batch, for {@link BehaviorType#BATCH}.
     *
     * <p>Accepted formats: {@code "1s"}, {@code "5s"}, {@code "1m"}.
     *
     * @return max wait time string (default {@code "5s"})
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     *             parameterises. See {@link #batchSize()}.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String maxWaitTime() default "5s";

    // -------------------------------------------------------------------------
    // RETRY behavior parameters
    // -------------------------------------------------------------------------

    /**
     * Maximum number of retry attempts for {@link BehaviorType#RETRY} behaviors.
     *
     * <p>After all attempts are exhausted, the behavior reports failure.
     *
     * @return maximum retry attempts (default 3)
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Retrying wraps a call: Resilience4j and Failsafe do it better and
     *              compose with the timeouts that arrive with the same problem.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    int maxRetries() default 3;

    /**
     * Backoff strategy used between retry attempts.
     *
     * <p>Accepted values: {@code "fixed"} (constant delay), {@code "linear"}
     * (linearly increasing delay), {@code "exponential"} (exponentially increasing delay).
     *
     * @return backoff strategy name (default {@code "exponential"})
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     *             parameterises. See {@link #maxRetries()}.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String backoff() default "exponential";

    // -------------------------------------------------------------------------
    // SCHEDULED behavior parameters
    // -------------------------------------------------------------------------

    /**
     * Cron expression for {@link BehaviorType#SCHEDULED} behaviors.
     *
     * <p>Uses standard 6-field cron format: {@code "second minute hour day month weekday"}.
     * Example: {@code "0 0 * * * *"} (top of every hour).
     *
     * @return cron expression, or empty string if not applicable
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Cron is a scheduling concern: drive the agent from whatever
     *              already owns your schedule, or use {@code CYCLIC} for a fixed cadence.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String cron() default "";

    // -------------------------------------------------------------------------
    // SEQUENTIAL composite parameters
    // -------------------------------------------------------------------------

    /**
     * Per-step timeout for {@link BehaviorType#SEQUENTIAL} behaviors.
     *
     * <p>Accepted formats: {@code "30s"}, {@code "5m"}. Empty string means no timeout.
     *
     * <p>For repeating (round-robin) sequential behaviors, use {@link #interval()} to set
     * the tick rate — the presence of a non-empty {@code interval} is what determines
     * repeating vs one-shot mode.
     *
     * @return per-step timeout string, or empty string for no timeout (default)
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Build a {@code SequentialBehavior} and add it with {@code
     *              agent.addBehavior()}; its {@code SchedulingHint} tells the scheduler what the
     *              annotation cannot.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String stepTimeout() default "";

    // -------------------------------------------------------------------------
    // PARALLEL composite parameters
    // -------------------------------------------------------------------------

    /**
     * Completion strategy for {@link BehaviorType#PARALLEL} behaviors.
     *
     * <p>Accepted values:
     * <ul>
     *   <li>{@code "ALL"} — wait for all child behaviors (default)</li>
     *   <li>{@code "ANY"} — complete when any child finishes</li>
     *   <li>{@code "FIRST"} — complete on the first successful child</li>
     *   <li>{@code "N_OF_M"} — complete when {@link #requiredCompletions()} children finish</li>
     * </ul>
     *
     * @return completion strategy name (default {@code "ALL"})
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     * parameterises. Build a {@code ParallelBehavior} and add it with {@code
     *              agent.addBehavior()}.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String parallelStrategy() default "ALL";

    /**
     * Number of child completions required when using the {@code N_OF_M}
     * {@link #parallelStrategy()}.
     *
     * @return required completions count (default 0, meaning use ALL strategy)
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     *             parameterises. See {@link #parallelStrategy()}.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    int requiredCompletions() default 0;

    /**
     * Per-child timeout for {@link BehaviorType#PARALLEL} behaviors.
     *
     * <p>Accepted formats: {@code "10s"}, {@code "1m"}. Empty string means no timeout.
     *
     * @return per-child timeout string, or empty string for no timeout (default)
     * @since 0.2.0
     *
     * @deprecated since 0.28.0, for removal in 0.30.0, with the behavior type it
     *             parameterises. See {@link #parallelStrategy()}.
     */
    @Deprecated(since = "0.28.0", forRemoval = true)
    String childTimeout() default "";

    // -------------------------------------------------------------------------
    // FSM composite parameters
    // -------------------------------------------------------------------------

    /**
     * Name of the initial FSM state for {@link BehaviorType#FSM} behaviors.
     *
     * @return initial state name (default {@code "START"})
     * @since 0.2.0
     */
    String fsmInitialState() default "START";

    /**
     * Per-state execution timeout for {@link BehaviorType#FSM} behaviors.
     *
     * <p>Accepted formats: {@code "30s"}, {@code "2m"}. Empty string means no timeout.
     *
     * @return state timeout string, or empty string for no timeout (default)
     * @since 0.2.0
     */
    String stateTimeout() default "";
}
