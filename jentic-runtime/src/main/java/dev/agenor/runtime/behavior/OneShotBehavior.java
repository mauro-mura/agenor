package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;

/**
 * Behavior that executes once and then stops.
 */
public abstract class OneShotBehavior extends BaseBehavior {

    protected OneShotBehavior() {
        super(BehaviorType.ONE_SHOT);
    }

    protected OneShotBehavior(String behaviorId) {
        super(behaviorId, BehaviorType.ONE_SHOT, null);
    }

    /**
     * Create a one-shot behavior from a Runnable
     */
    public static OneShotBehavior from(Runnable action) {
        return new OneShotBehavior() {
            @Override
            protected void action() {
                action.run();
            }
        };
    }

    /**
     * Create a named one-shot behavior from a Runnable
     */
    public static OneShotBehavior from(String name, Runnable action) {
        return new OneShotBehavior(name) {
            @Override
            protected void action() {
                action.run();
            }
        };
    }
}
