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
     * Whether this performative is one that, by its meaning, creates a commitment.
     *
     * <p><strong>The framework does not act on all four.</strong> Only {@code REQUEST}
     * and {@code AGREE} reach {@link CommitmentTracker#createFromMessage}: those are the
     * two guards in the runtime's conversation manager. A {@code CFP} or a {@code PROPOSE}
     * returns {@code true} here and still records nothing, so a bidder's offer to perform
     * leaves no commitment behind. That gap is a deliberate deferral, named as a known
     * limitation in ADR-009's 2026-09-01 amendment, and this method reports the intended
     * taxonomy rather than what is wired.
     *
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
