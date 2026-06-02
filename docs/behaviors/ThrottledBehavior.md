# ThrottledBehavior - Rate-Limited Execution

## Overview

`ThrottledBehavior` enforces a maximum execution rate using a **token bucket** algorithm. It prevents an agent from overwhelming downstream services, APIs, or resources.

**Since**: v0.2.0 | **Type**: `BehaviorType.THROTTLED` | **Package**: `dev.agenor.runtime.behavior.advanced`

---

## Key Features

- ✅ **Token Bucket Rate Limiter** — smooth rate enforcement
- ✅ **Two Execution Modes** — wait for permit or skip when rate exceeded
- ✅ **Execution Metrics** — track throttled and rejected executions
- ✅ **Factory Methods** — quick creation for common patterns
- ✅ **Cyclic Support** — combine with a fixed interval

---

## Execution Modes

| Mode | `waitForPermit` | Behaviour when rate exceeded |
|------|----------------|------------------------------|
| **Waiting** | `true` (default) | Blocks until a permit is available |
| **Skipping** | `false` | Skips execution and calls `onRateLimitExceeded()` |

---

## Basic Usage

### Subclass — waiting mode

```java
public class ApiCallerBehavior extends ThrottledBehavior {

    public ApiCallerBehavior() {
        super(RateLimit.of(10, Duration.ofSeconds(1))); // max 10 calls/sec
    }

    @Override
    protected void throttledAction() {
        apiClient.call();
    }
}
```

### Subclass — skipping mode

```java
public class MetricsSenderBehavior extends ThrottledBehavior {

    public MetricsSenderBehavior() {
        super("metrics-sender",
              RateLimit.of(5, Duration.ofSeconds(1)),
              Duration.ofMillis(200),
              false); // skip when rate exceeded
    }

    @Override
    protected void throttledAction() {
        metricsService.flush();
    }

    @Override
    protected void onRateLimitExceeded() {
        log.warn("Metrics flush skipped — rate limit exceeded");
    }
}
```

---

## Factory Methods

```java
// Wait for a permit before executing
ThrottledBehavior waiting = ThrottledBehavior.fromWaiting(
    RateLimit.of(10, Duration.ofSeconds(1)),
    () -> apiClient.call()
);

// Skip execution when rate exceeded
ThrottledBehavior skipping = ThrottledBehavior.fromSkipping(
    RateLimit.of(5, Duration.ofSeconds(1)),
    () -> metricsService.flush()
);

// Cyclic throttled behavior (interval + rate limit)
ThrottledBehavior cyclic = ThrottledBehavior.cyclic(
    RateLimit.of(100, Duration.ofMinutes(1)),
    Duration.ofSeconds(1),
    () -> processNextItem()
);

agent.addBehavior(waiting);
```

---

## Constructors

```java
// Minimal: waiting mode, no fixed interval
protected ThrottledBehavior(RateLimit rateLimit)

// With fixed cyclic interval, waiting mode
protected ThrottledBehavior(RateLimit rateLimit, Duration interval)

// Full control
protected ThrottledBehavior(String behaviorId, RateLimit rateLimit,
                             Duration interval, boolean waitForPermit)
```

---

## API Reference

### Monitoring

```java
// Rate limiter statistics (permits issued, wait times, etc.)
RateLimiterStats stats = behavior.getRateLimiterStats();

// Successfully executed invocations
long executed = behavior.getThrottledExecutions();

// Invocations rejected because rate was exceeded (skipping mode only)
long rejected = behavior.getRejectedExecutions();

// Currently available permits
int available = behavior.availablePermits();
```

### Control

```java
// Reset token bucket state
behavior.resetRateLimiter();
```

---

## Use Cases

- Calling external REST APIs with rate limits
- Writing metrics to time-series databases
- Publishing events to rate-limited message brokers
- Controlling fanout in multi-agent communication

---

## See Also

- [CircuitBreakerBehavior](CircuitBreakerBehavior.md) - Stop calling failing services
- [RetryBehavior](RetryBehavior.md) - Retry on failure
- [BatchBehavior](BatchBehavior.md) - Reduce call frequency by batching
- [Behavior Overview](README.md)

---

**Since**: Jentic 0.2.0  
**Status**: Production Ready ✅
