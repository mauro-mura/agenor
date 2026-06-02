package dev.agenor.core.dialogue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Represents a social commitment between two agents.
 *
 * <p>A commitment is created when an agent makes a promise or accepts
 * a request. The performer is obligated to fulfill the commitment to the
 * requester.
 *
 * @since 0.5.0
 */
public interface Commitment {

    /**
     * @return unique identifier for this commitment
     */
    String getId();

    /**
     * @return the agent who must fulfill the commitment
     */
    String getPerformer();

    /**
     * @return the agent who requested and awaits fulfillment
     */
    String getRequester();

    /**
     * @return the current state of this commitment
     */
    CommitmentState getState();

    /**
     * @return the content/terms of the commitment
     */
    Object getContent();

    /**
     * @return the conversation in which this commitment was created
     */
    String getConversationId();

    /**
     * @return when this commitment was created
     */
    Instant getCreatedAt();

    /**
     * @return optional deadline by which the commitment must be fulfilled
     */
    Optional<Instant> getDeadline();

    /**
     * @return history of state transitions
     */
    List<CommitmentEvent> getHistory();

    /**
     * @return true if this commitment is still active (not terminal)
     */
    default boolean isActive() {
        return !getState().isTerminal();
    }

    /**
     * @return true if the deadline has passed
     */
    default boolean isOverdue() {
        return getDeadline()
            .map(d -> Instant.now().isAfter(d))
            .orElse(false);
    }
}
