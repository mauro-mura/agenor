package dev.agenor.core.spi;

import java.lang.reflect.Method;

import dev.agenor.core.Agent;
import dev.agenor.core.BehaviorType;
import dev.agenor.core.annotations.Behavior;

/**
 * Optional {@code @Behavior} extension covering behavior types whose implementation
 * classes live outside {@code agenor-runtime} (CONDITIONAL, THROTTLED, BATCH, RETRY,
 * SEQUENTIAL, PARALLEL, FSM — all in {@code agenor-runtime-ext}), discovered via
 * {@link java.util.ServiceLoader}.
 *
 * <p>{@code agenor-runtime}'s core annotation processor handles ONE_SHOT, CYCLIC,
 * WAKER, EVENT_DRIVEN, and CUSTOM directly, with no optional-module dependency, and
 * delegates every other {@link BehaviorType} to this extension when present. Unlike
 * other ADR-027 SPI seams, absence of this extension is not a silent no-op: a
 * {@code @Behavior} annotation names its required type explicitly and is always known
 * at registration time, so using an ext-only type without {@code agenor-runtime-ext}
 * on the classpath fails loudly with an {@link IllegalStateException} instead.
 *
 * @since 0.25.0
 */
public interface BehaviorAnnotationExtension {

    /**
     * Reports whether this extension can build a behavior for {@code type}.
     *
     * @param type the behavior type requested by a {@code @Behavior} annotation
     * @return {@code true} if this extension supports {@code type}
     */
    boolean supports(BehaviorType type);

    /**
     * Builds the behavior for a {@code @Behavior}-annotated method whose type this
     * extension {@link #supports(BehaviorType)}.
     *
     * @param agent      the agent instance being processed; never {@code null}
     * @param method     the annotated method; never {@code null}
     * @param annotation the annotation itself; never {@code null}
     * @return the constructed behavior, or {@code null} if construction was skipped
     *         (e.g. a required annotation attribute was left empty)
     */
    dev.agenor.core.Behavior createBehavior(Agent agent, Method method, Behavior annotation);
}
