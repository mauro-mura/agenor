package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.Agent;
import dev.agenor.core.BehaviorType;
import dev.agenor.core.condition.Condition;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.condition.ConditionEvaluator;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Behavior that executes only when a condition is satisfied.
 * Can wrap any action and evaluate condition before execution.
 */
public abstract class ConditionalBehavior extends BaseBehavior {

    private final Condition condition;
    private final ConditionEvaluator evaluator;
    private long skippedExecutions = 0;
    private long successfulExecutions = 0;

    protected ConditionalBehavior(Condition condition) {
        this(null, condition, null);
    }

    protected ConditionalBehavior(Condition condition, Duration interval) {
        this(null, condition, interval);
    }

    protected ConditionalBehavior(String behaviorId, Condition condition, Duration interval) {
        super(behaviorId != null ? behaviorId : "conditional-behavior",
              BehaviorType.CUSTOM, interval);
        this.condition = condition;
        this.evaluator = new ConditionEvaluator();
    }

    @Override
    public CompletableFuture<Void> execute() {
        if (!isActive()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            Agent agent = getAgent();

            // Evaluate condition
            if (evaluator.evaluate(condition, agent)) {
                log.trace("Condition satisfied for behavior: {}, executing action", getBehaviorId());

                try {
                    conditionalAction();
                    successfulExecutions++;
                } catch (Exception e) {
                    log.error("Error executing conditional action: {}", getBehaviorId(), e);
                    onError(e);
                }
            } else {
                log.trace("Condition not satisfied for behavior: {}, skipping execution",
                         getBehaviorId());
                skippedExecutions++;
                onConditionNotMet();
            }
        });
    }

    /**
     * The action to execute when condition is satisfied.
     * Must be implemented by subclasses.
     */
    protected abstract void conditionalAction();

    /**
     * Called when condition is not met and execution is skipped.
     * Override for custom handling.
     */
    protected void onConditionNotMet() {
        // Default: do nothing
    }

    /**
     * Get number of skipped executions due to condition not met
     */
    public long getSkippedExecutions() {
        return skippedExecutions;
    }

    /**
     * Get number of successful executions (condition met)
     */
    public long getSuccessfulExecutions() {
        return successfulExecutions;
    }

    /**
     * Get condition satisfaction rate (0-1)
     */
    public double getSatisfactionRate() {
        long total = skippedExecutions + successfulExecutions;
        return total > 0 ? (double) successfulExecutions / total : 0.0;
    }

    /**
     * Factory method: Create conditional behavior from Runnable
     */
    public static ConditionalBehavior from(Condition condition, Runnable action) {
        return new ConditionalBehavior(condition) {
            @Override
            protected void conditionalAction() {
                action.run();
            }
        };
    }

    /**
     * Factory method: Create conditional cyclic behavior
     */
    public static ConditionalBehavior cyclic(Condition condition, Duration interval, Runnable action) {
        return new ConditionalBehavior(condition, interval) {
            @Override
            protected void conditionalAction() {
                action.run();
            }
        };
    }

    @Override
    protected void action() {
        // Not used in conditional behavior - execute() is overridden
    }
}
