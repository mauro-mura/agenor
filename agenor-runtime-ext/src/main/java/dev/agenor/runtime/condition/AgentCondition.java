package dev.agenor.runtime.condition;

import dev.agenor.core.Agent;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.condition.Condition;
import dev.agenor.runtime.agent.BaseAgent;

/**
 * Pre-built conditions for agent state checks
 */
public class AgentCondition {

    /**
     * Check if agent is running
     */
    public static Condition isRunning() {
        return Agent::isRunning;
    }

    /**
     * Check if agent has specific status
     */
    public static Condition hasStatus(AgentStatus status) {
        return agent -> {
            if (agent instanceof BaseAgent baseAgent) {
                return baseAgent.getStatus() == status;
            }
            return false;
        };
    }

    /**
     * Check if agent ID matches pattern
     */
    public static Condition idMatches(String pattern) {
        return agent -> agent.getAgentId().matches(pattern);
    }

    /**
     * Check if agent name contains string
     */
    public static Condition nameContains(String substring) {
        return agent -> agent.getAgentName().contains(substring);
    }
}
