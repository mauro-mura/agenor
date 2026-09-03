package dev.agenor.runtime.deadletter;

import dev.agenor.core.Message;
import dev.agenor.core.deadletter.DeadLetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryDeadLetterQueue")
class InMemoryDeadLetterQueueTest {

    private static DeadLetter entry(String id) {
        var msg = Message.builder().topic("t").content(id).build();
        return DeadLetter.of(msg, "agent-1", new IllegalStateException(id));
    }

    @Test
    @DisplayName("recent() returns newest first")
    void recentIsNewestFirst() {
        var q = new InMemoryDeadLetterQueue(10);
        q.record(entry("one"));
        q.record(entry("two"));
        q.record(entry("three"));

        assertThat(q.recent(10))
                .extracting(dl -> dl.message().content())
                .containsExactly("three", "two", "one");
    }

    @Test
    @DisplayName("recent() honours its limit")
    void recentHonoursLimit() {
        var q = new InMemoryDeadLetterQueue(10);
        for (int i = 0; i < 5; i++) q.record(entry("m" + i));

        assertThat(q.recent(2))
                .extracting(dl -> dl.message().content())
                .containsExactly("m4", "m3");
    }

    @Test
    @DisplayName("past capacity the oldest are evicted, and totalRecorded says it happened")
    void evictsOldestPastCapacity() {
        var q = new InMemoryDeadLetterQueue(3);
        for (int i = 0; i < 6; i++) q.record(entry("m" + i));

        assertThat(q.recent(100))
                .extracting(dl -> dl.message().content())
                .containsExactly("m5", "m4", "m3");
        assertThat(q.totalRecorded()).isEqualTo(6);
        assertThat(q.capacity()).isEqualTo(3);
    }

    @Test
    @DisplayName("the bound holds under concurrent writers")
    void boundHoldsUnderConcurrency() throws Exception {
        var q = new InMemoryDeadLetterQueue(50);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(8);

        for (int t = 0; t < 8; t++) {
            final int id = t;
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) q.record(entry("t" + id + "-" + i));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(q.totalRecorded()).isEqualTo(800);
        assertThat(q.recent(1000)).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("the returned list is not writable by the caller")
    void recentIsUnmodifiable() {
        var q = new InMemoryDeadLetterQueue(4);
        q.record(entry("one"));

        assertThatThrownBy(() -> q.recent(4).add(entry("two")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an empty queue reads back empty rather than failing")
    void emptyReadsBackEmpty() {
        assertThat(new InMemoryDeadLetterQueue().recent(10)).isEmpty();
    }

    @Test
    @DisplayName("capacity and limit must be positive")
    void rejectsNonPositiveBounds() {
        assertThatThrownBy(() -> new InMemoryDeadLetterQueue(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryDeadLetterQueue(4).recent(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
