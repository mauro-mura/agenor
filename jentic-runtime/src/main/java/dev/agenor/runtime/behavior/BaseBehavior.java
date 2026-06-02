package dev.agenor.runtime.behavior;

import dev.agenor.core.Agent;
import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base implementation of Behavior interface.
 * Provides common functionality for all behaviors.
 */
public abstract class BaseBehavior implements Behavior {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String behaviorId;
    private final BehaviorType type;
    private final Duration interval;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private Agent agent;

    protected BaseBehavior(BehaviorType type) {
        this(UUID.randomUUID().toString(), type, null);
    }

    protected BaseBehavior(BehaviorType type, Duration interval) {
        this(UUID.randomUUID().toString(), type, interval);
    }

    protected BaseBehavior(String behaviorId, BehaviorType type, Duration interval) {
        this.behaviorId = behaviorId;
        this.type = type;
        this.interval = interval;
    }

    @Override
    public String getBehaviorId() {
        return behaviorId;
    }

    @Override
    public Agent getAgent() {
        return agent;
    }

    @Override
    public BehaviorType getType() {
        return type;
    }

    @Override
    public Duration getInterval() {
        return interval;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void stop() {
        if (active.compareAndSet(true, false)) {
            log.debug("Behavior stopped: {}", behaviorId);
            onStop();
        }
    }

    /**
     * Reactivates a stopped behavior, allowing it to be scheduled again.
     *
     * <p>This method is called by the agent when restarting after a stop.
     * It resets the active flag to true, enabling the behavior to execute.
     *
     * @return true if the behavior was reactivated, false if already active
     * @since 0.4.0
     */
    public boolean activate() {
        if (active.compareAndSet(false, true)) {
            log.debug("Behavior activated: {}", behaviorId);
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<Void> execute() {
        if (!active.get()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                log.trace("Executing behavior: {}", behaviorId);
                action();
                if (type == BehaviorType.ONE_SHOT) {
                    stop();
                }
            } catch (Throwable t) { // catch everything
                log.error("Error in behavior execution: {}", behaviorId, t);
                try {
                    onError(t instanceof Exception e ? e : new Exception(t));
                } catch (Throwable suppressed) {
                    // Ensure even handler failures don’t propagate
                    log.error("Error handling behavior error: {}", behaviorId, suppressed);
                }
            }
        })
        .exceptionally(ex -> {
            // Last line of defense: should not normally happen because we catch in the lambda
            log.error("Behavior future failed: {}", behaviorId, ex);
            return null;
        });
    }

    /**
     * Set the owning agent for this behavior
     */
    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    /**
     * The main action to be performed by this behavior.
     * Must be implemented by subclasses.
     */
    protected abstract void action();

    /**
     * Called when the behavior is stopped.
     * Override for cleanup logic.
     */
    protected void onStop() {
        // Override in subclasses if needed
    }

    /**
     * Called when an error occurs during execution.
     * Override for custom error handling.
     */
    protected void onError(Exception error) {
        // Default: just log the error
        log.error("Behavior error: {}", behaviorId, error);
    }
}
