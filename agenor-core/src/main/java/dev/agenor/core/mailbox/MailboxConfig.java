package dev.agenor.core.mailbox;

import java.util.Objects;

/**
 * Bounds and overflow behaviour for one agent's mailbox.
 *
 * @param capacity       maximum number of messages held before {@code overflowPolicy}
 *                       applies; must be positive
 * @param overflowPolicy what to do with a message arriving at a full mailbox; must not be
 *                       {@code null}
 * @since 0.27.0
 */
public record MailboxConfig(int capacity, OverflowPolicy overflowPolicy) {

    /** Capacity used by {@link #defaults()}. */
    public static final int DEFAULT_CAPACITY = 1024;

    /**
     * Validates the configuration.
     *
     * @throws IllegalArgumentException if {@code capacity} is not positive
     * @throws NullPointerException     if {@code overflowPolicy} is {@code null}
     */
    public MailboxConfig {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        Objects.requireNonNull(overflowPolicy, "overflowPolicy");
    }

    /**
     * Returns the default configuration: {@value #DEFAULT_CAPACITY} messages, dropping the
     * oldest on overflow.
     *
     * @return a non-null default configuration
     */
    public static MailboxConfig defaults() {
        return new MailboxConfig(DEFAULT_CAPACITY, OverflowPolicy.DROP_OLDEST);
    }
}
