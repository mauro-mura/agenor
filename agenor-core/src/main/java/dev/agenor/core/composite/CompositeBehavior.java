package dev.agenor.core.composite;

import dev.agenor.core.Agent;
import dev.agenor.core.Behavior;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for behaviors that coordinate other behaviors.
 *
 * <h2>Scheduling</h2>
 * <p>Subclasses declare how the scheduler should drive them by overriding
 * {@link #getSchedulingHint()}. The default is {@link SchedulingHint#ON_DEMAND}:
 * the scheduler injects the agent reference but never calls {@code execute()}
 * automatically.
 *
 * <p>Workflow composites ({@code SequentialBehavior}, {@code ParallelBehavior}) override
 * this to return {@link SchedulingHint#ONCE} or {@link SchedulingHint#CYCLIC}, so that
 * {@code agent.addBehavior()} is sufficient — no manual {@code execute()} call required.
 *
 * <p>Control-flow composites (FSM, Retry, CircuitBreaker, Pipeline) keep the default
 * {@link SchedulingHint#ON_DEMAND} because they are wrappers triggered by external events.
 *
 * <h2>Child management</h2>
 * <p>Children are added via {@link #addChildBehavior(Behavior)}. When {@link #setAgent(Agent)}
 * is called, the agent reference is propagated recursively to all child composites.
 * {@link #stop()} propagates to all children as well.
 *
 * <h2>Thread safety</h2>
 * <p>The {@code active} flag is {@code volatile}. The {@code childBehaviors} list is not
 * synchronized — children should be added before the behavior is scheduled.
 *
 * <h2>Implementing a new composite</h2>
 * <pre>{@code
 * public class MyComposite extends CompositeBehavior {
 *
 *     public MyComposite(String id) { super(id); }
 *
 *     @Override public BehaviorType getType() { return BehaviorType.CUSTOM; }
 *
 *     // Override to control auto-scheduling; default is ON_DEMAND.
 *     @Override public SchedulingHint getSchedulingHint() { return SchedulingHint.ONCE; }
 *
 *     @Override
 *     public CompletableFuture<Void> execute() {
 *         // coordinate childBehaviors here
 *     }
 * }
 * }</pre>
 *
 * @see SchedulingHint
 */
public abstract class CompositeBehavior implements Behavior {

    /** Unique identifier for this composite behavior. */
    protected final String behaviorId;

    /** Ordered list of child behaviors managed by this composite. */
    protected final List<Behavior> childBehaviors;

    /** The agent this behavior belongs to; injected by the framework via {@link #setAgent(Agent)}. */
    protected Agent agent;

    /**
     * Whether this behavior is active. Set to {@code false} by {@link #stop()} or when a
     * one-shot composite has finished all its steps. Declared {@code volatile} for visibility
     * across threads.
     */
    protected volatile boolean active;

    /**
     * Scheduling interval used when {@link #getSchedulingHint()} returns
     * {@link SchedulingHint#CYCLIC}. Must be non-null in that case; ignored otherwise.
     */
    protected Duration interval;

    /**
     * Creates a new composite behavior with the given identifier.
     * The behavior starts in the active state with an empty child list.
     *
     * @param behaviorId unique identifier for this behavior, must not be null
     */
    protected CompositeBehavior(String behaviorId) {
        this.behaviorId = behaviorId;
        this.childBehaviors = new ArrayList<>();
        this.active = true;
    }

    // -------------------------------------------------------------------------
    // Scheduling contract
    // -------------------------------------------------------------------------

    /**
     * Returns the scheduling hint that tells the scheduler how to drive this composite.
     *
     * <p>Subclasses should override this to return the appropriate hint:
     * <ul>
     *   <li>{@link SchedulingHint#ONCE} — execute all steps once, then become inactive.</li>
     *   <li>{@link SchedulingHint#CYCLIC} — repeat at the interval from {@link #getInterval()}.</li>
     *   <li>{@link SchedulingHint#ON_DEMAND} — register only; caller triggers {@code execute()}.</li>
     * </ul>
     *
     * @return the scheduling hint, never null
     */
    public SchedulingHint getSchedulingHint() {
        return SchedulingHint.ON_DEMAND;
    }

    @Override
    public Duration getInterval() {
        return interval;
    }

    // -------------------------------------------------------------------------
    // Behavior identity and lifecycle
    // -------------------------------------------------------------------------

    @Override
    public String getBehaviorId() {
        return behaviorId;
    }

    @Override
    public Agent getAgent() {
        return agent;
    }

    /**
     * Injects the owning agent into this composite and propagates it recursively
     * to all child composites already registered. Called by the framework during
     * {@code agent.addBehavior()}.
     *
     * @param agent the owning agent, may be null if detaching
     */
    public void setAgent(Agent agent) {
        this.agent = agent;
        for (Behavior child : childBehaviors) {
            if (child instanceof CompositeBehavior composite) {
                composite.setAgent(agent);
            }
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /**
     * Marks this composite and all its children as inactive.
     * Once stopped, a composite will not execute further steps.
     */
    @Override
    public void stop() {
        active = false;
        for (Behavior child : childBehaviors) {
            child.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Child management
    // -------------------------------------------------------------------------

    /**
     * Adds a child behavior to this composite.
     *
     * <p>If the child is itself a {@code CompositeBehavior} and the agent has already
     * been set on this instance, the agent reference is propagated immediately.
     * Children should normally be added before the behavior is scheduled.
     *
     * @param behavior the child behavior to add, must not be null
     */
    public void addChildBehavior(Behavior behavior) {
        childBehaviors.add(behavior);
        if (behavior instanceof CompositeBehavior composite) {
            composite.setAgent(agent);
        }
    }

    /**
     * Returns an immutable view of the registered child behaviors in insertion order.
     *
     * @return unmodifiable list of children, never null
     */
    public List<Behavior> getChildBehaviors() {
        return Collections.unmodifiableList(childBehaviors);
    }
}
