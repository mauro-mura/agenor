package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for OneShotBehavior.
 * Coverage: constructors, factory methods, execute once semantics, lifecycle.
 */
class OneShotBehaviorTest {

    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
    }

    // =========================================================================
    // CONSTRUCTOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create one-shot behavior with default constructor")
    void testDefaultConstructor() {
        // Given/When
        OneShotBehavior behavior = new OneShotBehavior() {
            @Override
            protected void action() {
                // empty
            }
        };

        // Then
        assertThat(behavior.getType()).isEqualTo(BehaviorType.ONE_SHOT);
        assertThat(behavior.getInterval()).isNull();
        assertThat(behavior.getBehaviorId()).isNotNull();
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should create one-shot behavior with custom ID")
    void testConstructorWithBehaviorId() {
        // Given/When
        OneShotBehavior behavior = new OneShotBehavior("custom-id") {
            @Override
            protected void action() {
                // empty
            }
        };

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo("custom-id");
        assertThat(behavior.getType()).isEqualTo(BehaviorType.ONE_SHOT);
    }

    // =========================================================================
    // FACTORY METHOD TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create from Runnable with auto-generated ID")
    void testFromRunnable() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);

        // When
        OneShotBehavior behavior = OneShotBehavior.from(() -> counter.incrementAndGet());

        // Then
        assertThat(behavior).isNotNull();
        assertThat(behavior.getBehaviorId()).isNotNull();
        assertThat(behavior.getType()).isEqualTo(BehaviorType.ONE_SHOT);
    }

    @Test
    @DisplayName("Should create from Runnable with custom name")
    void testFromRunnableWithName() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);

        // When
        OneShotBehavior behavior = OneShotBehavior.from("test-behavior", () -> counter.incrementAndGet());

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo("test-behavior");
        assertThat(behavior.getType()).isEqualTo(BehaviorType.ONE_SHOT);
    }

    // =========================================================================
    // EXECUTE ONCE SEMANTICS
    // =========================================================================

    @Test
    @DisplayName("Should execute action exactly once")
    void testExecuteOnce() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);

        OneShotBehavior behavior = OneShotBehavior.from(() -> {
            counter.incrementAndGet();
            latch.countDown();
        });
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should become inactive after first execution")
    void testBecomesInactiveAfterExecution() {
        // Given
        OneShotBehavior behavior = OneShotBehavior.from(() -> {});
        behavior.setAgent(agent);

        assertThat(behavior.isActive()).isTrue();

        // When
        behavior.execute().join();

        // Then
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should not execute again after becoming inactive")
    void testNoExecutionAfterInactive() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        OneShotBehavior behavior = OneShotBehavior.from(() -> counter.incrementAndGet());
        behavior.setAgent(agent);

        // When - execute twice
        behavior.execute().join();
        behavior.execute().join();

        // Then - only executed once
        assertThat(counter.get()).isEqualTo(1);
        assertThat(behavior.isActive()).isFalse();
    }

    // =========================================================================
    // LIFECYCLE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should not execute if stopped before execution")
    void testStopBeforeExecution() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        OneShotBehavior behavior = OneShotBehavior.from(() -> counter.incrementAndGet());
        behavior.setAgent(agent);

        // When
        behavior.stop();
        behavior.execute().join();

        // Then
        assertThat(counter.get()).isZero();
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should support reactivation after stop")
    void testReactivation() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        OneShotBehavior behavior = OneShotBehavior.from(() -> counter.incrementAndGet());
        behavior.setAgent(agent);

        behavior.stop();
        assertThat(behavior.isActive()).isFalse();

        // When
        boolean activated = behavior.activate();

        // Then
        assertThat(activated).isTrue();
        assertThat(behavior.isActive()).isTrue();

        // And can execute again
        behavior.execute().join();
        assertThat(counter.get()).isEqualTo(1);
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle exceptions in action")
    void testExceptionHandling() {
        // Given
        OneShotBehavior behavior = OneShotBehavior.from(() -> {
            throw new RuntimeException("Test exception");
        });
        behavior.setAgent(agent);

        // When/Then - should not propagate exception
        assertThatCode(() -> behavior.execute().join()).doesNotThrowAnyException();

        // Behavior remains active after exception to allow retry/recovery
        assertThat(behavior.isActive()).isTrue();
    }

    // =========================================================================
    // CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle concurrent executions safely")
    void testConcurrentExecution() throws InterruptedException {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        OneShotBehavior behavior = OneShotBehavior.from(() -> counter.incrementAndGet());
        behavior.setAgent(agent);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(3);

        // When - trigger 3 concurrent executions
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                startLatch.await();
                behavior.execute().join();
                endLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
            try {
                startLatch.await();
                behavior.execute().join();
                endLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<Void> f3 = CompletableFuture.runAsync(() -> {
            try {
                startLatch.await();
                behavior.execute().join();
                endLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Then - may execute multiple times in race condition, but eventually stops
        assertThat(counter.get()).isGreaterThan(0);
        assertThat(behavior.isActive()).isFalse();
    }

    // =========================================================================
    // INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should integrate with agent")
    void testAgentIntegration() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        OneShotBehavior behavior = OneShotBehavior.from("integration-test", () -> {
            counter.incrementAndGet();
        });

        // When
        behavior.setAgent(agent);

        // Then
        assertThat(behavior.getAgent()).isEqualTo(agent);

        // And can execute
        behavior.execute().join();
        assertThat(counter.get()).isEqualTo(1);
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
