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
     * Whether the sender of a commitment-creating performative is the party that will
     * <em>perform</em> the obligation, rather than the one owed it.
     *
     * <p>The performative alone cannot answer this, which is why the question lives here.
     * {@code AGREE} binds its sender under a request protocol — A asks, B agrees, B performs —
     * and binds its <em>receiver</em> under Contract Net, where the initiator sends {@code AGREE}
     * to accept a proposal and the participant is the one taking on the work. This is the same
     * reason {@code allowedPerformatives} takes a perspective: direction is a property of the
     * protocol, and guessing it from the message is guessing.
     *
     * <p>The default is the request-shaped reading, which is correct for
     * {@code RequestProtocol} and {@code QueryProtocol}. Override it when your protocol
     * reverses the direction of any committing performative.
     *
     * @param performative the performative that creates the commitment
     * @return {@code true} when the sender performs, {@code false} when the receiver does
     * @since 0.31.0
     */
    default boolean senderPerforms(Performative performative) {
        return senderPerformsByDefault(performative);
    }

    /**
     * The request-shaped reading of {@link #senderPerforms}, available to implementations that
     * override the method for some performatives and want the default for the rest.
     *
     * @param performative the performative that creates the commitment
     * @return {@code true} when the sender performs under a request-shaped protocol
     * @since 0.31.0
     */
    static boolean senderPerformsByDefault(Performative performative) {
        return switch (performative) {
            case AGREE, PROPOSE -> true;
            default -> false;
        };
    }

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
