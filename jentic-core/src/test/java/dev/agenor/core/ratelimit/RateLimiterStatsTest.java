package dev.agenor.core.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for RateLimiterStats record
 */
class RateLimiterStatsTest {

    @Test
    void shouldCreateStatsWithAllFields() {
        // Given
        long totalRequests = 100L;
        long allowedRequests = 80L;
        long rejectedRequests = 20L;
        double rejectionRate = 20.0;
        Instant lastReset = Instant.now();
        int currentPermits = 5;

        // When
        RateLimiterStats stats = new RateLimiterStats(
            totalRequests, allowedRequests, rejectedRequests,
            rejectionRate, lastReset, currentPermits
        );

        // Then
        assertThat(stats.totalRequests()).isEqualTo(totalRequests);
        assertThat(stats.allowedRequests()).isEqualTo(allowedRequests);
        assertThat(stats.rejectedRequests()).isEqualTo(rejectedRequests);
        assertThat(stats.rejectionRate()).isEqualTo(rejectionRate);
        assertThat(stats.lastReset()).isEqualTo(lastReset);
        assertThat(stats.currentPermits()).isEqualTo(currentPermits);
    }

    @Test
    void shouldCalculateStatsCorrectly() {
        // Given
        long allowed = 80L;
        long rejected = 20L;
        int permits = 10;

        // When
        RateLimiterStats stats = RateLimiterStats.create(allowed, rejected, permits);

        // Then
        assertThat(stats.totalRequests()).isEqualTo(100L);
        assertThat(stats.allowedRequests()).isEqualTo(80L);
        assertThat(stats.rejectedRequests()).isEqualTo(20L);
        assertThat(stats.rejectionRate()).isEqualTo(20.0);
        assertThat(stats.currentPermits()).isEqualTo(10);
        assertThat(stats.lastReset()).isNotNull();
        assertThat(stats.lastReset()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void shouldCalculateZeroRejectionRate() {
        // Given
        long allowed = 100L;
        long rejected = 0L;

        // When
        RateLimiterStats stats = RateLimiterStats.create(allowed, rejected, 5);

        // Then
        assertThat(stats.rejectionRate()).isEqualTo(0.0);
    }

    @Test
    void shouldCalculate100PercentRejectionRate() {
        // Given
        long allowed = 0L;
        long rejected = 100L;

        // When
        RateLimiterStats stats = RateLimiterStats.create(allowed, rejected, 0);

        // Then
        assertThat(stats.rejectionRate()).isEqualTo(100.0);
    }

    @Test
    void shouldHandleZeroTotalRequests() {
        // Given
        long allowed = 0L;
        long rejected = 0L;

        // When
        RateLimiterStats stats = RateLimiterStats.create(allowed, rejected, 10);

        // Then
        assertThat(stats.totalRequests()).isEqualTo(0L);
        assertThat(stats.rejectionRate()).isEqualTo(0.0);
    }

    @Test
    void shouldCalculatePartialRejectionRate() {
        // Given
        long allowed = 75L;
        long rejected = 25L;

        // When
        RateLimiterStats stats = RateLimiterStats.create(allowed, rejected, 5);

        // Then
        assertThat(stats.rejectionRate()).isEqualTo(25.0);
    }

    @Test
    void shouldRoundRejectionRateProperly() {
        // Given
        long allowed = 67L;
        long rejected = 33L;

        // When
        RateLimiterStats stats = RateLimiterStats.create(allowed, rejected, 5);

        // Then
        assertThat(stats.rejectionRate()).isCloseTo(33.0, within(0.1));
    }
}
