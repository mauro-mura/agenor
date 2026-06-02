package dev.agenor.core.dialogue.protocol;

import dev.agenor.core.dialogue.Performative;

import java.util.Set;

/**
 * Defines an interaction protocol as a finite state machine.
 *
 * <p>Protocols define the valid sequences of performatives that can
 * occur in a conversation between agents. Each protocol has states
 * and transitions triggered by performatives.
 *
 * @since 0.5.0
 */
public interface Protocol {

    /**
     * @return unique identifier for this protocol
     */
    String getId();

    /**
     * @return human-readable name for display
     */
    String getDisplayName();

    /**
     * @return the initial state when a conversation starts
     */
    ProtocolState getInitialState();

    /**
     * Computes the next state given a performative.
     *
     * @param current the current state
     * @param received the received performative
     * @param isInitiator true if this agent initiated the protocol
     * @return the next state
     */
    ProtocolState nextState(ProtocolState current, Performative received, boolean isInitiator);

    /**
     * Gets the set of allowed performatives in a given state.
     *
     * @param state the current state
     * @param isInitiator true if this agent initiated the protocol
     * @return set of valid performatives
     */
    Set<Performative> allowedPerformatives(ProtocolState state, boolean isInitiator);

    /**
     * Validates whether a performative is allowed in a given state.
     *
     * @param state the current state
     * @param performative the performative to validate
     * @param isInitiator true if this agent initiated the protocol
     * @return true if the performative is valid
     */
    default boolean isValid(ProtocolState state, Performative performative, boolean isInitiator) {
        return allowedPerformatives(state, isInitiator).contains(performative);
    }
}
