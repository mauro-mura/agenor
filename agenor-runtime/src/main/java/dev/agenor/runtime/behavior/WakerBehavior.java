package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Behavior that wakes up based on specific conditions or time.
 *
 * @deprecated since 0.28.0, for removal in 0.30.0, together with
 *             {@link dev.agenor.core.BehaviorType#WAKER}. This is a polling behavior and the
 *             scheduler drove it exactly once, at registration, when its wake condition was by
 *             construction still false — so through the annotation it never woke. Use
 *             {@code ONE_SHOT} with {@code @Behavior(initialDelay = "...")}, where the delay is
 *             the scheduler's, which is where a delay belongs.
 */
@Deprecated(since = "0.28.0", forRemoval = true)
public abstract class WakerBehavior extends BaseBehavior {

    private final Supplier<Boolean> wakeCondition;
    private final Duration checkInterval;

    protected WakerBehavior(Supplier<Boolean> wakeCondition) {
        this(wakeCondition, Duration.ofSeconds(1));
    }

    protected WakerBehavior(Supplier<Boolean> wakeCondition, Duration checkInterval) {
        super(BehaviorType.WAKER, checkInterval);
        this.wakeCondition = wakeCondition;
        this.checkInterval = checkInterval;
    }

    protected WakerBehavior(String behaviorId, Supplier<Boolean> wakeCondition, Duration checkInterval) {
        super(behaviorId, BehaviorType.WAKER, checkInterval);
        this.wakeCondition = wakeCondition;
        this.checkInterval = checkInterval;
    }

    @Override
    protected void action() {
        if (wakeCondition.get()) {
            log.debug("Wake condition met for behavior: {}", getBehaviorId());
            onWake();
        }
    }

    /**
     * Called when the wake condition is met.
     * Must be implemented by subclasses.
     */
    protected abstract void onWake();

    /**
     * Create a waker behavior that wakes at a specific time
     */
    public static WakerBehavior wakeAt(Instant wakeTime, Runnable action) {
        return new WakerBehavior(() -> Instant.now().isAfter(wakeTime)) {
            @Override
            protected void onWake() {
                action.run();
                stop(); // One-time wake
            }
        };
    }

    /**
     * Create a waker behavior that wakes after a delay
     */
    public static WakerBehavior wakeAfter(Duration delay, Runnable action) {
        Instant wakeTime = Instant.now().plus(delay);
        return wakeAt(wakeTime, action);
    }

    /**
     * Create a waker behavior with custom condition
     */
    public static WakerBehavior wakeWhen(Supplier<Boolean> condition, Runnable action) {
        return new WakerBehavior(condition) {
            @Override
            protected void onWake() {
                action.run();
            }
        };
    }
}
