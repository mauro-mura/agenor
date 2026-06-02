package dev.agenor.core.dialogue.protocol;

/**
 * States for interaction protocol state machines.
 *
 * @since 0.5.0
 */
public enum ProtocolState {

    /** Protocol has been initiated */
    INITIATED,

    /** Waiting for a response */
    AWAITING_RESPONSE,

    /** Parties have agreed */
    AGREED,

    /** Protocol completed successfully */
    COMPLETED,

    /** Request was refused */
    REFUSED,

    /** Action failed */
    FAILED,

    /** Protocol was cancelled */
    CANCELLED,

    /** Protocol timed out */
    TIMEOUT;

    /**
     * @return true if this is a terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == REFUSED ||
               this == FAILED || this == CANCELLED || this == TIMEOUT;
    }

    /**
     * @return true if this state represents success
     */
    public boolean isSuccess() {
        return this == COMPLETED || this == AGREED;
    }
}
