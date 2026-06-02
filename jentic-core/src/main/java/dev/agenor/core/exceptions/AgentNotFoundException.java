package dev.agenor.core.exceptions;

/**
 * Thrown when a message cannot be delivered because the target agent is not registered
 * in the directory or has no resolvable endpoint.
 *
 * <p>This exception is thrown by {@code MessageDispatcher.sendTo(agentId, msg)} when
 * {@code AgentResolver.resolveEndpoint(agentId)} returns empty. It provides a clear,
 * actionable failure instead of silently dropping the message.
 *
 * @since 0.20.0
 */
public class AgentNotFoundException extends AgentException {

    /**
     * Constructs an exception for the given unknown agent ID.
     *
     * @param agentId the agent ID that could not be resolved
     */
    public AgentNotFoundException(String agentId) {
        super(agentId, "Agent not found in directory: " + agentId);
    }

    /**
     * Constructs an exception with a custom message.
     *
     * @param agentId the agent ID that could not be resolved
     * @param message additional context
     */
    public AgentNotFoundException(String agentId, String message) {
        super(agentId, message);
    }
}
