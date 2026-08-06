package dev.agenor.runtime.ratelimit;

import dev.agenor.core.ratelimit.RateLimit;
import dev.agenor.core.ratelimit.RateLimiterStats;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for SlidingWindowRateLimiter
 */
class SlidingWindowRateLimiterTest {

    @Test
    void shouldAllowRequestsWithinWindow() {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When/Then - should allow 5 requests
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();

        // 6th request should be rejected
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    void shouldRemoveOldTimestampsFromWindow() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(2);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When - exhaust window
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();
        assertThat(rateLimiter.tryAcquire()).isFalse();

        // Wait for window to slide
        Thread.sleep(1100);

        // Then - should allow new requests
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
    }

    @Test
    void shouldCalculateAvailablePermitsCorrectly() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When/Then
        assertThat(rateLimiter.availablePermits()).isEqualTo(5);

        rateLimiter.tryAcquire();
        assertThat(rateLimiter.availablePermits()).isEqualTo(4);

        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();
        assertThat(rateLimiter.availablePermits()).isEqualTo(2);

        // Wait for first request to fall outside window
        Thread.sleep(1100);
        assertThat(rateLimiter.availablePermits()).isGreaterThan(2);
    }

    @Test
    void shouldBlockingAcquireWaitForWindow() throws Exception {
        // Given
        RateLimit limit = RateLimit.perSecond(1);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // Exhaust window
        rateLimiter.tryAcquire();

        // When
        long start = System.currentTimeMillis();
        CompletableFuture<Void> future = rateLimiter.acquire();
        future.get(2, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // Then - should have waited for window to slide
        assertThat(elapsed).isGreaterThanOrEqualTo(900);
    }

    @Test
    void shouldAcquireWithTimeoutSucceed() throws Exception {
        // Given
        RateLimit limit = RateLimit.perSecond(2);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // Exhaust window
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();

        // When
        CompletableFuture<Boolean> future = rateLimiter.acquire(Duration.ofSeconds(2));
        Boolean result = future.get(3, TimeUnit.SECONDS);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void shouldAcquireWithTimeoutFail() throws Exception {
        // Given
        RateLimit limit = RateLimit.perMinute(1);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // Exhaust window
        rateLimiter.tryAcquire();

        // When
        CompletableFuture<Boolean> future = rateLimiter.acquire(Duration.ofMillis(100));
        Boolean result = future.get(1, TimeUnit.SECONDS);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void shouldResetClearAllState() {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // Consume some requests
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();

        // When
        rateLimiter.reset();

        // Then
        assertThat(rateLimiter.availablePermits()).isEqualTo(5);
        RateLimiterStats stats = rateLimiter.getStats();
        assertThat(stats.allowedRequests()).isEqualTo(0);
        assertThat(stats.rejectedRequests()).isEqualTo(0);
    }

    @Test
    void shouldTrackStatistics() {
        // Given
        RateLimit limit = RateLimit.perSecond(3);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When
        rateLimiter.tryAcquire(); // allowed
        rateLimiter.tryAcquire(); // allowed
        rateLimiter.tryAcquire(); // allowed
        rateLimiter.tryAcquire(); // rejected
        rateLimiter.tryAcquire(); // rejected

        // Then
        RateLimiterStats stats = rateLimiter.getStats();
        assertThat(stats.totalRequests()).isEqualTo(5);
        assertThat(stats.allowedRequests()).isEqualTo(3);
        assertThat(stats.rejectedRequests()).isEqualTo(2);
        assertThat(stats.rejectionRate()).isEqualTo(40.0);
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(50);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When - simulate concurrent requests
        int threads = 10;
        int requestsPerThread = 10;
        Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    rateLimiter.tryAcquire();
                }
            });
            workers[i].start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        // Then - total should be 100, allowed should be around 50
        RateLimiterStats stats = rateLimiter.getStats();
        assertThat(stats.totalRequests()).isEqualTo(100);
        assertThat(stats.allowedRequests()).isLessThanOrEqualTo(50);
        assertThat(stats.allowedRequests()).isGreaterThanOrEqualTo(48); // allow small race condition
        assertThat(stats.rejectedRequests()).isGreaterThanOrEqualTo(48);
    }

    @Test
    void shouldSlidingWindowAllowBurstAfterWindow() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(3);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When - first burst
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isFalse();

        // Wait for window to fully slide
        Thread.sleep(1100);

        // Then - second burst should work
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    void shouldPartialWindowSlideAllowPartialRequests() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When - consume 3 requests
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();

        // Wait for half window
        Thread.sleep(600);

        // Then - first 3 should still be in window, can only do 2 more
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    void shouldRateLimitPerMinute() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perMinute(3);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire()).isTrue();
        }

        // Then
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    void shouldRateLimitPerHour() {
        // Given
        RateLimit limit = RateLimit.perHour(100);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When
        for (int i = 0; i < 100; i++) {
            assertThat(rateLimiter.tryAcquire()).isTrue();
        }

        // Then
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    void shouldHandleEmptyQueueGracefully() {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new SlidingWindowRateLimiter(limit);

        // When - reset on empty queue
        rateLimiter.reset();

        // Then
        assertThat(rateLimiter.availablePermits()).isEqualTo(5);
        assertThat(rateLimiter.tryAcquire()).isTrue();
    }
}
