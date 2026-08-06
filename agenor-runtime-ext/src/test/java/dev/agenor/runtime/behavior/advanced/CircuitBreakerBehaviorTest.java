package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for CircuitBreakerBehavior.
 */
class CircuitBreakerBehaviorTest {

    private static final Duration TEST_RECOVERY_TIMEOUT = Duration.ofMillis(500);
    private static final int TEST_FAILURE_THRESHOLD = 3;
    private static final int TEST_SUCCESS_THRESHOLD = 2;

    private TestCircuitBreaker circuitBreaker;
    private AtomicInteger callCount;
    private AtomicInteger successCount;
    private AtomicInteger failureCount;

    @BeforeEach
    void setUp() {
        callCount = new AtomicInteger(0);
        successCount = new AtomicInteger(0);
        failureCount = new AtomicInteger(0);

        circuitBreaker = new TestCircuitBreaker(
            "test-breaker",
            TEST_FAILURE_THRESHOLD,
            TEST_RECOVERY_TIMEOUT,
            TEST_SUCCESS_THRESHOLD
        );
    }

    // ==================== BASIC FUNCTIONALITY ====================

    @Test
    @DisplayName("Should start in CLOSED state")
    void shouldStartInClosedState() {
        assertEquals(CircuitBreakerBehavior.State.CLOSED, circuitBreaker.getCurrentState());
        assertTrue(circuitBreaker.isClosed());
        assertFalse(circuitBreaker.isOpen());
        assertFalse(circuitBreaker.isHalfOpen());
    }

    @Test
    @DisplayName("Should return correct behavior type")
    void shouldReturnCorrectBehaviorType() {
        assertEquals(BehaviorType.CIRCUIT_BREAKER, circuitBreaker.getType());
    }

    @Test
    @DisplayName("Should execute successful action in CLOSED state")
    void shouldExecuteSuccessfulActionInClosedState() throws Exception {
        circuitBreaker.setAlwaysSucceed(true);

        String result = circuitBreaker.call();

        assertEquals("success", result);
        assertEquals(1, callCount.get());
        assertTrue(circuitBreaker.isClosed());
    }

    // ==================== STATE TRANSITIONS ====================

    @Test
    @DisplayName("Should transition to OPEN after threshold failures")
    void shouldTransitionToOpenAfterThresholdFailures() throws Exception {
        circuitBreaker.setAlwaysFail(true);

        // Execute failures up to threshold
        for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
            assertThrows(RuntimeException.class, () -> circuitBreaker.call());
            if (i < TEST_FAILURE_THRESHOLD - 1) {
                assertTrue(circuitBreaker.isClosed(), "Should still be CLOSED before threshold");
            }
        }

