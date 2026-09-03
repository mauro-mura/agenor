package dev.agenor.core.deadletter;

import java.util.List;
import java.util.Objects;

/**
 * The {@link DeadLetterQueue} that keeps nothing.
 *
 * <p>Reached through {@link DeadLetterQueue#noop()}. It still validates its arguments, so a
 * caller that passes {@code null} finds out under the default configuration rather than only
 * once a real queue is wired in.
 *
 * @since 0.32.0
 */
final class NoopDeadLetterQueue implements DeadLetterQueue {

    static final NoopDeadLetterQueue INSTANCE = new NoopDeadLetterQueue();

    private NoopDeadLetterQueue() {
    }

    @Override
    public void record(DeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter");
    }

    @Override
    public List<DeadLetter> recent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive, was " + limit);
        return List.of();
    }
}
