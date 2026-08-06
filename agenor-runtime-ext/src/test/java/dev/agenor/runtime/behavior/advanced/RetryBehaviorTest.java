package dev.agenor.runtime.behavior.advanced;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for RetryBehavior
 */
@DisplayName("RetryBehavior Tests")
class RetryBehaviorTest {

    private AtomicInteger attemptCounter;
    private AtomicBoolean callbackInvoked;

    @BeforeEach
    void setUp() {
        attemptCounter = new AtomicInteger(0);
        callbackInvoked = new AtomicBoolean(false);
    }

    // =========================================================================
    // BASIC FUNCTIONALITY TESTS
    // =========================================================================

    @Test
    @DisplayName("Should succeed on first attempt without retries")
    void testSuccessFirstAttempt() throws Exception {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(100)
        ) {
            @Override
            protected String attemptAction() {
                attemptCounter.incrementAndGet();
                return "success";
            }
        };

        retry.execute().join();

        assertEquals(1, attemptCounter.get(), "Should only attempt once");
        assertEquals(1, retry.getTotalAttempts());
        assertEquals(1, retry.getSuccessCount());
        assertEquals(0, retry.getFailureCount());
        assertEquals("success", retry.getLastSuccessfulResult());
    }

    @Test
    @DisplayName("Should retry on failure and eventually succeed")
    void testRetryAndSucceed() throws Exception {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("Attempt " + attempt + " failed");
                }
                return "success on attempt " + attempt;
            }
        };

        retry.execute().join();

        assertEquals(3, attemptCounter.get(), "Should attempt 3 times");
        assertEquals(3, retry.getTotalAttempts());
        assertEquals(1, retry.getSuccessCount());
        assertEquals(0, retry.getFailureCount());
        assertTrue(retry.getLastSuccessfulResult().contains("success"));
    }

    @Test
    @DisplayName("Should fail after exhausting all retries")
    void testExhaustAllRetries() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                throw new RuntimeException("Always fails");
            }
        };

        retry.execute().join();

        assertEquals(3, attemptCounter.get(), "Should attempt 3 times (initial + 2 retries)");
        assertEquals(3, retry.getTotalAttempts());
        assertEquals(0, retry.getSuccessCount());
        assertEquals(1, retry.getFailureCount());
        assertNotNull(retry.getLastException());
    }

    @Test
    @DisplayName("Should work with zero retries")
    void testZeroRetries() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                0,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                throw new RuntimeException("Fails immediately");
            }
        };

        retry.execute().join();

        assertEquals(1, attemptCounter.get(), "Should only attempt once (no retries)");
        assertEquals(1, retry.getTotalAttempts());
        assertEquals(0, retry.getSuccessCount());
        assertEquals(1, retry.getFailureCount());
    }

    // =========================================================================
    // BACKOFF STRATEGY TESTS
    // =========================================================================

    @Test
    @DisplayName("FIXED backoff should use constant delay")
    void testFixedBackoff() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(100)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                if (attemptCounter.get() < 4) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        long startTime = System.currentTimeMillis();
        retry.execute().join();
        long elapsedTime = System.currentTimeMillis() - startTime;

        // Should take approximately 300ms (3 retries * 100ms each)
        assertTrue(elapsedTime >= 250 && elapsedTime < 400,
                "Fixed backoff timing incorrect: " + elapsedTime + "ms");
        assertEquals(4, attemptCounter.get());
    }

    @Test
    @DisplayName("LINEAR backoff should increase delay linearly")
    void testLinearBackoff() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.LINEAR,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                if (attemptCounter.get() < 4) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        long startTime = System.currentTimeMillis();
        retry.execute().join();
        long elapsedTime = System.currentTimeMillis() - startTime;

        // Linear: 50ms, 100ms, 150ms = 300ms total
        assertTrue(elapsedTime >= 250 && elapsedTime < 400,
                "Linear backoff timing incorrect: " + elapsedTime + "ms");
        assertEquals(4, attemptCounter.get());
    }

    @Test
    @DisplayName("EXPONENTIAL backoff should double delay each time")
    void testExponentialBackoff() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.EXPONENTIAL,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                if (attemptCounter.get() < 4) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        long startTime = System.currentTimeMillis();
        retry.execute().join();
        long elapsedTime = System.currentTimeMillis() - startTime;

        // Exponential: 50ms, 100ms, 200ms = 350ms total
        assertTrue(elapsedTime >= 300 && elapsedTime < 450,
                "Exponential backoff timing incorrect: " + elapsedTime + "ms");
        assertEquals(4, attemptCounter.get());
    }

    @Test
    @DisplayName("JITTER backoff should add randomness to exponential")
    void testJitterBackoff() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.JITTER,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                if (attemptCounter.get() < 4) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        long startTime = System.currentTimeMillis();
        retry.execute().join();
        long elapsedTime = System.currentTimeMillis() - startTime;

        // Jitter: ~262ms to 437ms (75%-125% of exponential 350ms)
        assertTrue(elapsedTime >= 200 && elapsedTime < 500,
                "Jitter backoff timing out of expected range: " + elapsedTime + "ms");
        assertEquals(4, attemptCounter.get());
    }

    @Test
    @DisplayName("Should respect maxDelay cap")
    void testMaxDelayCap() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                5,
                RetryBehavior.BackoffStrategy.EXPONENTIAL,
                Duration.ofMillis(100),
                Duration.ofMillis(200),  // Max delay cap
                null
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                if (attemptCounter.get() < 6) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        long startTime = System.currentTimeMillis();
        retry.execute().join();
        long elapsedTime = System.currentTimeMillis() - startTime;

        // Without cap: 100, 200, 400, 800, 1600 = 3100ms
        // With 200ms cap: 100, 200, 200, 200, 200 = 900ms
        assertTrue(elapsedTime >= 800 && elapsedTime < 1100,
                "Max delay cap not working: " + elapsedTime + "ms");
        assertEquals(6, attemptCounter.get());
    }

    // =========================================================================
    // EXCEPTION FILTERING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should retry on retryable exceptions")
    void testRetryOnRetryableException() {
        class RetryableException extends Exception { }
        class NonRetryableException extends Exception { }

        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RetryableException();
                }
                return "success";
            }

            @Override
            protected boolean shouldRetry(Exception e) {
                return e instanceof RetryableException;
            }
        };

        retry.execute().join();

        assertEquals(3, attemptCounter.get());
        assertEquals(1, retry.getSuccessCount());
    }

    @Test
    @DisplayName("Should not retry on non-retryable exceptions")
    void testNoRetryOnNonRetryableException() {
        class NonRetryableException extends Exception { }

        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                throw new NonRetryableException();
            }

            @Override
            protected boolean shouldRetry(Exception e) {
                return false;  // Never retry
            }
        };

        retry.execute().join();

        assertEquals(1, attemptCounter.get(), "Should only attempt once");
        assertEquals(0, retry.getSuccessCount());
        assertEquals(1, retry.getFailureCount());
    }

    @Test
    @DisplayName("Should use custom retry condition predicate")
    void testCustomRetryCondition() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                int attempt = attemptCounter.incrementAndGet();
                throw new RuntimeException("Error " + attempt);
            }
        };

        // Only retry if error message contains "1" or "2"
        retry.withRetryCondition(e -> {
            String msg = e.getMessage();
            return msg.contains("1") || msg.contains("2");
        });

        retry.execute().join();

        assertEquals(3, attemptCounter.get(), "Should stop after 'Error 3'");
        assertEquals(0, retry.getSuccessCount());
        assertTrue(retry.getLastException().getMessage().contains("3"));
    }

    // =========================================================================
    // CALLBACK TESTS
    // =========================================================================

    @Test
    @DisplayName("Should invoke success callback on success")
    void testSuccessCallback() {
        AtomicInteger callbackValue = new AtomicInteger();

        RetryBehavior<Integer> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected Integer attemptAction() {
                attemptCounter.incrementAndGet();
                return 42;
            }
        };

        retry.onSuccess(result -> {
            callbackInvoked.set(true);
            callbackValue.set(result);
        });

        retry.execute().join();

        assertTrue(callbackInvoked.get(), "Success callback should be invoked");
        assertEquals(42, callbackValue.get());
    }

    @Test
    @DisplayName("Should invoke failure callback after all retries fail")
    void testFailureCallback() {
        AtomicInteger callbackErrorCode = new AtomicInteger();

        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                throw new RuntimeException("Error code: 500");
            }
        };

        retry.onFailure(exception -> {
            callbackInvoked.set(true);
            if (exception.getMessage().contains("500")) {
                callbackErrorCode.set(500);
            }
        });

        retry.execute().join();

        assertTrue(callbackInvoked.get(), "Failure callback should be invoked");
        assertEquals(500, callbackErrorCode.get());
    }

    @Test
    @DisplayName("Should invoke retry callback before each retry")
    void testRetryCallback() {
        AtomicInteger lastRetryNumber = new AtomicInteger();
        AtomicInteger callbackCount = new AtomicInteger();

        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 4) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        retry.onRetry(retryNumber -> {
            callbackCount.incrementAndGet();
            lastRetryNumber.set(retryNumber);
        });

        retry.execute().join();

        assertEquals(3, callbackCount.get(), "Retry callback should be invoked 3 times");
        assertEquals(3, lastRetryNumber.get(), "Last retry should be number 3");
    }

    @Test
    @DisplayName("Should handle callback exceptions gracefully")
    void testCallbackExceptions() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() {
                attemptCounter.incrementAndGet();
                return "success";
            }
        };

        retry.onSuccess(result -> {
            callbackInvoked.set(true);
            throw new RuntimeException("Callback error");
        });

        // Should not throw, just log warning
        assertDoesNotThrow(() -> retry.execute().join());
        assertTrue(callbackInvoked.get());
        assertEquals(1, retry.getSuccessCount());
    }

    // =========================================================================
    // TIMEOUT TESTS
    // =========================================================================

    @Test
    @DisplayName("Should timeout slow attempts")
    void testAttemptTimeout() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50),
                Duration.ofSeconds(5),
                Duration.ofMillis(100)  // Attempt timeout
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                // Simulate slow operation
                Thread.sleep(200);
                return "success";
            }
        };

        retry.execute().join();

        // Should timeout and retry
        assertTrue(attemptCounter.get() > 1, "Should retry after timeout");
        assertEquals(0, retry.getSuccessCount(), "Should fail due to timeouts");
    }

    @Test
    @DisplayName("Should succeed within timeout")
    void testSuccessWithinTimeout() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50),
                Duration.ofSeconds(5),
                Duration.ofMillis(200)  // Generous timeout
        ) {
            @Override
            protected String attemptAction() throws Exception {
                attemptCounter.incrementAndGet();
                Thread.sleep(50);  // Well within timeout
                return "success";
            }
        };

        retry.execute().join();

        assertEquals(1, attemptCounter.get(), "Should succeed on first attempt");
        assertEquals(1, retry.getSuccessCount());
    }

    // =========================================================================
    // FACTORY METHOD TESTS
    // =========================================================================

    @Test
    @DisplayName("withFixedDelay factory should work correctly")
    void testWithFixedDelayFactory() {
        AtomicInteger counter = new AtomicInteger();

        RetryBehavior<String> retry = RetryBehavior.withFixedDelay(
                "test-retry",
                2,
                Duration.ofMillis(50),
                () -> {
                    int count = counter.incrementAndGet();
                    if (count < 3) {
                        throw new RuntimeException("Fail");
                    }
                    return "success";
                }
        );

        retry.execute().join();

        assertEquals(3, counter.get());
        assertEquals(1, retry.getSuccessCount());
        assertEquals(RetryBehavior.BackoffStrategy.FIXED, retry.getBackoffStrategy());
    }

    @Test
    @DisplayName("withExponentialBackoff factory should work correctly")
    void testWithExponentialBackoffFactory() {
        AtomicInteger counter = new AtomicInteger();

        RetryBehavior<Integer> retry = RetryBehavior.withExponentialBackoff(
                "test-retry",
                3,
                Duration.ofMillis(50),
                () -> {
                    counter.incrementAndGet();
                    return 99;
                }
        );

        retry.execute().join();

        assertEquals(1, counter.get());
        assertEquals(99, retry.getLastSuccessfulResult());
        assertEquals(RetryBehavior.BackoffStrategy.EXPONENTIAL, retry.getBackoffStrategy());
    }

    @Test
    @DisplayName("withJitter factory should work correctly")
    void testWithJitterFactory() {
        AtomicInteger counter = new AtomicInteger();

        RetryBehavior<String> retry = RetryBehavior.withJitter(
                "test-retry",
                2,
                Duration.ofMillis(50),
                () -> {
                    if (counter.incrementAndGet() < 2) {
                        throw new RuntimeException("Fail");
                    }
                    return "success";
                }
        );

        retry.execute().join();

        assertEquals(2, counter.get());
        assertEquals(RetryBehavior.BackoffStrategy.JITTER, retry.getBackoffStrategy());
    }

    // =========================================================================
    // METRICS TESTS
    // =========================================================================

    @Test
    @DisplayName("Should track metrics correctly")
    void testMetricsTracking() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                3,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(100)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        retry.execute().join();

        assertEquals(3, retry.getTotalAttempts());
        assertEquals(1, retry.getSuccessCount());
        assertEquals(0, retry.getFailureCount());
        assertTrue(retry.getTotalRetryDelayMs() >= 180, "Should have ~200ms delay");
        assertEquals(1.0, retry.getSuccessRate(), 0.01);
        assertNotNull(retry.getLastAttemptTime());
        assertNotNull(retry.getLastSuccessfulResult());
    }

    @Test
    @DisplayName("Should calculate average retry delay correctly")
    void testAverageRetryDelay() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(100)
        ) {
            @Override
            protected String attemptAction() throws Exception {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("Fail");
                }
                return "success";
            }
        };

        retry.execute().join();

        double avgDelay = retry.getAverageRetryDelayMs();
        // 3 attempts with 200ms total delay = ~66ms average per attempt
        assertTrue(avgDelay >= 60 && avgDelay <= 80,
                "Average delay should be ~66ms, got: " + avgDelay);
    }

    @Test
    @DisplayName("Should calculate success rate correctly")
    void testSuccessRate() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() {
                attemptCounter.incrementAndGet();
                return "success";
            }
        };

        // Execute multiple times
        retry.execute().join();
        retry.execute().join();
        retry.execute().join();

        assertEquals(3, retry.getSuccessCount());
        assertEquals(1.0, retry.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void testResetMetrics() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() {
                attemptCounter.incrementAndGet();
                return "success";
            }
        };

        retry.execute().join();

        // Verify metrics exist
        assertTrue(retry.getTotalAttempts() > 0);
        assertTrue(retry.getSuccessCount() > 0);

        // Reset
        retry.resetMetrics();

        // Verify reset
        assertEquals(0, retry.getTotalAttempts());
        assertEquals(0, retry.getSuccessCount());
        assertEquals(0, retry.getFailureCount());
        assertEquals(0, retry.getTotalRetryDelayMs());
        assertNull(retry.getLastException());
        assertNull(retry.getLastSuccessfulResult());
        assertNull(retry.getLastAttemptTime());
    }

    @Test
    @DisplayName("Should generate metrics summary")
    void testMetricsSummary() {
        RetryBehavior<String> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(50)
        ) {
            @Override
            protected String attemptAction() {
                attemptCounter.incrementAndGet();
                return "success";
            }
        };

        retry.execute().join();

        String summary = retry.getMetricsSummary();

        assertNotNull(summary);
        assertTrue(summary.contains("test-retry"));
        assertTrue(summary.contains("attempts="));
        assertTrue(summary.contains("success="));
        assertTrue(summary.contains("failures="));
        assertTrue(summary.contains("successRate="));
        assertTrue(summary.contains("avgDelay="));
    }

    // =========================================================================
    // VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should reject negative maxRetries")
    void testNegativeMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RetryBehavior<String>(
                    "test-retry",
                    -1,
                    RetryBehavior.BackoffStrategy.FIXED,
                    Duration.ofMillis(100)
            ) {
                @Override
                protected String attemptAction() {
                    return "test";
                }
            };
        });
    }

    @Test
    @DisplayName("Should reject null initialDelay")
    void testNullInitialDelay() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RetryBehavior<String>(
                    "test-retry",
                    3,
                    RetryBehavior.BackoffStrategy.FIXED,
                    null
            ) {
                @Override
                protected String attemptAction() {
                    return "test";
                }
            };
        });
    }

    @Test
    @DisplayName("Should reject negative initialDelay")
    void testNegativeInitialDelay() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RetryBehavior<String>(
                    "test-retry",
                    3,
                    RetryBehavior.BackoffStrategy.FIXED,
                    Duration.ofMillis(-100)
            ) {
                @Override
                protected String attemptAction() {
                    return "test";
                }
            };
        });
    }

    @Test
    @DisplayName("Should reject maxDelay less than initialDelay")
    void testMaxDelayLessThanInitialDelay() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RetryBehavior<String>(
                    "test-retry",
                    3,
                    RetryBehavior.BackoffStrategy.EXPONENTIAL,
                    Duration.ofMillis(100),
                    Duration.ofMillis(50),  // Less than initialDelay
                    null
            ) {
                @Override
                protected String attemptAction() {
                    return "test";
                }
            };
        });
    }

    // =========================================================================
    // CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Should be thread-safe for multiple executions")
    void testThreadSafety() throws Exception {
        RetryBehavior<Integer> retry = new RetryBehavior<>(
                "test-retry",
                2,
                RetryBehavior.BackoffStrategy.FIXED,
                Duration.ofMillis(10)
        ) {
            @Override
            protected Integer attemptAction() {
                return attemptCounter.incrementAndGet();
            }
        };

        // Execute concurrently
        CompletableFuture<Void> f1 = retry.execute();
        CompletableFuture<Void> f2 = retry.execute();
        CompletableFuture<Void> f3 = retry.execute();

        CompletableFuture.allOf(f1, f2, f3).join();

        // Should track all executions
        assertEquals(3, retry.getSuccessCount());
        assertTrue(retry.getTotalAttempts() >= 3);
    }
}
