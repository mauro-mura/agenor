package dev.agenor.core.ratelimit;

import java.time.Duration;

/**
 * Configuration for rate limiting
 *
 * @param maxRequests   maximum number of requests allowed within {@code period}
 * @param period        time window over which {@code maxRequests} applies
 * @param burstCapacity maximum burst size above the steady-state limit;
 *                      defaults to {@code maxRequests} when using factory methods
 * @since 0.2.0
 */
public record RateLimit(
    int maxRequests,
    Duration period,
    int burstCapacity
) {

    /**
     * Create rate limit from string format: "10/s", "100/m", "1000/h"
     */
    public static RateLimit parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("Rate limit specification cannot be empty");
        }

        spec = spec.trim().toLowerCase();

        // Parse format: "number/unit" e.g., "10/s", "100/min"
        int slashIndex = spec.indexOf('/');
        if (slashIndex < 0) {
            throw new IllegalArgumentException("Invalid rate limit format. Expected: 'number/unit' (e.g., '10/s')");
        }

        String numberPart = spec.substring(0, slashIndex).trim();
        String unitPart = spec.substring(slashIndex + 1).trim();

        int maxRequests;
        try {
            maxRequests = Integer.parseInt(numberPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number in rate limit: " + numberPart);
        }

        Duration period = parsePeriod(unitPart);
        int burstCapacity = maxRequests; // Default burst = max requests

        return new RateLimit(maxRequests, period, burstCapacity);
    }

    private static Duration parsePeriod(String unit) {
        return switch (unit) {
            case "s", "sec", "second" -> Duration.ofSeconds(1);
            case "m", "min", "minute" -> Duration.ofMinutes(1);
            case "h", "hour" -> Duration.ofHours(1);
            case "d", "day" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    /**
     * Common rate limits
     */
    public static RateLimit perSecond(int requests) {
        return new RateLimit(requests, Duration.ofSeconds(1), requests);
    }

    public static RateLimit perMinute(int requests) {
        return new RateLimit(requests, Duration.ofMinutes(1), requests);
    }

    public static RateLimit perHour(int requests) {
        return new RateLimit(requests, Duration.ofHours(1), requests);
    }

    /**
     * With custom burst capacity
     */
    public RateLimit withBurst(int burstCapacity) {
        return new RateLimit(maxRequests, period, burstCapacity);
    }
}
