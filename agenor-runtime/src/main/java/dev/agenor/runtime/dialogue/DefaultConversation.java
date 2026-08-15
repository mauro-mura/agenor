package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.Conversation;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link Conversation}.
 *
 * @since 0.5.0
 */
public class DefaultConversation implements Conversation {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversation.class);

    private final String id;
    private final Protocol protocol;
    private final String initiatorId;
    private final String participantId;
    private final boolean isInitiator;
    private final Instant startedAt;
    private final List<DialogueMessage> history;

    private volatile ProtocolState state;
    private volatile Instant lastActivity;

    public DefaultConversation(
            String id,
            Protocol protocol,
            String initiatorId,
            String participantId,
            boolean isInitiator) {
        this.id = id;
        this.protocol = protocol;
        this.initiatorId = initiatorId;
        this.participantId = participantId;
        this.isInitiator = isInitiator;
        this.startedAt = Instant.now();
        this.lastActivity = this.startedAt;
        this.history = Collections.synchronizedList(new ArrayList<>());
        this.state = protocol != null ? protocol.getInitialState() : ProtocolState.INITIATED;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Optional<Protocol> getProtocol() {
        return Optional.ofNullable(protocol);
    }

    @Override
    public ProtocolState getState() {
        return state;
    }

    @Override
    public String getInitiatorId() {
        return initiatorId;
    }

    @Override
    public String getParticipantId() {
        return participantId;
    }

    @Override
    public boolean isInitiator() {
        return isInitiator;
    }

    @Override
    public Instant getLastActivity() {
        return lastActivity;
    }

    @Override
    public Instant getStartedAt() {
        return startedAt;
    }

    @Override
    public List<DialogueMessage> getHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * Adds a message to the conversation and updates state.
     *
     * <p>When the conversation has a protocol, the message is checked against it first. A
     * message the protocol does not allow in the current state is reported at WARN and then
     * processed exactly as before — it stays in the history and the transition still runs,
     * which for the built-in protocols leaves the state unchanged. Reporting rather than
     * rejecting is deliberate: see ADR-029.
     *
     * @param message the message to add
     */
    public void addMessage(DialogueMessage message) {
        history.add(message);
        lastActivity = Instant.now();

        if (protocol != null) {
            reportIfInvalid(message);
            state = protocol.nextState(state, message.performative(), isInitiator);
        }
    }

    /**
     * Checks a message against the protocol from the <em>sender's</em> point of view.
     *
     * <p>{@code allowedPerformatives(state, isInitiator)} answers "what may this party send
     * now?", so validating an inbound message with this conversation's own {@code isInitiator}
     * would flag every legitimate reply — an incoming {@code AGREE} while awaiting a response
     * would be checked against what the <em>initiator</em> may send, which is only
     * {@code CANCEL}. The sender's role is derivable without knowing the local agent id:
     * {@code initiatorId} is the initiator on both sides of the exchange.
     */
    private void reportIfInvalid(DialogueMessage message) {
        boolean senderIsInitiator = message.senderId().equals(initiatorId);
        if (protocol.isValid(state, message.performative(), senderIsInitiator)) {
            return;
        }
        log.warn("Protocol violation in conversation {} ({}): {} sent {} in state {}, "
                + "which allows {}",
            id, protocol.getId(), message.senderId(), message.performative(), state,
            protocol.allowedPerformatives(state, senderIsInitiator));
    }

    /**
     * Manually sets the state (for timeout/cancel scenarios).
     *
     * @param newState the new state
     */
    public void setState(ProtocolState newState) {
        this.state = newState;
        this.lastActivity = Instant.now();
    }
}
