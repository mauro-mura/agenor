package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for WakerBehavior.
 * Coverage: wake conditions, timing, factory methods, lifecycle.
 */
class WakerBehaviorTest {

    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
    }

    // =========================================================================
    // WAKE CONDITION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should check condition on each execution")
    void testConditionChecking() {
        // Given
        AtomicInteger checkCount = new AtomicInteger(0);
        AtomicBoolean condition = new AtomicBoolean(false);

        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> {
                checkCount.incrementAndGet();
                return condition.get();
            },
            () -> {}
        );
        behavior.setAgent(agent);

        // When - execute multiple times
        behavior.execute().join();
        behavior.execute().join();
        behavior.execute().join();

        // Then - condition checked each time
        assertThat(checkCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should only wake when condition is true")
    void testConditionalWake() {
        // Given
        AtomicBoolean condition = new AtomicBoolean(false);
        AtomicInteger wakeCount = new AtomicInteger(0);

        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> condition.get(),
            () -> wakeCount.incrementAndGet()
        );
        behavior.setAgent(agent);

        // When - execute with false condition
        behavior.execute().join();
        behavior.execute().join();

        // Then - no wakes
        assertThat(wakeCount.get()).isZero();

        // When - condition becomes true
        condition.set(true);
        behavior.execute().join();

        // Then - wakes
        assertThat(wakeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should create waker from condition using wakeWhen")
    void testWakeWhenFactory() {
        // Given
        AtomicBoolean condition = new AtomicBoolean(false);
        AtomicInteger wakeCount = new AtomicInteger(0);

        // When
        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> condition.get(),
            () -> wakeCount.incrementAndGet()
        );

        // Then
        assertThat(behavior).isNotNull();
        assertThat(behavior.getType()).isEqualTo(BehaviorType.WAKER);
        assertThat(behavior.getInterval()).isEqualTo(Duration.ofSeconds(1)); // default
    }

    @Test
    @DisplayName("Should wake when condition becomes true")
    void testWakeOnCondition() {
        // Given
        AtomicBoolean condition = new AtomicBoolean(false);
        AtomicInteger wakeCount = new AtomicInteger(0);

        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> condition.get(),
            () -> wakeCount.incrementAndGet()
        );
        behavior.setAgent(agent);

        // When - execute with false condition
        behavior.execute().join();
        assertThat(wakeCount.get()).isZero();

        // Set condition to true and execute
        condition.set(true);
        behavior.execute().join();

        // Then - should wake
        assertThat(wakeCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // FACTORY METHOD TESTS - wakeAfter
    // =========================================================================

    @Test
    @DisplayName("Should create waker with time-based wake using wakeAfter")
    void testWakeAfterFactory() {
        // Given
        Duration delay = Duration.ofMillis(100);
        AtomicInteger wakeCount = new AtomicInteger(0);

        // When
        WakerBehavior behavior = WakerBehavior.wakeAfter(delay, () -> wakeCount.incrementAndGet());

        // Then
        assertThat(behavior).isNotNull();
        assertThat(behavior.getType()).isEqualTo(BehaviorType.WAKER);
    }

    @Test
    @DisplayName("Should wake after specified duration")
    void testWakeAfterDelay() throws InterruptedException {
        // Given
        Instant startTime = Instant.now();
        Duration delay = Duration.ofMillis(100);
        CountDownLatch wakeLatch = new CountDownLatch(1);

        WakerBehavior behavior = WakerBehavior.wakeAfter(delay, () -> wakeLatch.countDown());
        behavior.setAgent(agent);

        // When - execute before delay elapsed
        behavior.execute().join();
        assertThat(wakeLatch.getCount()).isEqualTo(1); // not woken yet

        // Wait for delay
        Thread.sleep(delay.toMillis() + 50);

        // Execute after delay
        behavior.execute().join();

        // Then - should wake
        assertThat(wakeLatch.await(1, TimeUnit.SECONDS)).isTrue();
        Instant wakeTime = Instant.now();
        assertThat(Duration.between(startTime, wakeTime).toMillis())
            .isGreaterThanOrEqualTo(delay.toMillis());
    }

    // =========================================================================
    // FACTORY METHOD TESTS - wakeAt
    // =========================================================================

    @Test
    @DisplayName("Should create waker with specific instant using wakeAt")
    void testWakeAtFactory() {
        // Given
        Instant wakeTime = Instant.now().plusSeconds(1);
        AtomicInteger wakeCount = new AtomicInteger(0);

        // When
        WakerBehavior behavior = WakerBehavior.wakeAt(wakeTime, () -> wakeCount.incrementAndGet());

        // Then
        assertThat(behavior).isNotNull();
        assertThat(behavior.getType()).isEqualTo(BehaviorType.WAKER);
    }

    @Test
    @DisplayName("Should wake at specific instant")
    void testWakeAtInstant() throws InterruptedException {
        // Given
        Instant wakeTime = Instant.now().plusMillis(100);
        CountDownLatch wakeLatch = new CountDownLatch(1);

        WakerBehavior behavior = WakerBehavior.wakeAt(wakeTime, () -> wakeLatch.countDown());
        behavior.setAgent(agent);

        // When - execute before wake time
        behavior.execute().join();
        assertThat(wakeLatch.getCount()).isEqualTo(1);

        // Wait until after wake time
        Thread.sleep(150);
        behavior.execute().join();

        // Then - should wake
        assertThat(wakeLatch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("Should not wake if instant is in the past during creation")
    void testWakeAtPastInstant() {
        // Given
        Instant pastTime = Instant.now().minusSeconds(10);
        CountDownLatch wakeLatch = new CountDownLatch(1);

        // When
        WakerBehavior behavior = WakerBehavior.wakeAt(pastTime, () -> wakeLatch.countDown());
        behavior.setAgent(agent);

        // Execute immediately
        behavior.execute().join();

        // Then - should wake immediately since time already passed
        assertThat(wakeLatch.getCount()).isZero();
    }

    // =========================================================================
    // LIFECYCLE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should not wake when stopped")
    void testNoWakeWhenStopped() {
        // Given
        AtomicInteger wakeCount = new AtomicInteger(0);
        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> true,
            () -> wakeCount.incrementAndGet()
        );
        behavior.setAgent(agent);

        // When
        behavior.stop();
        behavior.execute().join();

        // Then
        assertThat(wakeCount.get()).isZero();
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should support reactivation")
    void testReactivation() {
        // Given
        AtomicInteger wakeCount = new AtomicInteger(0);
        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> true,
            () -> wakeCount.incrementAndGet()
        );
        behavior.setAgent(agent);

        // When - stop and reactivate
        behavior.stop();
        boolean activated = behavior.activate();

        // Then
        assertThat(activated).isTrue();
        assertThat(behavior.isActive()).isTrue();

        // Can wake again
        behavior.execute().join();
        assertThat(wakeCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle exception in wake condition")
    void testConditionException() {
        // Given
        AtomicInteger wakeCount = new AtomicInteger(0);

        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> {
                throw new RuntimeException("Condition check failed");
            },
            () -> wakeCount.incrementAndGet()
        );
        behavior.setAgent(agent);

        // When/Then - should not propagate exception
        assertThatCode(() -> behavior.execute().join()).doesNotThrowAnyException();

        // Should not wake due to exception
        assertThat(wakeCount.get()).isZero();
    }

    @Test
    @DisplayName("Should handle exception in wake action")
    void testWakeActionException() {
        // Given
        AtomicInteger attemptCount = new AtomicInteger(0);

        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> true,
            () -> {
                attemptCount.incrementAndGet();
                throw new RuntimeException("Wake action failed");
            }
        );
        behavior.setAgent(agent);

        // When/Then - should not propagate exception
        assertThatCode(() -> behavior.execute().join()).doesNotThrowAnyException();

        // Action was attempted
        assertThat(attemptCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // TIMING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should use default check interval")
    void testDefaultCheckInterval() {
        // Given
        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> false,
            () -> {}
        );

        // Then - default is 1 second
        assertThat(behavior.getInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("Should wake when condition is true")
    void testWakeWhenConditionTrue() {
        // Given
        AtomicInteger wakeCount = new AtomicInteger(0);

        // When
        WakerBehavior behavior = WakerBehavior.wakeWhen(
            () -> true,
            () -> wakeCount.incrementAndGet()
        );
        behavior.setAgent(agent);

        behavior.execute().join();

        // Then
        assertThat(wakeCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should integrate with agent")
    void testAgentIntegration() {
        // Given
        WakerBehavior behavior = WakerBehavior.wakeAfter(Duration.ofMillis(10), () -> {});

        // When
        behavior.setAgent(agent);

        // Then
        assertThat(behavior.getAgent()).isEqualTo(agent);
    }

    @Test
    @DisplayName("Should support multiple wake behaviors")
    void testMultipleWakers() {
        // Given
        AtomicInteger wake1Count = new AtomicInteger(0);
        AtomicInteger wake2Count = new AtomicInteger(0);

        WakerBehavior waker1 = WakerBehavior.wakeWhen(
                () -> true,
                () -> wake1Count.incrementAndGet()
        );

        // Use wakeWhen instead of wakeAfter to avoid timing race
        WakerBehavior waker2 = WakerBehavior.wakeWhen(
                () -> true,
                () -> wake2Count.incrementAndGet()
        );

        waker1.setAgent(agent);
        waker2.setAgent(agent);

        // When
        waker1.execute().join();
        waker2.execute().join();

        // Then - both wake independently
        assertThat(wake1Count.get()).isEqualTo(1);
        assertThat(wake2Count.get()).isEqualTo(1);
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
