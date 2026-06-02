package dev.agenor.core.memory;

/**
 * Defines the scope and lifecycle of memory entries.
 *
 * <p>Memory can be either short-term (volatile, cleared on restart) or
 * long-term (persistent, survives restarts).
 *
 * @since 0.6.0
 */
public enum MemoryScope {

    /**
     * Volatile memory that is cleared on agent restart.
     *
     * <p>Suitable for:
     * <ul>
     *   <li>Conversation context and history</li>
     *   <li>Temporary state during task execution</li>
     *   <li>Session-specific information</li>
     *   <li>Cached computations</li>
     * </ul>
     *
     * <p>Short-term memory is typically stored in-memory for fast access
     * and is not persisted to disk.
     */
    SHORT_TERM,

    /**
     * Persistent memory that survives agent restarts.
     *
     * <p>Suitable for:
     * <ul>
     *   <li>Learned facts and knowledge</li>
     *   <li>User preferences and profiles</li>
     *   <li>Historical patterns and insights</li>
     *   <li>Domain knowledge base</li>
     * </ul>
     *
     * <p>Long-term memory is persisted to durable storage (database, file system)
     * and can be retrieved across sessions.
     */
    LONG_TERM;

    /**
     * Checks if this is short-term memory.
     *
     * @return true if SHORT_TERM
     */
    public boolean isShortTerm() {
        return this == SHORT_TERM;
    }

    /**
     * Checks if this is long-term memory.
     *
     * @return true if LONG_TERM
     */
    public boolean isLongTerm() {
        return this == LONG_TERM;
    }
}
