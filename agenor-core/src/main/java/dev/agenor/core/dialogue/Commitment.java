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
 * <p><strong>Lifetime — a commitment does not outlive the process that tracks it.</strong> It
 * lives in the tracking agent's memory, so a restart loses it and nothing will move it to
 * {@link CommitmentState#VIOLATED} afterwards, however overdue it becomes. A commitment is an
 * observable promise within a running dialogue, <em>not</em> an audit record: anything that must
 * be auditable belongs in the agent's own persistent state. See ADR-031.
 *
 * <p>Commitments in a terminal state are swept after a retention window (default 5 minutes),
 * and deadline violations are detected by the same sweep — so a violated commitment stays
 * readable for at least one interval after it is marked.
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
