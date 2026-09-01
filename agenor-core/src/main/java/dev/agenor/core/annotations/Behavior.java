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
