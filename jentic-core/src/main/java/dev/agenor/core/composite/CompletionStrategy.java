package dev.agenor.core.composite;

/**
 * Strategy for determining when a parallel behavior completes
 */
public enum CompletionStrategy {
    /**
     * Wait for all child behaviors to complete
     */
    ALL,

    /**
     * Complete when any child behavior completes
     */
    ANY,

    /**
     * Complete when the first child behavior completes (race)
     */
    FIRST,

    /**
     * Complete when N child behaviors complete
     */
    N_OF_M
}
