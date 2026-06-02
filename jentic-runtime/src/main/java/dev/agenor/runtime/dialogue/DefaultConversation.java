package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.Conversation;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;

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
     * @param message the message to add
     */
    public void addMessage(DialogueMessage message) {
        history.add(message);
        lastActivity = Instant.now();

        if (protocol != null) {
            state = protocol.nextState(state, message.performative(), isInitiator);
        }
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
