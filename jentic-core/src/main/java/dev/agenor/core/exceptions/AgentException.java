package dev.agenor.core.exceptions;

/**
 * Exception thrown during agent operations
 */
public class AgentException extends AgenorException {

    private final String agentId;

    public AgentException(String agentId, String message) {
        super(message);
        this.agentId = agentId;
    }

    public AgentException(String agentId, String message, Throwable cause) {
        super(message, cause);
        this.agentId = agentId;
    }

    public String getAgentId() {
        return agentId;
    }
}
