package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.Commitment;
import dev.agenor.core.dialogue.CommitmentTracker;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;

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

    /**
     * Resolves the protocol a message was sent under, so the roles of a commitment can be read
     * from it. May be {@code null}, in which case every protocol is treated as request-shaped —
     * which is what this class assumed unconditionally before 0.31.0.
     */
    private final ProtocolRegistry protocolRegistry;

    public DefaultCommitmentTracker() {
        this(Duration.ofMinutes(5), null);
    }

    public DefaultCommitmentTracker(Duration defaultDeadline) {
        this(defaultDeadline, null);
    }

    /**
     * @param protocolRegistry resolves the protocol that decides which party performs
     * @since 0.31.0
     */
    public DefaultCommitmentTracker(ProtocolRegistry protocolRegistry) {
        this(Duration.ofMinutes(5), protocolRegistry);
    }

    /**
     * @param defaultDeadline how long a commitment has before it is considered violated
     * @param protocolRegistry resolves {@link DialogueMessage#getProtocol()} to the {@link Protocol}
     *                         that decides which party performs; {@code null} falls back to the
     *                         request-shaped reading
     * @since 0.31.0
     */
    public DefaultCommitmentTracker(Duration defaultDeadline, ProtocolRegistry protocolRegistry) {
        this.defaultDeadline = defaultDeadline;
        this.protocolRegistry = protocolRegistry;
    }

    @Override
    public Commitment createFromMessage(DialogueMessage message) {
        if (!message.performative().createsCommitment()) {
            return null;
        }

        // Which party is bound depends on the protocol, not on the performative alone: AGREE
        // binds its sender under a request protocol and its receiver under Contract Net, where
        // the initiator sends it to accept a proposal. Asking the protocol is the same move
        // ADR-029 D5 made for allowedPerformatives.
        boolean senderPerforms = senderPerforms(message);

        String performer = senderPerforms ? message.senderId() : message.receiverId();
        String requester = senderPerforms ? message.receiverId() : message.senderId();

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

    /**
     * Asks the message's protocol which party a committing performative binds, falling back to
     * the request-shaped reading when the message names no protocol or none is registered.
     */
    private boolean senderPerforms(DialogueMessage message) {
        Performative performative = message.performative();
        if (protocolRegistry == null) {
            return Protocol.senderPerformsByDefault(performative);
        }
        return message.getProtocol()
                .flatMap(protocolRegistry::get)
                .map(protocol -> protocol.senderPerforms(performative))
                .orElseGet(() -> Protocol.senderPerformsByDefault(performative));
    }

    @Override
    public Optional<Commitment> get(String commitmentId) {
        return Optional.ofNullable(commitments.get(commitmentId));
    }

    /**
     * Gets commitment ID by the message that created it.
     */
    @Override
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
     * <p>The {@code messageId -> commitmentId} index is pruned along with them, otherwise
     * it would keep growing after the commitments it points at are gone.
     *
     * @param olderThan remove commitments older than this duration
     * @return number of commitments removed
     */
    @Override
    public int cleanup(Duration olderThan) {
        var cutoff = Instant.now().minus(olderThan);
        var toRemove = commitments.entrySet().stream()
            .filter(e -> e.getValue().getState().isTerminal())
            .filter(e -> !e.getValue().getCreatedAt().isAfter(cutoff))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());

        toRemove.forEach(commitments::remove);
        messageToCommitment.values().removeIf(toRemove::contains);
        return toRemove.size();
    }
}
