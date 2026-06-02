package dev.agenor.core.dialogue;

/**
 * Communicative act types for agent dialogue.
 * Reduced to 10 pragmatic primitives from FIPA's 22+.
 *
 * @since 0.5.0
 */
public enum Performative {

    /** Request action execution */
    REQUEST,

    /** Ask for information */
    QUERY,

    /** Provide information */
    INFORM,

    /** Accept to perform action */
    AGREE,

    /** Decline request */
    REFUSE,

    /** Report action failure */
    FAILURE,

    /** Make a proposal */
    PROPOSE,

    /** Call for proposals */
    CFP,

    /** Cancel ongoing interaction */
    CANCEL,

    /** Notify of event */
    NOTIFY;

    /**
     * @return true if this performative creates a commitment
     */
    public boolean createsCommitment() {
        return this == REQUEST || this == PROPOSE || this == CFP || this == AGREE;
    }

    /**
     * @return true if this performative discharges a commitment
     */
    public boolean dischargesCommitment() {
        return this == INFORM || this == REFUSE || this == FAILURE || this == CANCEL;
    }

    /**
     * @return true if this performative expects a response
     */
    public boolean expectsResponse() {
        return this == REQUEST || this == QUERY || this == CFP || this == PROPOSE;
    }
}
