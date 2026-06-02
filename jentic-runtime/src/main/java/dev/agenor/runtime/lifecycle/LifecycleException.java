package dev.agenor.runtime.lifecycle;

/**
 * Exception thrown during lifecycle operations
 */
public class LifecycleException extends RuntimeException {

    private final String agentId;

    public LifecycleException(String agentId, String message) {
        super(message);
        this.agentId = agentId;
    }

    public LifecycleException(String agentId, String message, Throwable cause) {
        super(message, cause);
        this.agentId = agentId;
    }

    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getMessage() {
        return String.format("Agent '%s': %s", agentId, super.getMessage());
    }
}
