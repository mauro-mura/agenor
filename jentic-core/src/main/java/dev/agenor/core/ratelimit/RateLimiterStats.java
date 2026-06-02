package dev.agenor.core.ratelimit;

import java.time.Instant;

/**
 * Runtime statistics for a rate limiter instance.
 *
 * @param totalRequests     total number of requests evaluated (allowed + rejected)
 * @param allowedRequests   number of requests that passed the rate limit
 * @param rejectedRequests  number of requests that were throttled
 * @param rejectionRate     percentage of requests rejected (0.0–100.0)
 * @param lastReset         instant of the most recent counter reset
 * @param currentPermits    available permits remaining in the current window
 * @since 0.2.0
 */
public record RateLimiterStats(
    long totalRequests,
    long allowedRequests,
    long rejectedRequests,
    double rejectionRate,
    Instant lastReset,
    int currentPermits
) {
    public static RateLimiterStats create(long allowed, long rejected, int permits) {
        long total = allowed + rejected;
        double rate = total > 0 ? (double) rejected / total * 100 : 0.0;
        return new RateLimiterStats(total, allowed, rejected, rate, Instant.now(), permits);
    }
}
