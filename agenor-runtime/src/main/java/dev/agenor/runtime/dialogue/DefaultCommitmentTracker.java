package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.Commitment;
import dev.agenor.core.dialogue.CommitmentTracker;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link CommitmentTracker}.
 *
 * @since 0.5.0
 */
public class DefaultCommitmentTracker implements CommitmentTracker {

    private final Map<String, DefaultCommitment> commitments = new ConcurrentHashMap<>();
    private final Map<String, String> messageToCommitment = new ConcurrentHashMap<>();

    private final Duration defaultDeadline;

    public DefaultCommitmentTracker() {
        this(Duration.ofMinutes(5));
    }

    public DefaultCommitmentTracker(Duration defaultDeadline) {
        this.defaultDeadline = defaultDeadline;
    }

    @Override
    public Commitment createFromMessage(DialogueMessage message) {
        if (!message.performative().createsCommitment()) {
            return null;
        }

        String performer;
        String requester;

        // Determine performer/requester based on performative
        switch (message.performative()) {
            case REQUEST, QUERY, CFP -> {
                // Sender is requesting, receiver will be performer
                requester = message.senderId();
                performer = message.receiverId();
            }
            case AGREE, PROPOSE -> {
                // Sender agrees to perform
                performer = message.senderId();
                requester = message.receiverId();
            }
            default -> {
                return null;
            }
        }

        var id = UUID.randomUUID().toString();
        var deadline = Instant.now().plus(defaultDeadline);

        var commitment = new DefaultCommitment(
            id,
            performer,
            requester,
            message.content(),
            message.conversationId(),
            deadline
        );

        // AGREE activates immediately
        if (message.performative() == Performative.AGREE) {
            commitment.activate(message.senderId());
        }

        commitments.put(id, commitment);
        messageToCommitment.put(message.id(), id);

        return commitment;
    }

    @Override
    public void updateFromResponse(String commitmentId, DialogueMessage response) {
        var commitment = commitments.get(commitmentId);
        if (commitment == null) {
            return;
        }

        switch (response.performative()) {
            case AGREE -> commitment.activate(response.senderId());
            case INFORM -> commitment.fulfill(response.senderId());
            case FAILURE -> commitment.violate("Action failed: " + response.content());
            case REFUSE -> commitment.cancel(response.senderId(), "Request refused");
            case CANCEL -> commitment.cancel(response.senderId(), "Cancelled by sender");
            default -> { /* no state change */ }
        }
    }

    @Override
    public Optional<Commitment> get(String commitmentId) {
        return Optional.ofNullable(commitments.get(commitmentId));
    }

    /**
     * Gets commitment ID by the message that created it.
     */
    public Optional<String> getByMessageId(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(messageToCommitment.get(messageId));
    }

    @Override
    public List<Commitment> getActiveAsPerformer(String agentId) {
        return commitments.values().stream()
            .filter(c -> c.getPerformer().equals(agentId))
            .filter(Commitment::isActive)
            .map(c -> (Commitment) c)
            .toList();
    }

    @Override
    public List<Commitment> getActiveAsRequester(String agentId) {
        return commitments.values().stream()
            .filter(c -> c.getRequester().equals(agentId))
            .filter(Commitment::isActive)
            .map(c -> (Commitment) c)
            .toList();
    }

    @Override
    public List<Commitment> checkViolations() {
        var now = Instant.now();
        return commitments.values().stream()
            .filter(Commitment::isActive)
            .filter(c -> c.getDeadline().map(d -> now.isAfter(d)).orElse(false))
            .peek(c -> c.violate("Deadline exceeded"))
            .map(c -> (Commitment) c)
            .toList();
    }

    @Override
    public void cancel(String commitmentId, String reason) {
        var commitment = commitments.get(commitmentId);
        if (commitment != null) {
            commitment.cancel(commitment.getPerformer(), reason);
        }
    }

    @Override
    public void release(String commitmentId) {
        var commitment = commitments.get(commitmentId);
        if (commitment != null) {
            commitment.release(commitment.getRequester());
        }
    }

    /**
     * Removes completed/terminal commitments older than specified duration.
     *
     * @param olderThan remove commitments older than this duration
     * @return number of commitments removed
     */
    public int cleanup(Duration olderThan) {
        var cutoff = Instant.now().minus(olderThan);
        var toRemove = commitments.entrySet().stream()
            .filter(e -> e.getValue().getState().isTerminal())
            .filter(e -> e.getValue().getCreatedAt().isBefore(cutoff))
            .map(Map.Entry::getKey)
            .toList();

        toRemove.forEach(commitments::remove);
        return toRemove.size();
    }
}
