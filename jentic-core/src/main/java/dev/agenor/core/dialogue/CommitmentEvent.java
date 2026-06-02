package dev.agenor.core.dialogue;

import java.time.Instant;
import java.util.Objects;

/**
 * Records a state transition in a commitment's lifecycle.
 *
 * @param timestamp when the transition occurred
 * @param fromState the previous state
 * @param toState the new state
 * @param triggeredBy the agent or message that triggered the transition
 * @param description optional description of the transition
 * @since 0.5.0
 */
public record CommitmentEvent(
    Instant timestamp,
    CommitmentState fromState,
    CommitmentState toState,
    String triggeredBy,
    String description
) {

    public CommitmentEvent {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(fromState, "fromState cannot be null");
        Objects.requireNonNull(toState, "toState cannot be null");
    }

    /**
     * Creates an event for the initial commitment creation.
     */
    public static CommitmentEvent created(String triggeredBy, String description) {
        return new CommitmentEvent(
            Instant.now(),
            CommitmentState.PENDING,
            CommitmentState.PENDING,
            triggeredBy,
            description
        );
    }

    /**
     * Creates an activation event.
     */
    public static CommitmentEvent activated(String triggeredBy) {
        return new CommitmentEvent(
            Instant.now(),
            CommitmentState.PENDING,
            CommitmentState.ACTIVE,
            triggeredBy,
            "Commitment activated"
        );
    }
}
