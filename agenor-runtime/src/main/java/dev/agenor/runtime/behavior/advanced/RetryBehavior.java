package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.behavior.BaseBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * RetryBehavior - Advanced behavior that automatically retries failed operations
 * with configurable backoff strategies.
 *
 * <p>Features:
 * <ul>
 *   <li>Multiple backoff strategies (FIXED, LINEAR, EXPONENTIAL, JITTER)</li>
 *   <li>Configurable max retry attempts</li>
 *   <li>Exception filtering (retry only on specific exceptions)</li>
 *   <li>Success/failure callbacks</li>
 *   <li>Comprehensive metrics tracking</li>
 *   <li>Per-attempt timeout support with proper interruption</li>
 *   <li>Thread-safe retry state management</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe and can be used in concurrent environments.
 *
 * <p>Example Usage:
 * <pre>{@code
 * RetryBehavior<String> apiRetry = new RetryBehavior<>(
 *     "api-retry",
 *     3,  // Max 3 retries
 *     BackoffStrategy.EXPONENTIAL,
 *     Duration.ofSeconds(1)
 * ) {
 *     @Override
 *     protected String attemptAction() throws Exception {
 *         return externalApi.call();
 *     }
 *
 *     @Override
 *     protected boolean shouldRetry(Exception e) {
 *         return e instanceof NetworkException;
 *     }
 * };
 *
 * apiRetry.onSuccess(result -> log.info("Success: {}", result));
 * apiRetry.onFailure(e -> log.error("All retries failed", e));
 * }</pre>
 *
 * @param <T> the type of result produced by the retried action
 *
 * @since 0.2.0
 */
public abstract class RetryBehavior<T> extends BaseBehavior {

    private static final Logger log = LoggerFactory.getLogger(RetryBehavior.class);

    /**
     * Backoff strategies for retry delays
     */
    public enum BackoffStrategy {
        /**
         * Fixed delay between retries (e.g., 1s, 1s, 1s)
         */
        FIXED,

        /**
         * Linearly increasing delay (e.g., 1s, 2s, 3s)
         */
        LINEAR,

        /**
         * Exponentially increasing delay (e.g., 1s, 2s, 4s, 8s)
         */
        EXPONENTIAL,

        /**
         * Exponential with random jitter to avoid thundering herd
         * (e.g., 1s±25%, 2s±25%, 4s±25%)
         */
        JITTER
    }

    /**
     * Custom exception for attempt timeouts
     */
    public static class AttemptTimeoutException extends Exception {
        public AttemptTimeoutException(String message) {
            super(message);
        }

        public AttemptTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // Configuration
    private final int maxRetries;
    private final BackoffStrategy backoffStrategy;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final Duration attemptTimeout;

    // Executor for timeout handling
    private final ExecutorService timeoutExecutor;

    // State
    private final AtomicInteger currentAttempt = new AtomicInteger(0);
    private final AtomicInteger totalAttempts = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong totalRetryDelayMs = new AtomicLong(0);

    private volatile Instant lastAttemptTime;
    private volatile Exception lastException;
    private volatile T lastSuccessfulResult;

    // Callbacks
    private Consumer<T> onSuccessCallback;
    private Consumer<Exception> onFailureCallback;
    private Consumer<Integer> onRetryCallback;
    private Predicate<Exception> retryCondition;

    /**
     * Create a RetryBehavior with default configuration
     *
     * @param behaviorId unique identifier for this behavior
     * @param maxRetries maximum number of retry attempts (0 means no retries)
     * @param backoffStrategy strategy for calculating retry delays
     * @param initialDelay initial delay before first retry
     */
    protected RetryBehavior(String behaviorId,
                            int maxRetries,
                            BackoffStrategy backoffStrategy,
                            Duration initialDelay) {
        this(behaviorId, maxRetries, backoffStrategy, initialDelay, Duration.ofMinutes(5), null);
    }

    /**
     * Create a RetryBehavior with full configuration
     *
     * @param behaviorId unique identifier for this behavior
     * @param maxRetries maximum number of retry attempts
     * @param backoffStrategy strategy for calculating retry delays
     * @param initialDelay initial delay before first retry
     * @param maxDelay maximum delay between retries (caps exponential growth)
     * @param attemptTimeout timeout for each attempt (null for no timeout)
     */
    protected RetryBehavior(String behaviorId,
                            int maxRetries,
                            BackoffStrategy backoffStrategy,
                            Duration initialDelay,
                            Duration maxDelay,
                            Duration attemptTimeout) {
        super(behaviorId, BehaviorType.RETRY, null);

        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maxDelay != null && maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= initialDelay");
        }