        // Circuit should now be OPEN
        assertTrue(circuitBreaker.isOpen());
        assertEquals(TEST_FAILURE_THRESHOLD, callCount.get());
    }

    @Test
    @DisplayName("Should reject requests when OPEN")
    void shouldRejectRequestsWhenOpen() throws Exception {
        // Trip the circuit
        circuitBreaker.trip();
        assertTrue(circuitBreaker.isOpen());

        // Attempt to call should be rejected
        assertThrows(
            CircuitBreakerBehavior.CircuitBreakerOpenException.class,
            () -> circuitBreaker.call()
        );

        // Action should not be executed
        assertEquals(0, callCount.get());
    }

    @Test
    @DisplayName("Should transition to HALF_OPEN after recovery timeout")
    void shouldTransitionToHalfOpenAfterRecoveryTimeout() throws Exception {
        // Trip the circuit
        circuitBreaker.trip();
        assertTrue(circuitBreaker.isOpen());

        // Wait for recovery timeout
        Thread.sleep(TEST_RECOVERY_TIMEOUT.toMillis() + 100);

        // Next call should transition to HALF_OPEN
        circuitBreaker.setAlwaysSucceed(true);
        String result = circuitBreaker.call();

        assertEquals("success", result);
        assertTrue(circuitBreaker.isHalfOpen());
    }

    @Test
    @DisplayName("Should transition from HALF_OPEN to CLOSED after success threshold")
    void shouldTransitionFromHalfOpenToClosedAfterSuccessThreshold() throws Exception {
        // Trip and transition to HALF_OPEN
        circuitBreaker.trip();
        Thread.sleep(TEST_RECOVERY_TIMEOUT.toMillis() + 100);

        circuitBreaker.setAlwaysSucceed(true);
        circuitBreaker.call(); // First call transitions to HALF_OPEN

        assertTrue(circuitBreaker.isHalfOpen());

        // Execute successful calls up to success threshold
        for (int i = 1; i < TEST_SUCCESS_THRESHOLD; i++) {
            circuitBreaker.call();
        }

        // Circuit should now be CLOSED
        assertTrue(circuitBreaker.isClosed());
        assertEquals(TEST_SUCCESS_THRESHOLD, callCount.get());
    }

    @Test
    @DisplayName("Should transition from HALF_OPEN to OPEN on any failure")
    void shouldTransitionFromHalfOpenToOpenOnFailure() throws Exception {
        // Trip and transition to HALF_OPEN
        circuitBreaker.trip();
        Thread.sleep(TEST_RECOVERY_TIMEOUT.toMillis() + 100);

        circuitBreaker.setAlwaysSucceed(true);
        circuitBreaker.call(); // Transition to HALF_OPEN

        assertTrue(circuitBreaker.isHalfOpen());

        // Single failure should reopen circuit
        circuitBreaker.setAlwaysFail(true);
        assertThrows(RuntimeException.class, () -> circuitBreaker.call());

        assertTrue(circuitBreaker.isOpen());
    }

    // ==================== METRICS ====================

    @Test
    @DisplayName("Should track request metrics correctly")
    void shouldTrackRequestMetricsCorrectly() throws Exception {
        circuitBreaker.setAlwaysSucceed(true);

        // Execute some successful requests
        for (int i = 0; i < 5; i++) {
            circuitBreaker.call();
        }

        var metrics = circuitBreaker.getMetrics();

        assertEquals(5, metrics.totalRequests());
        assertEquals(5, metrics.successfulRequests());
        assertEquals(0, metrics.failedRequests());
        assertEquals(0, metrics.rejectedRequests());
        assertEquals(100.0, metrics.successRate(), 0.01);
    }

    @Test
    @DisplayName("Should track failure metrics correctly")
    void shouldTrackFailureMetricsCorrectly() throws Exception {
        circuitBreaker.setAlwaysFail(true);

        // Execute failures
        for (int i = 0; i < TEST_FAILURE_THRESHOLD; i++) {
            try {
                circuitBreaker.call();
            } catch (Exception e) {
                // Expected
            }
        }

        var metrics = circuitBreaker.getMetrics();

        assertEquals(TEST_FAILURE_THRESHOLD, metrics.totalRequests());
        assertEquals(0, metrics.successfulRequests());
        assertEquals(TEST_FAILURE_THRESHOLD, metrics.failedRequests());
        assertEquals(100.0, metrics.failureRate(), 0.01);
    }

    @Test
    @DisplayName("Should track rejected requests when OPEN")
    void shouldTrackRejectedRequestsWhenOpen() throws Exception {
        // Trip the circuit
        circuitBreaker.trip();

        // Try multiple requests
        for (int i = 0; i < 5; i++) {
            try {
                circuitBreaker.call();
            } catch (CircuitBreakerBehavior.CircuitBreakerOpenException e) {
                // Expected
            }
        }

        var metrics = circuitBreaker.getMetrics();
        assertEquals(5, metrics.rejectedRequests());
        assertEquals(0, metrics.successfulRequests());
    }

    @Test
    @DisplayName("Should track state changes")
    void shouldTrackStateChanges() throws Exception {
        var initialMetrics = circuitBreaker.getMetrics();
        assertEquals(0, initialMetrics.stateChangeCount());

        // Trip to OPEN
        circuitBreaker.trip();
        assertEquals(1, circuitBreaker.getMetrics().stateChangeCount());

        // Reset to CLOSED
        circuitBreaker.reset();
        assertEquals(2, circuitBreaker.getMetrics().stateChangeCount());
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void shouldResetMetricsCorrectly() throws Exception {
        // Generate some metrics
        circuitBreaker.setAlwaysSucceed(true);
        for (int i = 0; i < 10; i++) {
            circuitBreaker.call();
        }

        // Reset
        circuitBreaker.resetMetrics();

        var metrics = circuitBreaker.getMetrics();
        assertEquals(0, metrics.totalRequests());
        assertEquals(0, metrics.successfulRequests());
        assertEquals(0, metrics.failedRequests());
        assertEquals(0, metrics.rejectedRequests());
    }

    // ==================== CALLBACKS ====================

    @Test
    @DisplayName("Should invoke state change callback")
    void shouldInvokeStateChangeCallback() throws Exception {
        AtomicReference<CircuitBreakerBehavior.State> capturedState = new AtomicReference<>();
        circuitBreaker.onStateChange(capturedState::set);

        // Trip to OPEN
        circuitBreaker.trip();

        assertEquals(CircuitBreakerBehavior.State.OPEN, capturedState.get());
    }

    @Test
    @DisplayName("Should invoke success callback")
    void shouldInvokeSuccessCallback() throws Exception {
        AtomicReference<String> capturedResult = new AtomicReference<>();
        circuitBreaker.onSuccess(capturedResult::set);

        circuitBreaker.setAlwaysSucceed(true);
        circuitBreaker.call();

        assertEquals("success", capturedResult.get());
    }

    @Test
    @DisplayName("Should invoke failure callback")
    void shouldInvokeFailureCallback() throws Exception {
        AtomicReference<Exception> capturedException = new AtomicReference<>();
        circuitBreaker.onFailure(capturedException::set);

        circuitBreaker.setAlwaysFail(true);

        try {
            circuitBreaker.call();
        } catch (Exception e) {
            // Expected
        }

        assertNotNull(capturedException.get());
        assertTrue(capturedException.get() instanceof RuntimeException);
    }

    // ==================== CONCURRENCY ====================

    @Test
    @DisplayName("Should handle concurrent requests safely")
    @Timeout(5)
    void shouldHandleConcurrentRequestsSafely() throws Exception {
        int threadCount = 10;
        int requestsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        circuitBreaker.setAlwaysSucceed(true);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        circuitBreaker.call();
                    }
                } catch (Exception e) {
                    // Ignore for this test
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        executor.shutdown();

        var metrics = circuitBreaker.getMetrics();
        assertEquals(threadCount * requestsPerThread, metrics.totalRequests());
        assertTrue(circuitBreaker.isClosed());
    }

    @Test
    @DisplayName("Should handle concurrent failures and state transitions")
    @Timeout(5)
    void shouldHandleConcurrentFailuresAndStateTransitions() throws Exception {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        circuitBreaker.setAlwaysFail(true);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        try {
                            circuitBreaker.call();
                        } catch (Exception e) {
                            // Expected
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        executor.shutdown();

        // Circuit should be OPEN due to failures
        assertTrue(circuitBreaker.isOpen());
    }

    // ==================== EDGE CASES ====================

    @Test
    @DisplayName("Should handle zero success threshold")
    void shouldRejectZeroSuccessThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
            new TestCircuitBreaker("test", 5, Duration.ofSeconds(30), 0)
        );
    }

    @Test
    @DisplayName("Should handle zero failure threshold")
    void shouldRejectZeroFailureThreshold() {
        assertThrows(IllegalArgumentException.class, () ->
            new TestCircuitBreaker("test", 0, Duration.ofSeconds(30), 2)
        );
    }

    @Test
    @DisplayName("Should handle negative recovery timeout")
    void shouldRejectNegativeRecoveryTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
            new TestCircuitBreaker("test", 5, Duration.ofSeconds(-1), 2)
        );
    }

    @Test
    @DisplayName("Should handle manual reset in various states")
    void shouldHandleManualResetInVariousStates() throws Exception {
        // Reset from CLOSED
        circuitBreaker.reset();
        assertTrue(circuitBreaker.isClosed());

        // Trip and reset from OPEN
        circuitBreaker.trip();
        assertTrue(circuitBreaker.isOpen());
        circuitBreaker.reset();
        assertTrue(circuitBreaker.isClosed());

        // Transition to HALF_OPEN and reset
        circuitBreaker.trip();
        Thread.sleep(TEST_RECOVERY_TIMEOUT.toMillis() + 100);
        circuitBreaker.setAlwaysSucceed(true);
        circuitBreaker.call(); // Transition to HALF_OPEN
        assertTrue(circuitBreaker.isHalfOpen());
        circuitBreaker.reset();
        assertTrue(circuitBreaker.isClosed());
    }

    @Test
    @DisplayName("Should handle manual trip in various states")
    void shouldHandleManualTripInVariousStates() {
        // Trip from CLOSED
        assertTrue(circuitBreaker.isClosed());
        circuitBreaker.trip();
        assertTrue(circuitBreaker.isOpen());

        // Reset and trip again
        circuitBreaker.reset();
        assertTrue(circuitBreaker.isClosed());
        circuitBreaker.trip();
        assertTrue(circuitBreaker.isOpen());
    }

    // ==================== FACTORY METHODS ====================

    @Test
    @DisplayName("Should create circuit breaker with standard factory")
    void shouldCreateCircuitBreakerWithStandardFactory() throws Exception {
        var breaker = CircuitBreakerBehavior.standard(
            "standard-breaker",
            () -> "factory-result"
        );

        String result = breaker.call();
        assertEquals("factory-result", result);
        assertTrue(breaker.isClosed());
    }

    @Test
    @DisplayName("Should create circuit breaker with custom factory")
    void shouldCreateCircuitBreakerWithCustomFactory() throws Exception {
        var breaker = CircuitBreakerBehavior.custom(
            "custom-breaker",
            () -> "custom-result",
            10,
            Duration.ofMinutes(1),
            5
        );

        String result = breaker.call();
        assertEquals("custom-result", result);
        assertTrue(breaker.isClosed());
    }

    // ==================== HELPER CLASSES ====================

    /**
     * Test implementation of CircuitBreakerBehavior for testing purposes.
     */
    private class TestCircuitBreaker extends CircuitBreakerBehavior<String> {
        private volatile boolean alwaysSucceed = false;
        private volatile boolean alwaysFail = false;

        public TestCircuitBreaker(String behaviorId,
                                 int failureThreshold,
                                 Duration recoveryTimeout,
                                 int successThreshold) {
            super(behaviorId, failureThreshold, recoveryTimeout, successThreshold);
        }

        @Override
        protected String executeAction() throws Exception {
            callCount.incrementAndGet();

            if (alwaysFail) {
                failureCount.incrementAndGet();
                throw new RuntimeException("Simulated failure");
            }

            if (alwaysSucceed) {
                successCount.incrementAndGet();
                return "success";
            }

            // Default: random behavior
            if (Math.random() < 0.5) {
                successCount.incrementAndGet();
                return "success";
            } else {
                failureCount.incrementAndGet();
                throw new RuntimeException("Random failure");
            }
        }

        public void setAlwaysSucceed(boolean value) {
            this.alwaysSucceed = value;
            this.alwaysFail = false;
        }

        public void setAlwaysFail(boolean value) {
            this.alwaysFail = value;
            this.alwaysSucceed = false;
        }
    }
}
