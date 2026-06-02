package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;

import java.time.Duration;

/**
 * Behavior that executes repeatedly at fixed intervals.
 */
public abstract class CyclicBehavior extends BaseBehavior {

    protected CyclicBehavior(Duration interval) {
        super(BehaviorType.CYCLIC, interval);
    }

    protected CyclicBehavior(String behaviorId, Duration interval) {
        super(behaviorId, BehaviorType.CYCLIC, interval);
    }

    /**
     * Create a cyclic behavior from a Runnable
     */
    public static CyclicBehavior from(Duration interval, Runnable action) {
        return new CyclicBehavior(interval) {
            @Override
            protected void action() {
                action.run();
            }
        };
    }

    /**
     * Create a named cyclic behavior from a Runnable
     */
    public static CyclicBehavior from(String name, Duration interval, Runnable action) {
        return new CyclicBehavior(name, interval) {
            @Override
            protected void action() {
                action.run();
            }
        };
    }
}
