package dev.agenor.examples.support.production;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter for protecting against abuse.
 * Implements sliding window and token bucket algorithms.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final int maxRequestsPerWindow;
    private final Duration windowSize;
    private final int burstLimit;
    private final Duration burstWindow;

    // Per-client tracking
    private final Map<String, ClientBucket> clientBuckets = new ConcurrentHashMap<>();

    // Global tracking
    private final AtomicInteger globalRequestCount = new AtomicInteger(0);
    private final int globalLimit;

    // Cleanup scheduler
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * Creates a rate limiter with default settings.
     * Default: 60 requests per minute, burst of 10 per second, global limit 1000/min.
     */
    public RateLimiter() {
        this(60, Duration.ofMinutes(1), 10, Duration.ofSeconds(1), 1000);
    }

    /**
     * Creates a rate limiter with custom settings.
     */
    public RateLimiter(int maxRequestsPerWindow, Duration windowSize,
                       int burstLimit, Duration burstWindow, int globalLimit) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowSize = windowSize;
        this.burstLimit = burstLimit;
        this.burstWindow = burstWindow;
        this.globalLimit = globalLimit;

        // Schedule cleanup every minute
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupScheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);

        log.info("Rate limiter initialized: {} req/{}, burst {}/{}s, global {}/min",
            maxRequestsPerWindow, formatDuration(windowSize),
            burstLimit, burstWindow.toSeconds(), globalLimit);
    }

    /**
     * Checks if a request is allowed for the given client.
     *
     * @param clientId unique client identifier (e.g., session ID, IP)
     * @return result indicating if allowed and details
     */
    public RateLimitResult checkLimit(String clientId) {
        Instant now = Instant.now();

        // Check global limit
        if (globalRequestCount.get() >= globalLimit) {
            log.warn("Global rate limit exceeded");
            return RateLimitResult.denied("Global rate limit exceeded",
                Duration.ofMinutes(1), globalLimit, globalLimit);
        }

        // Get or create client bucket
        ClientBucket bucket = clientBuckets.computeIfAbsent(clientId,
            k -> new ClientBucket(maxRequestsPerWindow, windowSize, burstLimit, burstWindow));

        // Check client limits
        RateLimitResult result = bucket.tryAcquire(now);

        if (result.isAllowed()) {
            globalRequestCount.incrementAndGet();
        } else {
            log.debug("Rate limit exceeded for client {}: {}", clientId, result.reason());
        }

        return result;
    }

    /**
     * Checks and consumes a request allowance.
     * Convenience method that throws exception if denied.
     */
    public void acquire(String clientId) throws RateLimitExceededException {
        RateLimitResult result = checkLimit(clientId);
        if (!result.isAllowed()) {
            throw new RateLimitExceededException(result);
        }
    }

    /**
     * Gets the current status for a client.
     */
    public ClientStatus getStatus(String clientId) {
        ClientBucket bucket = clientBuckets.get(clientId);
        if (bucket == null) {
            return new ClientStatus(clientId, 0, maxRequestsPerWindow, 0, burstLimit, false);
        }
        return bucket.getStatus(clientId);
    }

    /**
     * Resets the limit for a specific client.
     */
    public void resetClient(String clientId) {
        clientBuckets.remove(clientId);
        log.debug("Reset rate limit for client {}", clientId);
    }

    /**
     * Shuts down the rate limiter.
     */
    public void shutdown() {
        cleanupScheduler.shutdownNow();
        log.info("Rate limiter shut down");
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(windowSize.multipliedBy(2));

        clientBuckets.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().getLastAccess().isBefore(cutoff);
            if (expired) {
                log.trace("Removed expired bucket for client {}", entry.getKey());
            }
            return expired;
        });

        // Reset global counter periodically
        globalRequestCount.set(0);
    }

    private String formatDuration(Duration duration) {
        if (duration.toMinutes() >= 1) {
            return duration.toMinutes() + "m";
        }
        return duration.toSeconds() + "s";
    }

    // ========== DATA CLASSES ==========

    /**
     * Result of a rate limit check.
     */
    public record RateLimitResult(
        boolean allowed,
        String reason,
        Duration retryAfter,
        int currentCount,
        int limit
    ) {
        public static RateLimitResult allowed(int currentCount, int limit) {
            return new RateLimitResult(true, "OK", Duration.ZERO, currentCount, limit);
        }

        public static RateLimitResult denied(String reason, Duration retryAfter,
                                             int currentCount, int limit) {
            return new RateLimitResult(false, reason, retryAfter, currentCount, limit);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public long getRetryAfterSeconds() {
            return retryAfter.toSeconds();
        }
    }

    /**
     * Status of a client's rate limit.
     */
    public record ClientStatus(
        String clientId,
        int windowCount,
        int windowLimit,
        int burstCount,
        int burstLimit,
        boolean isLimited
    ) {
        public int remainingInWindow() {
            return Math.max(0, windowLimit - windowCount);
        }

        public int remainingBurst() {
            return Math.max(0, burstLimit - burstCount);
        }
    }

    /**
     * Exception thrown when rate limit is exceeded.
     */
    public static class RateLimitExceededException extends Exception {
        private final RateLimitResult result;

        public RateLimitExceededException(RateLimitResult result) {
            super("Rate limit exceeded: " + result.reason());
            this.result = result;
        }

        public RateLimitResult getResult() {
            return result;
        }

        public Duration getRetryAfter() {
            return result.retryAfter();
        }
    }

    // ========== INTERNAL CLASSES ==========

    /**
     * Per-client rate limiting bucket using sliding window.
     */
    private static class ClientBucket {
        private final int maxRequests;
        private final Duration windowSize;
        private final int burstLimit;
        private final Duration burstWindow;

        private final Deque<Instant> requestTimestamps = new ConcurrentLinkedDeque<>();
        private final Deque<Instant> burstTimestamps = new ConcurrentLinkedDeque<>();
        private volatile Instant lastAccess = Instant.now();

        ClientBucket(int maxRequests, Duration windowSize, int burstLimit, Duration burstWindow) {
            this.maxRequests = maxRequests;
            this.windowSize = windowSize;
            this.burstLimit = burstLimit;
            this.burstWindow = burstWindow;
        }

        synchronized RateLimitResult tryAcquire(Instant now) {
            lastAccess = now;

            // Clean old timestamps
            Instant windowStart = now.minus(windowSize);
            Instant burstStart = now.minus(burstWindow);

            while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst().isBefore(windowStart)) {
                requestTimestamps.pollFirst();
            }

            while (!burstTimestamps.isEmpty() && burstTimestamps.peekFirst().isBefore(burstStart)) {
                burstTimestamps.pollFirst();
            }

            // Check burst limit
            if (burstTimestamps.size() >= burstLimit) {
                Instant oldestBurst = burstTimestamps.peekFirst();
                Duration retryAfter = Duration.between(now, oldestBurst.plus(burstWindow));
                return RateLimitResult.denied("Burst limit exceeded",
                    retryAfter.isNegative() ? Duration.ZERO : retryAfter,
                    burstTimestamps.size(), burstLimit);
            }

            // Check window limit
            if (requestTimestamps.size() >= maxRequests) {
                Instant oldestRequest = requestTimestamps.peekFirst();
                Duration retryAfter = Duration.between(now, oldestRequest.plus(windowSize));
                return RateLimitResult.denied("Rate limit exceeded",
                    retryAfter.isNegative() ? Duration.ZERO : retryAfter,
                    requestTimestamps.size(), maxRequests);
            }

            // Allow request
            requestTimestamps.addLast(now);
            burstTimestamps.addLast(now);

            return RateLimitResult.allowed(requestTimestamps.size(), maxRequests);
        }

        ClientStatus getStatus(String clientId) {
            return new ClientStatus(
                clientId,
                requestTimestamps.size(),
                maxRequests,
                burstTimestamps.size(),
                burstLimit,
                requestTimestamps.size() >= maxRequests || burstTimestamps.size() >= burstLimit
            );
        }

        Instant getLastAccess() {
            return lastAccess;
        }
    }
}
