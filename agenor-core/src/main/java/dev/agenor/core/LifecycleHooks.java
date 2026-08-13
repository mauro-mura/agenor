package dev.agenor.core;

/**
 * Opt-in extension point for components that need to run code when an agent starts or stops,
 * without the agent having to override {@code start()}/{@code stop()} for them.
 *
 * <p>This is deliberately <strong>not</strong> part of {@link Agent}: an agent implementation
 * that has no use for lifecycle hooks should not be forced to provide the machinery. Framework
 * capabilities composed into an agent (dialogue, persistence, ...) register themselves through
 * this interface when the agent supports it, and fall back to requiring manual wiring when it
 * does not.
 *
 * <p>{@code BaseAgent} implements this interface, so agents extending it get automatic wiring
 * for free:
 *
 * <pre>{@code
 * public class MyAgent extends BaseAgent {
 *     // registers itself via onStartHook/onStopHook - no onStart() override needed
 *     private final DialogueCapability dialogue = new DialogueCapability(this);
 * }
 * }</pre>
 *
 * <p>An agent implementing {@link Agent} directly can opt in by also implementing this
 * interface and invoking the registered hooks from its own {@code start()}/{@code stop()}.
 *
 * <p><strong>Ordering</strong> (as implemented by {@code BaseAgent}): start hooks run in
 * registration order <em>after</em> the agent's {@code onStart()}, stop hooks run <em>before</em>
 * its {@code onStop()}. A hook that throws is logged and does not abort the lifecycle transition,
 * so a hook must not be relied upon for anything the agent cannot run without.
 *
 * @since 0.26.0
 */
public interface LifecycleHooks {

    /**
     * Registers a hook to be executed when the agent starts.
     *
     * @param hook the action to run on start; a {@code null} hook is ignored
     */
    void onStartHook(Runnable hook);

    /**
     * Registers a hook to be executed when the agent stops.
     *
     * @param hook the action to run on stop; a {@code null} hook is ignored
     */
    void onStopHook(Runnable hook);
}
