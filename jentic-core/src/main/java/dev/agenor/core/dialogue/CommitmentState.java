package dev.agenor.core.dialogue;

/**
 * States of a social commitment between agents.
 *
 * @since 0.5.0
 */
public enum CommitmentState {

    /** Commitment created but not yet active */
    PENDING,

    /** Commitment is active and must be fulfilled */
    ACTIVE,

    /** Commitment has been successfully fulfilled */
    FULFILLED,

    /** Commitment was not fulfilled within constraints */
    VIOLATED,

    /** Commitment was cancelled by debtor */
    CANCELLED,

    /** Commitment was released by creditor */
    RELEASED;

    /**
     * @return true if this state is terminal (no further transitions possible)
     */
    public boolean isTerminal() {
        return this != PENDING && this != ACTIVE;
    }

    /**
     * @return true if this state represents a successful outcome
     */
    public boolean isSuccessful() {
        return this == FULFILLED || this == RELEASED;
    }
}
