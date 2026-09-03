package dev.agenor.runtime.deadletter;

import dev.agenor.core.deadletter.DeadLetter;
import dev.agenor.core.deadletter.DeadLetterQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link DeadLetterQueue} holding a bounded window of recent entries in memory.
 *
 * <p>The default for a runtime that has not been given a durable one. It is a window, not an
 * archive: past {@link #capacity()} entries the oldest are evicted, and nothing survives a
 * restart. {@link #totalRecorded()} is what tells you whether eviction has happened, so that a
 * full window is distinguishable from a quiet one.
 *
 * <p>Those limits are the honest shape for this transport. The in-memory dispatcher does not
 * redeliver, so a dead letter here is a message that is already lost; keeping it is what makes
 * the loss visible, and keeping it forever would only trade one leak for another.
 *
 * @since 0.32.0
 */
public final class InMemoryDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeadLetterQueue.class);

    /** Entries kept when no capacity is given. */
    public static final int DEFAULT_CAPACITY = 256;

    private final ConcurrentLinkedDeque<DeadLetter> entries = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong totalRecorded = new AtomicLong();
    private final int capacity;

    /** Creates a queue holding {@link #DEFAULT_CAPACITY} entries. */
    public InMemoryDeadLetterQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a queue holding at most {@code capacity} entries.
     *
     * @param capacity the number of entries to keep; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public InMemoryDeadLetterQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public void record(DeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter");
        entries.addLast(deadLetter);
        totalRecorded.incrementAndGet();
        // Trim in a loop rather than once: concurrent writers can push past the bound
        // together, and the last one out is the one that brings it back.
        var current = size.incrementAndGet();
        while (current > capacity) {
            if (entries.pollFirst() == null) break;
            current = size.decrementAndGet();
        }
        log.warn("Dead-lettered message {} for '{}' after {} attempt(s): {}",
                deadLetter.message().id(), deadLetter.recipientId(),
                deadLetter.attempts(), deadLetter.reason());
    }

    @Override
    public List<DeadLetter> recent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive, was " + limit);
        var out = new ArrayList<DeadLetter>(Math.min(limit, size.get() + 1));
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns how many entries this queue keeps before evicting the oldest.
     *
     * @return the capacity; always positive
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns how many entries have ever been recorded, including those since evicted.
     *
     * <p>Compare it with the size of {@link #recent(int)} to tell a window that has overflowed
     * from one that has not.
     *
     * @return the total ever recorded; never negative
     */
    public long totalRecorded() {
        return totalRecorded.get();
    }
}
