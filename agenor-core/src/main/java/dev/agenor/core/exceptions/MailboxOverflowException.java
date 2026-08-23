package dev.agenor.core.exceptions;

/**
 * Thrown when a message arrives at a full mailbox configured to reject rather than drop.
 *
 * <p>Signals backpressure: the recipient is not keeping up. The message was not enqueued
 * and no handler will see it.
 *
 * @since 0.27.0
 */
public class MailboxOverflowException extends AgenorException {

    /** Identifier of the agent whose mailbox refused the message. */
    private final String agentId;

    /**
     * Creates an overflow exception for the given agent.
     *
     * @param agentId  identifier of the agent whose mailbox is full
     * @param capacity the capacity that was reached
     */
    public MailboxOverflowException(String agentId, int capacity) {
        super("Mailbox for agent '" + agentId + "' is full (capacity " + capacity + ")");
        this.agentId = agentId;
    }

    /**
     * Returns the agent whose mailbox refused the message.
     *
     * @return the agent identifier, never {@code null}
     */
    public String getAgentId() {
        return agentId;
    }
}
