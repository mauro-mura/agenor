package dev.agenor.runtime.scheduler;

import dev.agenor.runtime.behavior.CyclicBehavior;
import dev.agenor.runtime.behavior.OneShotBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for SimpleBehaviorScheduler
 */
class SimpleBehaviorSchedulerTest {

    private SimpleBehaviorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SimpleBehaviorScheduler();
        scheduler.start().join();
    }

    @Test
    void shouldScheduleOneShotBehavior() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executeCount = new AtomicInteger(0);

        OneShotBehavior behavior = OneShotBehavior.from("test-oneshot", () -> {
            executeCount.incrementAndGet();
            latch.countDown();
        });

        // When
        scheduler.schedule(behavior).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executeCount.get()).isEqualTo(1);

        // Wait a bit more to ensure it doesn't execute again
        Thread.sleep(1000);
        assertThat(executeCount.get()).isEqualTo(1);
    }

    @Test
    void shouldScheduleCyclicBehavior() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(3); // Wait for 3 executions
        AtomicInteger executeCount = new AtomicInteger(0);

        CyclicBehavior behavior = CyclicBehavior.from("test-cyclic", Duration.ofMillis(200), () -> {
            executeCount.incrementAndGet();
            latch.countDown();
        });

        // When
        scheduler.schedule(behavior).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executeCount.get()).isGreaterThanOrEqualTo(3);

        // Stop behavior and verify it stops executing
        behavior.stop();
        int countAfterStop = executeCount.get();
        Thread.sleep(500);
        assertThat(executeCount.get()).isLessThanOrEqualTo(countAfterStop + 1); // Allow for one more due to timing
    }

    @Test
    void shouldCancelBehavior() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executeCount = new AtomicInteger(0);

        CyclicBehavior behavior = CyclicBehavior.from("test-cancel", Duration.ofMillis(100), () -> {
            executeCount.incrementAndGet();
            latch.countDown();
        });

        // When
        scheduler.schedule(behavior).join();

        // Wait for first execution
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Cancel the behavior
        boolean cancelled = scheduler.cancel(behavior.getBehaviorId());

        // Then
        assertThat(cancelled).isTrue();

        int countAfterCancel = executeCount.get();
        Thread.sleep(500); // Wait to see if it continues executing

        // Should not execute more (allow for timing tolerance)
        assertThat(executeCount.get()).isLessThanOrEqualTo(countAfterCancel + 1);
    }

    @Test
    void shouldReturnFalseForNonExistentBehaviorCancel() {
        // When
        boolean cancelled = scheduler.cancel("non-existent-behavior");

        // Then
        assertThat(cancelled).isFalse();
    }

    @Test
    void shouldStartAndStopScheduler() {
        // Given
        SimpleBehaviorScheduler newScheduler = new SimpleBehaviorScheduler();

        // Initially not running
        assertThat(newScheduler.isRunning()).isFalse();

        // When
        newScheduler.start().join();

        // Then
        assertThat(newScheduler.isRunning()).isTrue();

        // When
        newScheduler.stop().join();

        // Then
        assertThat(newScheduler.isRunning()).isFalse();
    }

    @Test
    void shouldNotScheduleWhenNotRunning() throws InterruptedException {
        // Given
        SimpleBehaviorScheduler stoppedScheduler = new SimpleBehaviorScheduler();
        // Note: not started

        CountDownLatch latch = new CountDownLatch(1);
        OneShotBehavior behavior = OneShotBehavior.from(() -> latch.countDown());

        // When
        stoppedScheduler.schedule(behavior).join();

        // Then
        assertThat(latch.await(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void shouldHandleBehaviorExceptions() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(3); // Should execute twice despite exception
        AtomicInteger executeCount = new AtomicInteger(0);

        CyclicBehavior faultyBehavior = CyclicBehavior.from("faulty", Duration.ofMillis(150), () -> {
            executeCount.incrementAndGet();
            latch.countDown();
            throw new RuntimeException("Behavior error");
        });

        // When
        scheduler.schedule(faultyBehavior).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executeCount.get()).isGreaterThanOrEqualTo(3);

        // Stop the behavior to clean up
        faultyBehavior.stop();
    }
}
