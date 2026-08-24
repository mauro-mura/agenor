package dev.agenor.core.mailbox;

import java.util.Objects;

/**
 * Bounds and overflow behaviour for one agent's mailbox.
 *
 * @param capacity              maximum number of messages held before {@code overflowPolicy}
 *                              applies; must be positive
 * @param overflowPolicy        what to do with a message arriving at a full mailbox; must not
 *                              be {@code null}
 * @param maxConcurrentHandlers how many of this agent's handlers may run at once; must be
 *                              positive. This is the bound that matters: the queue only fills
 *                              once handlers are saturated, so it is the constraint the
 *                              capacity sits behind (ADR-033 D-2)
 * @since 0.27.0
 */
public record MailboxConfig(int capacity, OverflowPolicy overflowPolicy, int maxConcurrentHandlers) {

    /** Capacity used by {@link #defaults()}. */
    public static final int DEFAULT_CAPACITY = 1024;

    /**
     * Concurrent handler limit used by {@link #defaults()}. High enough that an ordinary agent
     * never meets it, low enough that one slow handler cannot spawn threads without limit.
     */
    public static final int DEFAULT_MAX_CONCURRENT_HANDLERS = 64;

    /**
     * Validates the configuration.
     *
     * @throws IllegalArgumentException if {@code capacity} or {@code maxConcurrentHandlers}
     *                                  is not positive
     * @throws NullPointerException     if {@code overflowPolicy} is {@code null}
     */
    public MailboxConfig {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        if (maxConcurrentHandlers <= 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentHandlers must be positive, was " + maxConcurrentHandlers);
        }
        Objects.requireNonNull(overflowPolicy, "overflowPolicy");
    }

    /**
     * Creates a configuration with the default concurrent-handler limit.
     *
     * @param capacity       maximum number of messages held before {@code overflowPolicy} applies
     * @param overflowPolicy what to do with a message arriving at a full mailbox
     */
    public MailboxConfig(int capacity, OverflowPolicy overflowPolicy) {
        this(capacity, overflowPolicy, DEFAULT_MAX_CONCURRENT_HANDLERS);
    }

    /**
     * Returns the default configuration: {@value #DEFAULT_CAPACITY} messages, dropping the
     * oldest on overflow.
     *
     * @return a non-null default configuration
     */
    public static MailboxConfig defaults() {
        return new MailboxConfig(DEFAULT_CAPACITY, OverflowPolicy.DROP_OLDEST,
                DEFAULT_MAX_CONCURRENT_HANDLERS);
    }
}
