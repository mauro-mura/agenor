package dev.agenor.core.mailbox;

/**
 * What a mailbox does with a message it has no room for.
 *
 * <p>Blocking the producer is deliberately not offered: the producer may be a transport
 * consumer loop serving many agents, and stalling it would turn one slow agent into a
 * node-wide outage.
 *
 * @since 0.27.0
 */
public enum OverflowPolicy {

    /**
     * Discard the oldest queued message to make room for the arriving one, and log a
     * warning. Keeps the agent responsive to current traffic at the cost of history.
     */
    DROP_OLDEST,

    /**
     * Discard the arriving message and log a warning, leaving the queue untouched.
     */
    DROP_NEWEST,

    /**
     * Refuse the arriving message and signal the failure to the sender rather than
     * dropping it silently.
     */
    REJECT
}
