package dev.agenor.core.ratelimit;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for rate limiting strategy.
 * Controls the rate at which operations can be executed.
 */
public interface RateLimiter {

    /**
     * Attempt to acquire permission to execute.
     * Returns immediately with true/false.
     *
     * @return true if permission granted, false if rate limit exceeded
     */
    boolean tryAcquire();

    /**
     * Acquire permission to execute, waiting if necessary.
     *
     * @return CompletableFuture that completes when permission is granted
     */
    CompletableFuture<Void> acquire();

    /**
     * Acquire permission with timeout.
     *
     * @param timeout maximum time to wait
     * @return CompletableFuture that completes when permission granted or timeout
     */
    CompletableFuture<Boolean> acquire(Duration timeout);

    /**
     * Get current available permits
     */
    int availablePermits();

    /**
     * Reset rate limiter state
     */
    void reset();

    /**
     * Get rate limiter statistics
     */
    RateLimiterStats getStats();
}
