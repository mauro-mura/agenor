package dev.agenor.runtime.condition;

import dev.agenor.core.Agent;
import dev.agenor.core.condition.Condition;
import dev.agenor.core.condition.ConditionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates conditions with context and error handling
 */
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    private final ConditionContext context;

    public ConditionEvaluator() {
        this.context = new ConditionContext();
    }

    public ConditionEvaluator(ConditionContext context) {
        this.context = context;
    }

    /**
     * Evaluate condition safely with error handling
     */
    public boolean evaluate(Condition condition, Agent agent) {
        if (condition == null) {
            return true; // No condition = always execute
        }

        try {
            boolean result = condition.evaluate(agent);
            log.trace("Condition evaluated to {} for agent {}", result, agent.getAgentId());
            return result;
        } catch (Exception e) {
            log.error("Error evaluating condition for agent {}", agent.getAgentId(), e);
            return false; // Fail-safe: don't execute on error
        }
    }

    /**
     * Get condition context
     */
    public ConditionContext getContext() {
        return context;
    }
}
