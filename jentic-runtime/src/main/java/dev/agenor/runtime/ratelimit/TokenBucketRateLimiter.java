package dev.agenor.runtime.ratelimit;

import dev.agenor.core.ratelimit.RateLimit;
import dev.agenor.core.ratelimit.RateLimiter;
import dev.agenor.core.ratelimit.RateLimiterStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token bucket algorithm implementation for rate limiting.
 * Allows burst traffic up to bucket capacity while maintaining average rate.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final RateLimit rateLimit;
    private final AtomicInteger tokens;
    private final AtomicLong lastRefillTime;
    private final Lock refillLock = new ReentrantLock();

    // Statistics
    private final AtomicLong allowedRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);

    public TokenBucketRateLimiter(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
        this.tokens = new AtomicInteger(rateLimit.burstCapacity());
        this.lastRefillTime = new AtomicLong(System.nanoTime());
    }

    @Override
    public boolean tryAcquire() {
        refillTokens();

        if (tokens.get() > 0) {
            if (tokens.decrementAndGet() >= 0) {
                allowedRequests.incrementAndGet();
                return true;
            } else {
                // Revert decrement if we went negative
                tokens.incrementAndGet();
            }
        }

        rejectedRequests.incrementAndGet();
        log.trace("Rate limit exceeded, request rejected");
        return false;
    }

    @Override
    public CompletableFuture<Void> acquire() {
        return CompletableFuture.runAsync(() -> {
            while (!tryAcquire()) {
                try {
                    // Wait for token refill
                    long waitTime = calculateWaitTime();
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for rate limit", e);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> acquire(Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.nanoTime() + timeout.toNanos();

            while (System.nanoTime() < deadline) {
                if (tryAcquire()) {
                    return true;
                }

                try {
                    long waitTime = Math.min(calculateWaitTime(),
                                           (deadline - System.nanoTime()) / 1_000_000);
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            return false; // Timeout
        });
    }

    @Override
    public int availablePermits() {
        refillTokens();
        return Math.max(0, tokens.get());
    }

    @Override
    public void reset() {
        tokens.set(rateLimit.burstCapacity());
        lastRefillTime.set(System.nanoTime());
        allowedRequests.set(0);
        rejectedRequests.set(0);
        log.debug("Rate limiter reset");
    }

    @Override
    public RateLimiterStats getStats() {
        return RateLimiterStats.create(
            allowedRequests.get(),
            rejectedRequests.get(),
            availablePermits()
        );
    }

    private void refillTokens() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTime.get();
        long elapsed = now - lastRefill;

        if (elapsed < TimeUnit.MILLISECONDS.toNanos(100)) {
            return; // Refill at most every 100ms
        }

        refillLock.lock();
        try {
            // Double-check after acquiring lock
            if (lastRefillTime.get() != lastRefill) {
                return; // Another thread already refilled
            }

            // Calculate tokens to add based on elapsed time
            long periodNanos = rateLimit.period().toNanos();
            long tokensToAdd = (elapsed * rateLimit.maxRequests()) / periodNanos;

            if (tokensToAdd > 0) {
                int currentTokens = tokens.get();
                int newTokens = Math.min(
                    currentTokens + (int) tokensToAdd,
                    rateLimit.burstCapacity()
                );

                tokens.set(newTokens);
                lastRefillTime.set(now);

                log.trace("Refilled {} tokens, total: {}", tokensToAdd, newTokens);
            }
        } finally {
            refillLock.unlock();
        }
    }

    private long calculateWaitTime() {
        // Calculate time until next token is available
        long periodMs = rateLimit.period().toMillis();
        long tokensPerPeriod = rateLimit.maxRequests();

        return periodMs / tokensPerPeriod;
    }
}
