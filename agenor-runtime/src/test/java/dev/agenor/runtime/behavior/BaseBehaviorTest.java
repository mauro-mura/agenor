package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for BaseBehavior.
 * Coverage: lifecycle, agent association, activation, error handling, common methods.
 */
class BaseBehaviorTest {

    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
    }

    // =========================================================================
    // CONSTRUCTOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create with type only")
    void testConstructorWithType() {
        // When
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);

        // Then
        assertThat(behavior.getType()).isEqualTo(BehaviorType.ONE_SHOT);
        assertThat(behavior.getBehaviorId()).isNotNull();
        assertThat(behavior.getInterval()).isNull();
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should create with type and interval")
    void testConstructorWithTypeAndInterval() {
        // Given
        Duration interval = Duration.ofSeconds(5);

        // When
        TestBehavior behavior = new TestBehavior(BehaviorType.CYCLIC, interval);

        // Then
        assertThat(behavior.getType()).isEqualTo(BehaviorType.CYCLIC);
        assertThat(behavior.getInterval()).isEqualTo(interval);
        assertThat(behavior.getBehaviorId()).isNotNull();
    }

    @Test
    @DisplayName("Should create with custom ID, type and interval")
    void testConstructorWithIdTypeAndInterval() {
        // Given
        String behaviorId = "custom-behavior";
        Duration interval = Duration.ofMillis(100);

        // When
        TestBehavior behavior = new TestBehavior(behaviorId, BehaviorType.CYCLIC, interval);

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo(behaviorId);
        assertThat(behavior.getType()).isEqualTo(BehaviorType.CYCLIC);
        assertThat(behavior.getInterval()).isEqualTo(interval);
    }

    @Test
    @DisplayName("Should generate unique IDs for behaviors without explicit ID")
    void testUniqueIdGeneration() {
        // When
        TestBehavior behavior1 = new TestBehavior(BehaviorType.ONE_SHOT);
        TestBehavior behavior2 = new TestBehavior(BehaviorType.ONE_SHOT);

        // Then
        assertThat(behavior1.getBehaviorId()).isNotEqualTo(behavior2.getBehaviorId());
    }

    // =========================================================================
    // AGENT ASSOCIATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should set and get agent")
    void testSetAndGetAgent() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);

        // When
        behavior.setAgent(agent);

        // Then
        assertThat(behavior.getAgent()).isEqualTo(agent);
    }

    @Test
    @DisplayName("Should allow null agent")
    void testNullAgent() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);

        // When
        behavior.setAgent(null);

        // Then
        assertThat(behavior.getAgent()).isNull();
    }

    @Test
    @DisplayName("Should allow agent reassignment")
    void testAgentReassignment() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        TestAgent agent2 = new TestAgent();

        // When
        behavior.setAgent(agent);
        assertThat(behavior.getAgent()).isEqualTo(agent);

        behavior.setAgent(agent2);

        // Then
        assertThat(behavior.getAgent()).isEqualTo(agent2);
    }

    // =========================================================================
    // ACTIVE/INACTIVE STATE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should start as active")
    void testStartsActive() {
        // When
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);

        // Then
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should become inactive when stopped")
    void testStopMakesInactive() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);

        // When
        behavior.stop();

        // Then
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should handle multiple stop calls idempotently")
    void testMultipleStopCalls() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        AtomicInteger onStopCount = new AtomicInteger(0);
        behavior.onStopCallback = onStopCount::incrementAndGet;

        // When
        behavior.stop();
        behavior.stop();
        behavior.stop();

        // Then
        assertThat(behavior.isActive()).isFalse();
        assertThat(onStopCount.get()).isEqualTo(1); // onStop called only once
    }

    // =========================================================================
    // ACTIVATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should reactivate stopped behavior")
    void testActivation() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.stop();

        assertThat(behavior.isActive()).isFalse();

        // When
        boolean result = behavior.activate();

        // Then
        assertThat(result).isTrue();
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should return false when activating already active behavior")
    void testActivateAlreadyActive() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);

        assertThat(behavior.isActive()).isTrue();

        // When
        boolean result = behavior.activate();

        // Then
        assertThat(result).isFalse();
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should support multiple stop/activate cycles")
    void testMultipleStopActivateCycles() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.CYCLIC, Duration.ofSeconds(1));

        // When/Then - cycle 1
        behavior.stop();
        assertThat(behavior.isActive()).isFalse();
        behavior.activate();
        assertThat(behavior.isActive()).isTrue();

        // cycle 2
        behavior.stop();
        assertThat(behavior.isActive()).isFalse();
        behavior.activate();
        assertThat(behavior.isActive()).isTrue();

        // cycle 3
        behavior.stop();
        assertThat(behavior.isActive()).isFalse();
        behavior.activate();
        assertThat(behavior.isActive()).isTrue();
    }

    // =========================================================================
    // EXECUTE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should execute action when active")
    void testExecuteWhenActive() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.actionCallback = () -> {
            actionExecuted.set(true);
            latch.countDown();
        };
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(actionExecuted.get()).isTrue();
    }

    @Test
    @DisplayName("Should not execute action when inactive")
    void testNotExecuteWhenInactive() {
        // Given
        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.actionCallback = () -> actionExecuted.set(true);
        behavior.setAgent(agent);

        behavior.stop();

        // When
        behavior.execute().join();

        // Then
        assertThat(actionExecuted.get()).isFalse();
    }

    @Test
    @DisplayName("Should stop one-shot behavior after execution")
    void testOneShotAutoStop() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.setAgent(agent);

        assertThat(behavior.isActive()).isTrue();

        // When
        behavior.execute().join();

        // Then
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should not stop cyclic behavior after execution")
    void testCyclicNoAutoStop() {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.CYCLIC, Duration.ofSeconds(1));
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then
        assertThat(behavior.isActive()).isTrue();
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should catch and handle exceptions in action")
    void testExceptionHandling() {
        // Given
        AtomicBoolean errorHandled = new AtomicBoolean(false);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.actionCallback = () -> {
            throw new RuntimeException("Test exception");
        };
        behavior.onErrorCallback = (ex) -> errorHandled.set(true);
        behavior.setAgent(agent);

        // When/Then - should not propagate exception
        assertThatCode(() -> behavior.execute().join()).doesNotThrowAnyException();

        // Error handler was called
        assertThat(errorHandled.get()).isTrue();
    }

    @Test
    @DisplayName("Should invoke onError for any throwable")
    void testOnErrorInvoked() {
        // Given
        AtomicInteger errorCount = new AtomicInteger(0);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.actionCallback = () -> {
            throw new RuntimeException("Test error");
        };
        behavior.onErrorCallback = (ex) -> errorCount.incrementAndGet();
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then
        assertThat(errorCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle Error types")
    void testErrorHandling() {
        // Given
        AtomicBoolean errorHandled = new AtomicBoolean(false);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.actionCallback = () -> {
            throw new AssertionError("Test error");
        };
        behavior.onErrorCallback = (ex) -> errorHandled.set(true);
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then
        assertThat(errorHandled.get()).isTrue();
    }

    // =========================================================================
    // ONSTOP CALLBACK TESTS
    // =========================================================================

    @Test
    @DisplayName("Should invoke onStop when stopped")
    void testOnStopInvoked() {
        // Given
        AtomicBoolean onStopCalled = new AtomicBoolean(false);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.onStopCallback = () -> onStopCalled.set(true);

        // When
        behavior.stop();

        // Then
        assertThat(onStopCalled.get()).isTrue();
    }

    @Test
    @DisplayName("Should invoke onStop only once for multiple stops")
    void testOnStopOnce() {
        // Given
        AtomicInteger onStopCount = new AtomicInteger(0);

        TestBehavior behavior = new TestBehavior(BehaviorType.ONE_SHOT);
        behavior.onStopCallback = onStopCount::incrementAndGet;

        // When
        behavior.stop();
        behavior.stop();
        behavior.stop();

        // Then
        assertThat(onStopCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle concurrent executions safely")
    void testConcurrentExecutions() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger executionCount = new AtomicInteger(0);

        TestBehavior behavior = new TestBehavior(BehaviorType.CYCLIC, Duration.ofMillis(10));
        behavior.actionCallback = () -> {
            executionCount.incrementAndGet();
            latch.countDown();
        };
        behavior.setAgent(agent);

        // When - execute concurrently
        CompletableFuture.allOf(
            behavior.execute(),
            behavior.execute(),
            behavior.execute(),
            behavior.execute(),
            behavior.execute()
        ).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executionCount.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should handle concurrent stop/activate safely")
    void testConcurrentStopActivate() throws InterruptedException {
        // Given
        TestBehavior behavior = new TestBehavior(BehaviorType.CYCLIC, Duration.ofMillis(10));
        CountDownLatch latch = new CountDownLatch(10);

        // When - concurrent stop and activate
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                behavior.stop();
                behavior.activate();
                latch.countDown();
            }).start();
        }

        // Then - should complete without errors
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    // =========================================================================
    // HELPER CLASSES
    // =========================================================================

    static class TestBehavior extends BaseBehavior {
        Runnable actionCallback;
        Runnable onStopCallback;
        java.util.function.Consumer<Exception> onErrorCallback;

        TestBehavior(BehaviorType type) {
            super(type);
        }

        TestBehavior(BehaviorType type, Duration interval) {
            super(type, interval);
        }

        TestBehavior(String behaviorId, BehaviorType type, Duration interval) {
            super(behaviorId, type, interval);
        }

        @Override
        protected void action() {
            if (actionCallback != null) {
                actionCallback.run();
            }
        }

        @Override
        protected void onStop() {
            if (onStopCallback != null) {
                onStopCallback.run();
            }
        }

        @Override
        protected void onError(Exception e) {
            if (onErrorCallback != null) {
                onErrorCallback.accept(e);
            }
        }
    }

    static class TestAgent extends BaseAgent {
        TestAgent() {
            super("test-agent", "Test Agent");
        }
    }
}
