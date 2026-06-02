package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for CyclicBehavior.
 * Coverage: constructors, factory methods, cyclic execution, interval handling.
 */
class CyclicBehaviorTest {

    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
    }

    // =========================================================================
    // CONSTRUCTOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create cyclic behavior with interval")
    void testConstructorWithInterval() {
        // Given
        Duration interval = Duration.ofSeconds(5);

        // When
        CyclicBehavior behavior = new CyclicBehavior(interval) {
            @Override
            protected void action() {
                // empty
            }
        };

        // Then
        assertThat(behavior.getType()).isEqualTo(BehaviorType.CYCLIC);
        assertThat(behavior.getInterval()).isEqualTo(interval);
        assertThat(behavior.getBehaviorId()).isNotNull();
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should create cyclic behavior with custom ID and interval")
    void testConstructorWithIdAndInterval() {
        // Given
        String behaviorId = "custom-cyclic";
        Duration interval = Duration.ofMillis(100);

        // When
        CyclicBehavior behavior = new CyclicBehavior(behaviorId, interval) {
            @Override
            protected void action() {
                // empty
            }
        };

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo(behaviorId);
        assertThat(behavior.getInterval()).isEqualTo(interval);
        assertThat(behavior.getType()).isEqualTo(BehaviorType.CYCLIC);
    }

    // =========================================================================
    // FACTORY METHOD TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create from interval and Runnable")
    void testFromIntervalAndRunnable() {
        // Given
        Duration interval = Duration.ofMillis(100);
        AtomicInteger counter = new AtomicInteger(0);

        // When
        CyclicBehavior behavior = CyclicBehavior.from(interval, () -> counter.incrementAndGet());

        // Then
        assertThat(behavior).isNotNull();
        assertThat(behavior.getInterval()).isEqualTo(interval);
        assertThat(behavior.getType()).isEqualTo(BehaviorType.CYCLIC);
    }

    @Test
    @DisplayName("Should create from name, interval and Runnable")
    void testFromNameIntervalAndRunnable() {
        // Given
        String name = "test-cyclic";
        Duration interval = Duration.ofSeconds(1);
        AtomicInteger counter = new AtomicInteger(0);

        // When
        CyclicBehavior behavior = CyclicBehavior.from(name, interval, () -> counter.incrementAndGet());

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo(name);
        assertThat(behavior.getInterval()).isEqualTo(interval);
    }

    // =========================================================================
    // CYCLIC EXECUTION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should execute action multiple times")
    void testMultipleExecutions() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger counter = new AtomicInteger(0);

        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(50), () -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        behavior.setAgent(agent);

        // When - manually execute 3 times
        behavior.execute().join();
        behavior.execute().join();
        behavior.execute().join();

        // Then
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should remain active after executions")
    void testRemainsActive() {
        // Given
        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(100), () -> {});
        behavior.setAgent(agent);

        // When
        behavior.execute().join();
        behavior.execute().join();

        // Then - should still be active (unlike one-shot)
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should respect interval timing")
    void testIntervalTiming() {
        // Given
        Duration interval = Duration.ofMillis(200);
        CyclicBehavior behavior = CyclicBehavior.from(interval, () -> {});

        // Then
        assertThat(behavior.getInterval()).isEqualTo(interval);
        assertThat(behavior.getInterval().toMillis()).isEqualTo(200);
    }

    // =========================================================================
    // LIFECYCLE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should stop cyclic execution when stopped")
    void testStopExecution() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(50), () -> counter.incrementAndGet());
        behavior.setAgent(agent);

        // When - execute once, then stop
        behavior.execute().join();
        behavior.stop();

        assertThat(counter.get()).isEqualTo(1);

        // Then - should not execute after stop
        behavior.execute().join();
        assertThat(counter.get()).isEqualTo(1); // no change
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should support reactivation and continue cycling")
    void testReactivation() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(50), () -> counter.incrementAndGet());
        behavior.setAgent(agent);

        // When - execute, stop, reactivate
        behavior.execute().join();
        behavior.stop();
        assertThat(counter.get()).isEqualTo(1);

        boolean activated = behavior.activate();
        assertThat(activated).isTrue();
        assertThat(behavior.isActive()).isTrue();

        // Then - can execute again
        behavior.execute().join();
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle multiple stop calls idempotently")
    void testMultipleStopCalls() {
        // Given
        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(100), () -> {});
        behavior.setAgent(agent);

        // When
        behavior.stop();
        behavior.stop();
        behavior.stop();

        // Then - should handle gracefully
        assertThat(behavior.isActive()).isFalse();
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle exceptions without stopping")
    void testExceptionHandling() {
        // Given
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger executionCount = new AtomicInteger(0);

        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(50), () -> {
            int count = executionCount.incrementAndGet();
            if (count == 2) {
                throw new RuntimeException("Test exception");
            }
            successCount.incrementAndGet();
        });
        behavior.setAgent(agent);

        // When - execute 3 times (2nd will fail)
        behavior.execute().join(); // success
        behavior.execute().join(); // exception
        behavior.execute().join(); // success

        // Then - should continue after exception
        assertThat(executionCount.get()).isEqualTo(3);
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(behavior.isActive()).isTrue(); // still active
    }

    // =========================================================================
    // INTERVAL VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle very short intervals")
    void testShortInterval() {
        // Given
        Duration shortInterval = Duration.ofMillis(1);

        // When
        CyclicBehavior behavior = CyclicBehavior.from(shortInterval, () -> {});

        // Then
        assertThat(behavior.getInterval()).isEqualTo(shortInterval);
    }

    @Test
    @DisplayName("Should handle long intervals")
    void testLongInterval() {
        // Given
        Duration longInterval = Duration.ofHours(1);

        // When
        CyclicBehavior behavior = CyclicBehavior.from(longInterval, () -> {});

        // Then
        assertThat(behavior.getInterval()).isEqualTo(longInterval);
        assertThat(behavior.getInterval().toHours()).isEqualTo(1);
    }

    // =========================================================================
    // CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle concurrent executions safely")
    void testConcurrentExecutions() throws InterruptedException {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        CyclicBehavior behavior = CyclicBehavior.from(Duration.ofMillis(10), () -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        behavior.setAgent(agent);

        // When - execute concurrently from multiple threads
        for (int i = 0; i < 5; i++) {
            new Thread(() -> behavior.execute().join()).start();
        }

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(5);
        assertThat(behavior.isActive()).isTrue();
    }

    // =========================================================================
    // INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should integrate with agent context")
    void testAgentIntegration() {
        // Given
        CyclicBehavior behavior = CyclicBehavior.from("integration-test", Duration.ofSeconds(1), () -> {});

        // When
        behavior.setAgent(agent);

        // Then
        assertThat(behavior.getAgent()).isEqualTo(agent);
        assertThat(behavior.getAgent().getAgentId()).isEqualTo(agent.getAgentId());
    }

    @Test
    @DisplayName("Should work with different interval units")
    void testDifferentIntervalUnits() {
        // Given/When
        CyclicBehavior seconds = CyclicBehavior.from(Duration.ofSeconds(5), () -> {});
        CyclicBehavior millis = CyclicBehavior.from(Duration.ofMillis(500), () -> {});
        CyclicBehavior minutes = CyclicBehavior.from(Duration.ofMinutes(2), () -> {});

        // Then
        assertThat(seconds.getInterval().getSeconds()).isEqualTo(5);
        assertThat(millis.getInterval().toMillis()).isEqualTo(500);
        assertThat(minutes.getInterval().toMinutes()).isEqualTo(2);
    }

    // =========================================================================
    // HELPER CLASSES
    // =========================================================================

    static class TestAgent extends BaseAgent {
        TestAgent() {
            super("test-agent", "Test Agent");
        }
    }
}
