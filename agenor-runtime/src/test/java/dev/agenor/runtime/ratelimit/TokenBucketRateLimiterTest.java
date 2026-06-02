package dev.agenor.runtime.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.agenor.core.ratelimit.RateLimit;
import dev.agenor.core.ratelimit.RateLimiterStats;

/**
 * Comprehensive tests for TokenBucketRateLimiter
 */
class TokenBucketRateLimiterTest {

    @Test
    void shouldAllowRequestsUpToLimit() {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new TokenBucketRateLimiter(limit);

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
    void shouldRefillTokensOverTime() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(2);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // Exhaust tokens
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();
        assertThat(rateLimiter.tryAcquire()).isFalse();

        // Wait for refill
        Thread.sleep(600); // Should refill 1 token

        // Should allow one more request
        assertThat(rateLimiter.tryAcquire()).isTrue();
    }

    @Test
    void shouldRespectBurstCapacity() {
        // Given
        RateLimit limit = RateLimit.perSecond(10).withBurst(15);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // When - consume all burst tokens
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (rateLimiter.tryAcquire()) {
                allowed++;
            }
        }

        // Then - should allow exactly burst capacity
        assertThat(allowed).isEqualTo(15);
    }

    @Test
    void shouldBlockingAcquireWaitForToken() throws Exception {
        // Given
        RateLimit limit = RateLimit.perSecond(1);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // Exhaust token
        rateLimiter.tryAcquire();

        // When
        long start = System.currentTimeMillis();
        CompletableFuture<Void> future = rateLimiter.acquire();
        future.get(2, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // Then - should have waited for refill
        assertThat(elapsed).isGreaterThanOrEqualTo(500);
    }

    @Test
    void shouldAcquireWithTimeoutReturnTrue() throws Exception {
        // Given
        RateLimit limit = RateLimit.perSecond(2);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // Exhaust tokens
        rateLimiter.tryAcquire();
        rateLimiter.tryAcquire();

        // When
        CompletableFuture<Boolean> future = rateLimiter.acquire(Duration.ofSeconds(2));
        Boolean result = future.get(3, TimeUnit.SECONDS);

        // Then - should eventually succeed
        assertThat(result).isTrue();
    }

    @Test
    void shouldAcquireWithTimeoutReturnFalse() throws Exception {
        // Given
        RateLimit limit = RateLimit.perMinute(1);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // Exhaust token
        rateLimiter.tryAcquire();

        // When
        CompletableFuture<Boolean> future = rateLimiter.acquire(Duration.ofMillis(100));
        Boolean result = future.get(1, TimeUnit.SECONDS);

        // Then - should timeout
        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnCorrectAvailablePermits() {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // When/Then
        assertThat(rateLimiter.availablePermits()).isEqualTo(5);

        rateLimiter.tryAcquire();
        assertThat(rateLimiter.availablePermits()).isEqualTo(4);

        rateLimiter.tryAcquire();
        assertThat(rateLimiter.availablePermits()).isEqualTo(3);
    }

    @Test
    void shouldResetState() {
        // Given
        RateLimit limit = RateLimit.perSecond(5);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // Consume tokens
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
        var rateLimiter = new TokenBucketRateLimiter(limit);

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
    void shouldNotRefillBeyondBurstCapacity() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(10).withBurst(5);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // Wait for potential refill
        Thread.sleep(2000);

        // When/Then - should cap at burst capacity
        assertThat(rateLimiter.availablePermits()).isLessThanOrEqualTo(5);
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perSecond(100);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // When - simulate concurrent requests
        int threads = 10;
        int requestsPerThread = 15;
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

        // Then
        RateLimiterStats stats = rateLimiter.getStats();
        assertThat(stats.totalRequests()).isEqualTo(150);
        assertThat(stats.allowedRequests()).isEqualTo(100);
        assertThat(stats.rejectedRequests()).isEqualTo(50);
    }

    @Test
    void shouldRateLimitPerMinute() throws InterruptedException {
        // Given
        RateLimit limit = RateLimit.perMinute(60);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // When - consume all tokens
        for (int i = 0; i < 60; i++) {
            assertThat(rateLimiter.tryAcquire()).isTrue();
        }

        // Then
        assertThat(rateLimiter.tryAcquire()).isFalse();
        Thread.sleep(1100); // wait for at least 1 token
        assertThat(rateLimiter.tryAcquire()).isTrue();
    }

    @Test
    void shouldRateLimitPerHour() {
        // Given
        RateLimit limit = RateLimit.perHour(100);
        var rateLimiter = new TokenBucketRateLimiter(limit);

        // When
        for (int i = 0; i < 100; i++) {
            assertThat(rateLimiter.tryAcquire()).isTrue();
        }

        // Then
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }
}
