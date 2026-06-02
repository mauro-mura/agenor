package dev.agenor.core.dialogue;

import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Represents an ongoing dialogue conversation between agents.
 *
 * <p>A conversation tracks the sequence of messages exchanged,
 * the protocol being followed, and the current state.
 *
 * @since 0.5.0
 */
public interface Conversation {

    /**
     * @return unique conversation identifier
     */
    String getId();

    /**
     * @return the protocol governing this conversation, if any
     */
    Optional<Protocol> getProtocol();

    /**
     * @return the current protocol state
     */
    ProtocolState getState();

    /**
     * @return the agent that initiated this conversation
     */
    String getInitiatorId();

    /**
     * @return the other participant in the conversation
     */
    String getParticipantId();

    /**
     * @return true if the local agent is the initiator
     */
    boolean isInitiator();

    /**
     * @return timestamp of the last activity
     */
    Instant getLastActivity();

    /**
     * @return when the conversation started
     */
    Instant getStartedAt();

    /**
     * @return ordered list of messages in this conversation
     */
    List<DialogueMessage> getHistory();

    /**
     * @return the most recent message, if any
     */
    default Optional<DialogueMessage> getLastMessage() {
        var history = getHistory();
        return history.isEmpty()
            ? Optional.empty()
            : Optional.of(history.get(history.size() - 1));
    }

    /**
     * @return true if the conversation has reached a terminal state
     */
    default boolean isComplete() {
        return getState().isTerminal();
    }

    /**
     * @return number of messages exchanged
     */
    default int getMessageCount() {
        return getHistory().size();
    }
}