        this.maxRetries = maxRetries;
        this.backoffStrategy = backoffStrategy;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay != null ? maxDelay : Duration.ofMinutes(5);
        this.attemptTimeout = attemptTimeout;

        // Create executor for timeout handling - use virtual threads for efficiency
        this.timeoutExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("retry-timeout-", 0).factory()
        );

        // Default: retry on all exceptions
        this.retryCondition = e -> true;

        log.debug("Created RetryBehavior '{}' with maxRetries={}, strategy={}, initialDelay={}, maxDelay={}, attemptTimeout={}",
                behaviorId, maxRetries, backoffStrategy, initialDelay, this.maxDelay, attemptTimeout);
    }

    @Override
    protected void action() {
        // RetryBehavior manages its own execution flow
        // This is called by execute() in the parent class
        try {
            T result = executeWithRetries();

            // Success callback
            if (onSuccessCallback != null && result != null) {
                try {
                    onSuccessCallback.accept(result);
                } catch (Exception e) {
                    log.warn("Error in success callback for behavior '{}': {}",
                            getBehaviorId(), e.getMessage());
                }
            }

            lastSuccessfulResult = result;
            successCount.incrementAndGet();

            log.debug("RetryBehavior '{}' completed successfully after {} attempts",
                    getBehaviorId(), totalAttempts.get());

        } catch (Exception e) {
            // Failure callback
            if (onFailureCallback != null) {
                try {
                    onFailureCallback.accept(e);
                } catch (Exception callbackError) {
                    log.warn("Error in failure callback for behavior '{}': {}",
                            getBehaviorId(), callbackError.getMessage());
                }
            }

            lastException = e;
            failureCount.incrementAndGet();

            log.error("RetryBehavior '{}' failed after {} attempts: {}",
                    getBehaviorId(), totalAttempts.get(), e.getMessage());
        } finally {
            // Reset for next execution
            currentAttempt.set(0);
        }
    }

    /**
     * Execute the action with retry logic
     *
     * @return the result of the successful action
     * @throws Exception if all retry attempts fail
     */
    private T executeWithRetries() throws Exception {
        currentAttempt.set(0);
        Exception lastError = null;

        while (currentAttempt.get() <= maxRetries && isActive()) {
            int attemptNum = currentAttempt.get();
            totalAttempts.incrementAndGet();
            lastAttemptTime = Instant.now();

            try {
                log.debug("RetryBehavior '{}' attempt {}/{}",
                        getBehaviorId(), attemptNum + 1, maxRetries + 1);

                // Execute the action with optional timeout
                T result;
                if (attemptTimeout != null) {
                    result = executeWithTimeout();
                } else {
                    result = attemptAction();
                }

                // Success!
                if (attemptNum > 0) {
                    log.info("RetryBehavior '{}' succeeded on attempt {} after {} retries",
                            getBehaviorId(), attemptNum + 1, attemptNum);
                }

                return result;

            } catch (Exception e) {
                lastError = e;

                // Log the exception type for debugging
                log.debug("RetryBehavior '{}' caught exception: {} - {}",
                        getBehaviorId(), e.getClass().getSimpleName(), e.getMessage());

                // Check if we should retry this exception
                if (!shouldRetry(e) || !retryCondition.test(e)) {
                    log.warn("RetryBehavior '{}' will not retry exception: {}",
                            getBehaviorId(), e.getClass().getSimpleName());
                    throw e;
                }

                // Check if we have more retries left
                if (currentAttempt.get() >= maxRetries) {
                    log.warn("RetryBehavior '{}' exhausted all {} retries",
                            getBehaviorId(), maxRetries);
                    throw e;
                }

                // Calculate and apply backoff delay
                long delayMs = calculateBackoffDelay(attemptNum);
                totalRetryDelayMs.addAndGet(delayMs);

                log.debug("RetryBehavior '{}' will retry in {}ms (attempt {}/{})",
                        getBehaviorId(), delayMs, attemptNum + 2, maxRetries + 1);

                // Retry callback
                if (onRetryCallback != null) {
                    try {
                        onRetryCallback.accept(attemptNum + 1);
                    } catch (Exception callbackError) {
                        log.warn("Error in retry callback: {}", callbackError.getMessage());
                    }
                }

                // Wait before next retry
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }

                currentAttempt.incrementAndGet();
            }
        }

        // If we get here, all retries failed
        if (lastError != null) {
            throw lastError;
        } else {
            throw new RuntimeException("Retry behavior stopped before completion");
        }
    }

    /**
     * Execute action with timeout using proper thread interruption
     */
    private T executeWithTimeout() throws Exception {
        Future<T> future = timeoutExecutor.submit(() -> {
            try {
                return attemptAction();
            } catch (Exception e) {
                // Preserve exception type
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e);
            }
        });

        try {
            return future.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // Unwrap the cause
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException && cause.getCause() instanceof Exception) {
                // Unwrap RuntimeException wrapper
                throw (Exception) cause.getCause();
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            // Cancel and interrupt the task
            future.cancel(true);

            log.debug("RetryBehavior '{}' attempt timed out after {}",
                    getBehaviorId(), attemptTimeout);

            // Throw a specific exception that can be retried
            throw new AttemptTimeoutException("Attempt timed out after " + attemptTimeout);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", e);
        }
    }

    /**
     * Calculate backoff delay based on strategy
     */
    private long calculateBackoffDelay(int attemptNumber) {
        long baseDelayMs = initialDelay.toMillis();
        long delayMs;

        switch (backoffStrategy) {
            case FIXED:
                delayMs = baseDelayMs;
                break;

            case LINEAR:
                delayMs = baseDelayMs * (attemptNumber + 1);
                break;

            case EXPONENTIAL:
                delayMs = (long) (baseDelayMs * Math.pow(2, attemptNumber));
                break;

            case JITTER:
                long exponentialDelay = (long) (baseDelayMs * Math.pow(2, attemptNumber));
                // Add random jitter: ±25%
                double jitterFactor = 0.75 + (ThreadLocalRandom.current().nextDouble() * 0.5);
                delayMs = (long) (exponentialDelay * jitterFactor);
                break;

            default:
                delayMs = baseDelayMs;
        }

        // Cap at max delay
        return Math.min(delayMs, maxDelay.toMillis());
    }

    @Override
    public void stop() {
        super.stop();
        // Shutdown the executor
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // ABSTRACT METHODS
    // =========================================================================

    /**
     * Attempt to execute the action. This method will be called multiple times
     * if the action fails and retries are configured.
     *
     * @return the result of the action
     * @throws Exception if the action fails
     */
    protected abstract T attemptAction() throws Exception;

    /**
     * Determine if an exception should trigger a retry.
     * Default implementation returns true for all exceptions (including timeouts).
     * Override to filter specific exception types.
     *
     * @param exception the exception that occurred
     * @return true if the action should be retried, false otherwise
     */
    protected boolean shouldRetry(Exception exception) {
        // By default, retry all exceptions including timeouts
        return true;
    }

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    /**
     * Set custom retry condition predicate
     *
     * @param condition predicate that returns true if exception should trigger retry
     * @return this RetryBehavior for method chaining
     */
    public RetryBehavior<T> withRetryCondition(Predicate<Exception> condition) {
        this.retryCondition = condition;
        return this;
    }

    /**
     * Set success callback to be invoked when action succeeds
     *
     * @param callback consumer to accept the successful result
     * @return this RetryBehavior for method chaining
     */
    public RetryBehavior<T> onSuccess(Consumer<T> callback) {
        this.onSuccessCallback = callback;
        return this;
    }

    /**
     * Set failure callback to be invoked when all retries fail
     *
     * @param callback consumer to accept the final exception
     * @return this RetryBehavior for method chaining
     */
    public RetryBehavior<T> onFailure(Consumer<Exception> callback) {
        this.onFailureCallback = callback;
        return this;
    }

    /**
     * Set retry callback to be invoked before each retry attempt
     *
     * @param callback consumer to accept the upcoming retry number
     * @return this RetryBehavior for method chaining
     */
    public RetryBehavior<T> onRetry(Consumer<Integer> callback) {
        this.onRetryCallback = callback;
        return this;
    }

    // =========================================================================
    // FACTORY METHODS
    // =========================================================================

    /**
     * Create a RetryBehavior with fixed delay strategy
     *
     * @param behaviorId unique identifier
     * @param maxRetries maximum retry attempts
     * @param delay fixed delay between retries
     * @param action the action to retry
     * @param <R> result type
     * @return configured RetryBehavior
     */
    public static <R> RetryBehavior<R> withFixedDelay(
            String behaviorId,
            int maxRetries,
            Duration delay,
            CheckedSupplier<R> action) {

        return new RetryBehavior<R>(behaviorId, maxRetries, BackoffStrategy.FIXED, delay) {
            @Override
            protected R attemptAction() throws Exception {
                return action.get();
            }
        };
    }

    /**
     * Create a RetryBehavior with exponential backoff strategy
     *
     * @param behaviorId unique identifier
     * @param maxRetries maximum retry attempts
     * @param initialDelay initial delay (will be doubled each retry)
     * @param action the action to retry
     * @param <R> result type
     * @return configured RetryBehavior
     */
    public static <R> RetryBehavior<R> withExponentialBackoff(
            String behaviorId,
            int maxRetries,
            Duration initialDelay,
            CheckedSupplier<R> action) {

        return new RetryBehavior<R>(behaviorId, maxRetries, BackoffStrategy.EXPONENTIAL, initialDelay) {
            @Override
            protected R attemptAction() throws Exception {
                return action.get();
            }
        };
    }

    /**
     * Create a RetryBehavior with jittered exponential backoff
     *
     * @param behaviorId unique identifier
     * @param maxRetries maximum retry attempts
     * @param initialDelay initial delay
     * @param action the action to retry
     * @param <R> result type
     * @return configured RetryBehavior
     */
    public static <R> RetryBehavior<R> withJitter(
            String behaviorId,
            int maxRetries,
            Duration initialDelay,
            CheckedSupplier<R> action) {

        return new RetryBehavior<R>(behaviorId, maxRetries, BackoffStrategy.JITTER, initialDelay) {
            @Override
            protected R attemptAction() throws Exception {
                return action.get();
            }
        };
    }

    // =========================================================================
    // METRICS AND STATE
    // =========================================================================

    /**
     * Get the maximum number of retry attempts configured
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Get the current attempt number (0-based)
     */
    public int getCurrentAttempt() {
        return currentAttempt.get();
    }

    /**
     * Get the total number of attempts made (including first attempt)
     */
    public int getTotalAttempts() {
        return totalAttempts.get();
    }

    /**
     * Get the number of successful executions
     */
    public int getSuccessCount() {
        return successCount.get();
    }

    /**
     * Get the number of failed executions (after all retries)
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Get the total time spent waiting for retries (in milliseconds)
     */
    public long getTotalRetryDelayMs() {
        return totalRetryDelayMs.get();
    }

    /**
     * Get the average retry delay per execution (in milliseconds)
     */
    public double getAverageRetryDelayMs() {
        int total = totalAttempts.get();
        return total > 0 ? (double) totalRetryDelayMs.get() / total : 0.0;
    }

    /**
     * Get the success rate (0.0 to 1.0)
     */
    public double getSuccessRate() {
        int total = successCount.get() + failureCount.get();
        return total > 0 ? (double) successCount.get() / total : 0.0;
    }

    /**
     * Get the backoff strategy being used
     */
    public BackoffStrategy getBackoffStrategy() {
        return backoffStrategy;
    }

    /**
     * Get the initial delay configuration
     */
    public Duration getInitialDelay() {
        return initialDelay;
    }

    /**
     * Get the maximum delay configuration
     */
    public Duration getMaxDelay() {
        return maxDelay;
    }

    /**
     * Get the attempt timeout configuration
     */
    public Duration getAttemptTimeout() {
        return attemptTimeout;
    }

    /**
     * Get the last exception that occurred
     */
    public Exception getLastException() {
        return lastException;
    }

    /**
     * Get the last successful result
     */
    public T getLastSuccessfulResult() {
        return lastSuccessfulResult;
    }

    /**
     * Get the time of the last attempt
     */
    public Instant getLastAttemptTime() {
        return lastAttemptTime;
    }

    /**
     * Reset all metrics and state
     */
    public void resetMetrics() {
        currentAttempt.set(0);
        totalAttempts.set(0);
        successCount.set(0);
        failureCount.set(0);
        totalRetryDelayMs.set(0);
        lastException = null;
        lastSuccessfulResult = null;
        lastAttemptTime = null;

        log.debug("Reset metrics for RetryBehavior '{}'", getBehaviorId());
    }

    /**
     * Get comprehensive metrics as a formatted string
     */
    public String getMetricsSummary() {
        return String.format(
                "RetryBehavior[%s] Metrics: attempts=%d, success=%d, failures=%d, successRate=%.1f%%, avgDelay=%.1fms",
                getBehaviorId(),
                totalAttempts.get(),
                successCount.get(),
                failureCount.get(),
                getSuccessRate() * 100,
                getAverageRetryDelayMs()
        );
    }

    // =========================================================================
    // FUNCTIONAL INTERFACE
    // =========================================================================

    /**
     * Functional interface for actions that can throw checked exceptions
     *
     * @param <R> the result type
     */
    @FunctionalInterface
    public interface CheckedSupplier<R> {
        R get() throws Exception;
    }
}
