package dev.agenor.runtime.ratelimit;

import dev.agenor.core.ratelimit.RateLimit;
import dev.agenor.core.ratelimit.RateLimiter;
import dev.agenor.core.ratelimit.RateLimiterStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding window algorithm implementation for rate limiting.
 * Tracks requests in a time window and enforces limit.
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    private final RateLimit rateLimit;
    private final ConcurrentLinkedQueue<Long> requestTimestamps = new ConcurrentLinkedQueue<>();

    // Statistics
    private final AtomicLong allowedRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);

    public SlidingWindowRateLimiter(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.period().toMillis();

        // Remove old timestamps outside window
        while (!requestTimestamps.isEmpty()) {
            Long oldest = requestTimestamps.peek();
            if (oldest != null && oldest < windowStart) {
                requestTimestamps.poll();
            } else {
                break;
            }
        }

        // Check if under limit
        if (requestTimestamps.size() < rateLimit.maxRequests()) {
            requestTimestamps.offer(now);
            allowedRequests.incrementAndGet();
            return true;
        }

        rejectedRequests.incrementAndGet();
        log.trace("Rate limit exceeded (sliding window), request rejected");
        return false;
    }

    @Override
    public CompletableFuture<Void> acquire() {
        return CompletableFuture.runAsync(() -> {
            while (!tryAcquire()) {
                try {
                    Thread.sleep(calculateWaitTime());
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
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline) {
                if (tryAcquire()) {
                    return true;
                }

                try {
                    long waitTime = Math.min(calculateWaitTime(),
                                           deadline - System.currentTimeMillis());
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            return false;
        });
    }

    @Override
    public int availablePermits() {
        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.period().toMillis();

        // Count requests in current window
        int requestsInWindow = (int) requestTimestamps.stream()
            .filter(ts -> ts >= windowStart)
            .count();

        return Math.max(0, rateLimit.maxRequests() - requestsInWindow);
    }

    @Override
    public void reset() {
        requestTimestamps.clear();
        allowedRequests.set(0);
        rejectedRequests.set(0);
        log.debug("Sliding window rate limiter reset");
    }

    @Override
    public RateLimiterStats getStats() {
        return RateLimiterStats.create(
            allowedRequests.get(),
            rejectedRequests.get(),
            availablePermits()
        );
    }

    private long calculateWaitTime() {
        if (requestTimestamps.isEmpty()) {
            return 0;
        }

        Long oldest = requestTimestamps.peek();
        if (oldest == null) {
            return 0;
        }

        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.period().toMillis();

        // Wait until oldest request falls outside window
        return Math.max(0, oldest - windowStart);
    }
}
