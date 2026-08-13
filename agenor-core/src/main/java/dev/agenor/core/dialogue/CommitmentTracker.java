package dev.agenor.core.dialogue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Tracks and manages commitments across conversations.
 *
 * @since 0.5.0
 */
public interface CommitmentTracker {

    /**
     * Creates a new commitment from a dialogue message.
     *
     * @param message the message that creates the commitment (e.g., REQUEST, AGREE)
     * @return the created commitment
     */
    Commitment createFromMessage(DialogueMessage message);

    /**
     * Updates a commitment based on a response message.
     *
     * @param commitmentId the commitment to update
     * @param response the response message (e.g., INFORM, FAILURE)
     */
    void updateFromResponse(String commitmentId, DialogueMessage response);

    /**
     * Retrieves a commitment by ID.
     *
     * @param commitmentId the commitment ID
     * @return the commitment if found
     */
    Optional<Commitment> get(String commitmentId);

    /**
     * Retrieves the ID of the commitment created by a given message.
     *
     * <p>Used to correlate an incoming reply with the commitment its original message
     * created, so that the commitment can be advanced by
     * {@link #updateFromResponse(String, DialogueMessage)}.
     *
     * @param messageId the ID of the message that created the commitment; may be null
     * @return the commitment ID if that message created one
     * @since 0.26.0
     */
    Optional<String> getByMessageId(String messageId);

    /**
     * Gets all active commitments where the agent is the performer.
     *
     * @param agentId the agent ID
     * @return list of active commitments to fulfill
     */
    List<Commitment> getActiveAsPerformer(String agentId);

    /**
     * Gets all active commitments where the agent is the requester.
     *
     * @param agentId the agent ID
     * @return list of active commitments awaiting fulfillment
     */
    List<Commitment> getActiveAsRequester(String agentId);

    /**
     * Checks for commitments that have exceeded their deadline.
     *
     * @return list of violated commitments
     */
    List<Commitment> checkViolations();

    /**
     * Cancels a commitment (by debtor).
     *
     * @param commitmentId the commitment to cancel
     * @param reason optional reason for cancellation
     */
    void cancel(String commitmentId, String reason);

    /**
     * Releases a commitment (by creditor).
     *
     * @param commitmentId the commitment to release
     */
    void release(String commitmentId);

    /**
     * Removes commitments that have reached a terminal state and are older than the
     * given duration.
     *
     * <p>Commitments outlive the message exchange that created them so that they remain
     * observable after the fact; without a periodic sweep they accumulate for the whole
     * lifetime of the agent. Implementations backed by a store with its own expiry may
     * leave this as the default no-op.
     *
     * @param olderThan retention window measured from the commitment's creation time;
     *                  {@link Duration#ZERO} removes every terminated commitment
     * @return the number of commitments removed
     * @since 0.26.0
     */
    default int cleanup(Duration olderThan) {
        return 0;
    }
}
