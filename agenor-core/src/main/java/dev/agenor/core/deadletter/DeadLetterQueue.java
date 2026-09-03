package dev.agenor.core.deadletter;

import java.util.List;

/**
 * Where a message goes when the framework stops trying to deliver it.
 *
 * <p>This is the downstream half of the delivery chain ADR-033 designed. That chain was
 * already complete up to the mailbox: {@code BaseAgent} rethrows an annotated handler's
 * failure, {@code DefaultAgentMailbox} fails the message's outcome future, and
 * {@link dev.agenor.core.mailbox.AgentMailbox#offer} promises exactly that. What was missing
 * was a consumer of that future outside the Redis adapter — the in-memory dispatcher answered
 * a failed outcome with a log line, so identical agent code lost a message on one transport
 * and dead-lettered it on the other.
 *
 * <p><strong>This port records; it does not retry.</strong> Whether a message is redelivered
 * before it lands here is the transport's business and is unchanged by this interface: Redis
 * retries {@code maxDeliveryAttempts} times, the in-memory transport does not retry at all.
 * What is now the same on both is that the message is <em>recoverable</em> rather than gone.
 *
 * <p>Reading is half the point. A queue that could only be written to would be a logger with
 * more steps — it is {@link #recent(int)} that lets a console show what failed, a test assert
 * it, and an operator find the payload that has to be re-sent by hand.
 *
 * <p>Implementations must be safe for concurrent use: messages fail on many virtual threads
 * at once, and reading happens while writing continues.
 *
 * @since 0.32.0
 */
public interface DeadLetterQueue {

    /**
     * Records a message the framework has given up on.
     *
     * <p>Called from a delivery path that has already failed, so it must not throw: an
     * implementation that cannot store the entry logs and returns rather than turning a lost
     * message into a second failure on top of it.
     *
     * @param deadLetter what was given up on and why; must not be {@code null}
     */
    void record(DeadLetter deadLetter);

    /**
     * Returns the most recently dead-lettered messages, newest first.
     *
     * <p>What "recent" reaches depends on the implementation and is a property worth knowing
     * before relying on it: an in-memory queue holds a bounded window and forgets on restart,
     * while one backed by a durable stream reaches as far back as that stream is retained.
     *
     * @param limit the maximum number of entries to return; must be positive
     * @return the entries, newest first, at most {@code limit} of them; never {@code null},
     *         possibly empty
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    List<DeadLetter> recent(int limit);

    /**
     * Returns the queue that discards everything and reads back empty.
     *
     * <p>This is the default for a dispatcher assembled without one, so that recording a dead
     * letter is never a null check at the call site. It preserves the behaviour that existed
     * before this interface: the message is gone and only the log says so.
     *
     * @return the shared noop instance; never {@code null}
     */
    static DeadLetterQueue noop() {
        return NoopDeadLetterQueue.INSTANCE;
    }
}
