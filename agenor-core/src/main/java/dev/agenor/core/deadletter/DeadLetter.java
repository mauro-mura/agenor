package dev.agenor.core.deadletter;

import dev.agenor.core.Message;

import java.time.Instant;
import java.util.Objects;

/**
 * A message the framework has stopped trying to deliver, and why.
 *
 * <p>A dead letter is a record of a decision already taken: the message is not queued for
 * another attempt, and recording it changes nothing about its fate. It exists so that the
 * fate is <em>legible</em> — before this type, a message lost on the in-memory transport left
 * one log line, and one dead-lettered on Redis left an entry in a stream no API could read.
 *
 * <p>{@code attempts} counts how many times a handler was actually invoked for this message.
 * On a transport with redelivery it is the exhausted attempt limit; on one without, it is
 * {@code 1}. That is a fact about the transport, not a placeholder — a dead letter with one
 * attempt says the message was never retried because retrying it was never possible.
 *
 * @param message        the message that was given up on; never {@code null}
 * @param reason         why it was given up on, in a form a human reads; never {@code null}
 * @param recipientId    the agent the message was addressed to, or {@code null} for a topic
 *                       delivery, where no single agent was the intended recipient
 * @param attempts       how many times a handler was invoked for this message; at least 1
 * @param deadLetteredAt when the framework gave up
 * @since 0.32.0
 */
public record DeadLetter(
        Message message,
        String reason,
        String recipientId,
        int attempts,
        Instant deadLetteredAt
) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if {@code message}, {@code reason} or
     *                                  {@code deadLetteredAt} is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    public DeadLetter {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(deadLetteredAt, "deadLetteredAt");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
    }

    /**
     * Records a message given up on after a single attempt, which is the only outcome a
     * transport without redelivery can produce.
     *
     * <p>The reason is taken from the failure's own message where it has one, and from its
     * type where it does not — an exception with a null message is common enough that
     * {@code "null"} would otherwise be the most frequent reason in the queue.
     *
     * @param message     the message that failed; must not be {@code null}
     * @param recipientId the agent it was addressed to, or {@code null} for a topic delivery
     * @param cause       the failure; must not be {@code null}
     * @return the dead letter, timestamped now; never {@code null}
     */
    public static DeadLetter of(Message message, String recipientId, Throwable cause) {
        return of(message, recipientId, cause, 1);
    }

    /**
     * Records a message given up on after {@code attempts} handler invocations.
     *
     * @param message     the message that failed; must not be {@code null}
     * @param recipientId the agent it was addressed to, or {@code null} for a topic delivery
     * @param cause       the failure; must not be {@code null}
     * @param attempts    how many times a handler was invoked; at least 1
     * @return the dead letter, timestamped now; never {@code null}
     */
    public static DeadLetter of(Message message, String recipientId, Throwable cause, int attempts) {
        Objects.requireNonNull(cause, "cause");
        var detail = cause.getMessage();
        var reason = cause.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
        return new DeadLetter(message, reason, recipientId, attempts, Instant.now());
    }
}
