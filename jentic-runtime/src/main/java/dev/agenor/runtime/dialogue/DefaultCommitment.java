package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.Commitment;
import dev.agenor.core.dialogue.CommitmentEvent;
import dev.agenor.core.dialogue.CommitmentState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link Commitment}.
 *
 * @since 0.5.0
 */
public class DefaultCommitment implements Commitment {

    private final String id;
    private final String performer;
    private final String requester;
    private final Object content;
    private final String conversationId;
    private final Instant createdAt;
    private final Instant deadline;
    private final List<CommitmentEvent> history;

    private volatile CommitmentState state;

    public DefaultCommitment(
            String id,
            String performer,
            String requester,
            Object content,
            String conversationId,
            Instant deadline) {
        this.id = id;
        this.performer = performer;
        this.requester = requester;
        this.content = content;
        this.conversationId = conversationId;
        this.createdAt = Instant.now();
        this.deadline = deadline;
        this.history = Collections.synchronizedList(new ArrayList<>());
        this.state = CommitmentState.PENDING;

        history.add(CommitmentEvent.created(requester, "Commitment created"));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getPerformer() {
        return performer;
    }

    @Override
    public String getRequester() {
        return requester;
    }

    @Override
    public CommitmentState getState() {
        return state;
    }

    @Override
    public Object getContent() {
        return content;
    }

    @Override
    public String getConversationId() {
        return conversationId;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public Optional<Instant> getDeadline() {
        return Optional.ofNullable(deadline);
    }

    @Override
    public List<CommitmentEvent> getHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * Transitions to a new state.
     *
     * @param newState the target state
     * @param triggeredBy who triggered the transition
     * @param description description of the transition
     * @return true if transition was valid
     */
    public boolean transitionTo(CommitmentState newState, String triggeredBy, String description) {
        if (state.isTerminal()) {
            return false;
        }

        var event = new CommitmentEvent(Instant.now(), state, newState, triggeredBy, description);
        history.add(event);
        state = newState;
        return true;
    }

    /**
     * Activates the commitment.
     */
    public void activate(String triggeredBy) {
        if (state == CommitmentState.PENDING) {
            transitionTo(CommitmentState.ACTIVE, triggeredBy, "Commitment activated");
        }
    }

    /**
     * Marks the commitment as fulfilled.
     */
    public void fulfill(String triggeredBy) {
        transitionTo(CommitmentState.FULFILLED, triggeredBy, "Commitment fulfilled");
    }

    /**
     * Marks the commitment as violated.
     */
    public void violate(String reason) {
        transitionTo(CommitmentState.VIOLATED, "system", reason);
    }

    /**
     * Cancels the commitment.
     */
    public void cancel(String triggeredBy, String reason) {
        transitionTo(CommitmentState.CANCELLED, triggeredBy, reason);
    }

    /**
     * Releases the commitment.
     */
    public void release(String triggeredBy) {
        transitionTo(CommitmentState.RELEASED, triggeredBy, "Commitment released by requester");
    }
}
