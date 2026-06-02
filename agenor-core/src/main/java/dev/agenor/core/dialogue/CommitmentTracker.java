package dev.agenor.core.dialogue;

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
}
