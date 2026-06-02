package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.ratelimit.RateLimit;
import dev.agenor.core.ratelimit.RateLimiter;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.ratelimit.TokenBucketRateLimiter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Behavior with rate limiting to control execution frequency.
 * Prevents overwhelming external resources or APIs.
 */
public abstract class ThrottledBehavior extends BaseBehavior {

    private final RateLimiter rateLimiter;
    private final boolean waitForPermit;
    private long throttledExecutions = 0;
    private long rejectedExecutions = 0;

    protected ThrottledBehavior(RateLimit rateLimit) {
        this(null, rateLimit, null, true);
    }

    protected ThrottledBehavior(RateLimit rateLimit, Duration interval) {
        this(null, rateLimit, interval, true);
    }

    protected ThrottledBehavior(String behaviorId, RateLimit rateLimit, Duration interval, boolean waitForPermit) {
        super(behaviorId != null ? behaviorId : "throttled-behavior",
              BehaviorType.CUSTOM, interval);
        this.rateLimiter = new TokenBucketRateLimiter(rateLimit);
        this.waitForPermit = waitForPermit;
    }

    @Override
    public CompletableFuture<Void> execute() {
        if (!isActive()) {
            return CompletableFuture.completedFuture(null);
        }

        if (waitForPermit) {
            // Wait for rate limit permit
            return rateLimiter.acquire()
                .thenRunAsync(() -> executeThrottledAction());
        } else {
            // Try to acquire, skip if not available
            return CompletableFuture.runAsync(() -> {
                if (rateLimiter.tryAcquire()) {
                    executeThrottledAction();
                } else {
                    rejectedExecutions++;
                    log.trace("Execution rejected by rate limiter: {}", getBehaviorId());
                    onRateLimitExceeded();
                }
            });
        }
    }

    private void executeThrottledAction() {
        try {
            throttledAction();
            throttledExecutions++;
        } catch (Exception e) {
            log.error("Error executing throttled action: {}", getBehaviorId(), e);
            onError(e);
        }
    }

    /**
     * The rate-limited action to execute.
     * Must be implemented by subclasses.
     */
    protected abstract void throttledAction();

    /**
     * Called when rate limit is exceeded and execution is rejected.
     * Only called when waitForPermit is false.
     */
    protected void onRateLimitExceeded() {
        // Default: do nothing
    }

    /**
     * Get rate limiter statistics
     */
    public dev.agenor.core.ratelimit.RateLimiterStats getRateLimiterStats() {
        return rateLimiter.getStats();
    }

    /**
     * Get number of successful throttled executions
     */
    public long getThrottledExecutions() {
        return throttledExecutions;
    }

    /**
     * Get number of rejected executions (only when waitForPermit=false)
     */
    public long getRejectedExecutions() {
        return rejectedExecutions;
    }

    /**
     * Get available permits from rate limiter
     */
    public int availablePermits() {
        return rateLimiter.availablePermits();
    }

    /**
     * Reset rate limiter state
     */
    public void resetRateLimiter() {
        rateLimiter.reset();
    }

    /**
     * Factory method: Create throttled behavior that waits for permits
     */
    public static ThrottledBehavior fromWaiting(RateLimit rateLimit, Runnable action) {
        return new ThrottledBehavior(rateLimit) {
            @Override
            protected void throttledAction() {
                action.run();
            }
        };
    }

    /**
     * Factory method: Create throttled behavior that skips when rate limited
     */
    public static ThrottledBehavior fromSkipping(RateLimit rateLimit, Runnable action) {
        return new ThrottledBehavior(null, rateLimit, null, false) {
            @Override
            protected void throttledAction() {
                action.run();
            }
        };
    }

    /**
     * Factory method: Create cyclic throttled behavior
     */
    public static ThrottledBehavior cyclic(RateLimit rateLimit, Duration interval, Runnable action) {
        return new ThrottledBehavior(rateLimit, interval) {
            @Override
            protected void throttledAction() {
                action.run();
            }
        };
    }

    @Override
    protected void action() {
        // Not used in throttled behavior - execute() is overridden
    }
}
