# Message Filtering & Rate Limiting Guide

This guide covers the full message filtering API and both rate limiting implementations available in Jentic.

The filtering subsystem spans two packages:
- **`agenor-core` / `dev.agenor.core.filter`** — `MessageFilter` interface and `MessageFilterBuilder`
- **`agenor-runtime` / `dev.agenor.runtime.filter`** — concrete filter implementations
- **`agenor-core` / `dev.agenor.core.ratelimit`** — `RateLimit`, `RateLimiter`, `RateLimiterStats`
- **`agenor-runtime` / `dev.agenor.runtime.ratelimit`** — `SlidingWindowRateLimiter`, `TokenBucketRateLimiter`
- **`agenor-runtime` / `dev.agenor.runtime.behavior.advanced`** — `ThrottledBehavior`

---

## MessageFilter interface

`MessageFilter` extends `Predicate<Message>`, so any lambda `msg -> boolean` is a valid filter.

```java
// Lambda syntax
MessageFilter myFilter = msg -> "orders.created".equals(msg.topic());

// Utilities
MessageFilter all     = MessageFilter.acceptAll();   // always true
MessageFilter none    = MessageFilter.rejectAll();   // always false
MessageFilter wrapped = MessageFilter.of(predicate); // wrap any Predicate<Message>

// Default combinators
MessageFilter combined = filterA.and(filterB);       // both must pass
MessageFilter either   = filterA.or(filterB);        // either passes
MessageFilter inverted = filterA.negate();           // logical NOT
```

### Performance characteristics

| Filter type | Cost |
|-------------|------|
| Topic (exact/prefix) | O(1) — string equality or `startsWith` |
| Header equality | O(1) — map lookup |
| Header/topic regex | O(n) — regex match on each message |
| Content predicate | depends on predicate |
| Composite (AND/OR) | short-circuits on first failure/success |

---

## Concrete filter classes (agenor-runtime)

All classes are in `dev.agenor.runtime.filter` and implement `MessageFilter`.

### TopicFilter

Matches messages by topic pattern.

```java
import dev.agenor.runtime.filter.TopicFilter;

// Exact match
TopicFilter.exact("orders.created");

// Prefix match
TopicFilter.startsWith("orders.");

// Suffix match
TopicFilter.endsWith(".error");

// Wildcard — * expands to any characters
TopicFilter.wildcard("orders.*.created");     // matches "orders.usa.created"
TopicFilter.wildcard("*.notification");       // matches "user.notification"

// Full regex
TopicFilter.regex("orders\\.(created|updated)");
```

### HeaderFilter

Matches messages by header key/value.

```java
import dev.agenor.runtime.filter.HeaderFilter;

// Header must exist (any value)
HeaderFilter.exists("x-trace-id");

// Exact value
HeaderFilter.equals("priority", "HIGH");

// Regex on value
HeaderFilter.matches("region", "us-.*");

// Value in set
HeaderFilter.in("priority", "HIGH", "CRITICAL");

// Value starts with prefix
HeaderFilter.startsWith("content-type", "application/");
```

### ContentFilter

Matches messages by payload content.

```java
import dev.agenor.runtime.filter.ContentFilter;

// Content must be of a specific type
ContentFilter.ofType(OrderData.class);

// Content must be non-null
ContentFilter.notNull();

// Custom predicate on content
ContentFilter.matching(obj ->
    obj instanceof OrderData order && order.amount() > 1000
);
```

### PredicateFilter

Wraps any `Predicate<Message>` with an optional description for logging.

```java
import dev.agenor.runtime.filter.PredicateFilter;

MessageFilter filter = new PredicateFilter(
    msg -> msg.senderId() != null && msg.senderId().startsWith("trusted-"),
    "sender-trust-check"
);

// Without description (defaults to "custom-predicate")
MessageFilter filter = new PredicateFilter(
    msg -> msg.topic() != null && !msg.topic().startsWith("internal.")
);

System.out.println(filter);  // PredicateFilter[sender-trust-check]
```

### CompositeFilter

Combines multiple filters with AND, OR, or NOT logic.

```java
import dev.agenor.runtime.filter.CompositeFilter;
import dev.agenor.runtime.filter.TopicFilter;
import dev.agenor.runtime.filter.HeaderFilter;

MessageFilter topic  = TopicFilter.startsWith("orders.");
MessageFilter urgent = HeaderFilter.equals("priority", "HIGH");
MessageFilter region = HeaderFilter.in("region", "eu-west", "eu-central");

// AND — all filters must pass
MessageFilter both = CompositeFilter.and(topic, urgent);

// OR — any filter may pass
MessageFilter either = CompositeFilter.or(urgent, region);

// NOT — inverts the first (and only) filter
MessageFilter notInternal = CompositeFilter.not(TopicFilter.startsWith("internal."));
```

---

## MessageFilterBuilder (fluent API)

`MessageFilterBuilder` (in `agenor-core`) provides a single chainable builder that produces an `AND`-combined `MessageFilter` by default.

```java
import dev.agenor.core.filter.MessageFilter;

MessageFilter filter = MessageFilter.builder()
    .topicStartsWith("orders.")          // topic prefix
    .topicMatches("orders\\..*")         // regex (alternative)
    .headerEquals("priority", "HIGH")    // exact header value
    .headerExists("x-trace-id")          // header presence
    .headerMatches("region", "us-.*")    // regex on header value
    .headerIn("priority", "HIGH", "CRITICAL")  // value in set
    .contentType(OrderData.class)        // content instanceof
    .contentPredicate(obj ->
        obj instanceof OrderData o && o.amount() > 500)  // content predicate
    .customPredicate(msg ->
        msg.senderId() != null)          // full message predicate
    .build();
```

Switch to OR mode:

```java
import dev.agenor.core.filter.FilterOperator;

MessageFilter filter = MessageFilter.builder()
    .operator(FilterOperator.OR)
    .topicStartsWith("orders.")
    .topicStartsWith("payments.")
    .build();
```

Additional routing fields:

```java
MessageFilter.builder()
    .senderId("producer-agent")
    .receiverId("consumer-agent")
    .correlationId("req-42")
    .build();
```

---

## Registering filters on subscriptions

### Programmatic subscription

Pass a `MessageFilter` (or any `Predicate<Message>`) to `FilterableSubscriber.subscribeFiltered()`. The in-memory dispatcher implements this capability:

```java
// Using a concrete filter class
MessageFilter filter = CompositeFilter.and(
    TopicFilter.startsWith("orders."),
    HeaderFilter.equals("priority", "HIGH")
);

FilterableSubscriber filterable = (FilterableSubscriber) dispatcher;
filterable.subscribeFiltered(filter, message -> {
    OrderData order = message.getContent(OrderData.class);
    processUrgentOrder(order);
});
```

### Inside an agent's onStart()

```java
@Agent("order-processor")
public class OrderProcessorAgent extends BaseAgent {

    @Override
    protected void onStart() {
        MessageFilter filter = MessageFilter.builder()
            .topicStartsWith("orders.")
            .headerIn("status", "pending", "confirmed")
            .build();

        ((FilterableSubscriber) getMessageDispatcher()).subscribeFiltered(filter, this::handleOrder);
    }

    private void handleOrder(Message msg) {
        // Only receives orders with status = pending or confirmed
    }
}
```

### With @AgenorMessageHandler and inline filtering

`@AgenorMessageHandler` routes by topic. For additional conditions, combine it with a secondary filter check inside the handler, or use programmatic subscription as above:

```java
@AgenorMessageHandler("orders.*")
public void handleOrder(Message msg) {
    // Topic already filtered by annotation pattern
    // Extra guard for header check
    if (!"HIGH".equals(msg.headers().get("priority"))) {
        return;
    }
    processOrder(msg.getContent(OrderData.class));
}
```

---

## Rate Limiting

### RateLimit configuration

`RateLimit` is a record that defines the limit parameters. All three constructors are immutable.

```java
import dev.agenor.core.ratelimit.RateLimit;

// Factory methods
RateLimit tenPerSecond   = RateLimit.perSecond(10);
RateLimit hundredPerMin  = RateLimit.perMinute(100);
RateLimit thousandPerHour = RateLimit.perHour(1000);

// Explicit constructor
RateLimit limit = new RateLimit(
    50,                    // maxRequests
    Duration.ofMinutes(1), // period
    100                    // burstCapacity (for token bucket)
);

// Parse from string ("number/unit")
RateLimit limit = RateLimit.parse("10/s");    // 10 per second
RateLimit limit = RateLimit.parse("100/min"); // 100 per minute
RateLimit limit = RateLimit.parse("500/h");   // 500 per hour

// Override burst capacity
RateLimit burst = RateLimit.perSecond(10).withBurst(30);  // 10 avg, burst of 30
```

### SlidingWindowRateLimiter

Tracks requests in a rolling time window. Provides smooth traffic shaping — the rate is always measured over the most recent `period` ms, so no request can benefit from being made at the start of a fixed window.

```java
import dev.agenor.runtime.ratelimit.SlidingWindowRateLimiter;

RateLimiter limiter = new SlidingWindowRateLimiter(RateLimit.perSecond(10));

// Non-blocking: return immediately with true/false
if (limiter.tryAcquire()) {
    callExternalApi();
} else {
    log.warn("Rate limit exceeded, request dropped");
}

// Blocking: wait until a permit is available
limiter.acquire().thenRun(this::callExternalApi);

// Blocking with timeout: returns true if acquired, false if timed out
boolean acquired = limiter.acquire(Duration.ofSeconds(2)).join();
if (acquired) {
    callExternalApi();
}

// Observe current capacity
int permits = limiter.availablePermits();

// Reset all counters and the timestamp queue
limiter.reset();
```

**Best for:** APIs that must not be hit in bursts; smooth ingest pipelines; LLM API calls with strict RPM limits.

### TokenBucketRateLimiter

Implements the token bucket algorithm. Tokens are added to the bucket at a steady rate; each request consumes one token. The bucket has a `burstCapacity` ceiling, allowing temporary bursts above the average rate.

```java
import dev.agenor.runtime.ratelimit.TokenBucketRateLimiter;

// 10 requests/second with burst of 30
RateLimit limit   = RateLimit.perSecond(10).withBurst(30);
RateLimiter limiter = new TokenBucketRateLimiter(limit);

// Same API as SlidingWindowRateLimiter
if (limiter.tryAcquire()) { ... }
limiter.acquire().join();
limiter.acquire(Duration.ofMillis(500)).join();

// Refill happens automatically every ~100ms as tryAcquire() is called
```

**Best for:** event-driven processing that naturally produces bursts; webhook handlers; batch jobs that need burst tolerance.

### Choosing between the two

| Aspect | SlidingWindow | TokenBucket |
|--------|---------------|-------------|
| Burst tolerance | ❌ strict at all times | ✅ up to `burstCapacity` |
| Traffic shape | smooth | bursty |
| Memory cost | O(n) timestamps | O(1) |
| Thread safety | ConcurrentLinkedQueue | AtomicInteger + lock |
| Best for | rate-sensitive APIs | event-driven processing |

### RateLimiterStats

Both implementations expose statistics:

```java
RateLimiterStats stats = limiter.getStats();

System.out.println("Total requests  : " + stats.totalRequests());
System.out.println("Allowed         : " + stats.allowedRequests());
System.out.println("Rejected        : " + stats.rejectedRequests());
System.out.printf("Rejection rate  : %.1f%%%n", stats.rejectionRate());
System.out.println("Current permits : " + stats.currentPermits());
System.out.println("Last reset      : " + stats.lastReset());
```

---

## ThrottledBehavior — rate limiting for agent behaviors

`ThrottledBehavior` (in `dev.agenor.runtime.behavior.advanced`) wraps any repeating action into a rate-limited behavior. Internally it uses `TokenBucketRateLimiter`.

### Waiting mode — blocks until permit available

```java
import dev.agenor.runtime.behavior.advanced.ThrottledBehavior;

Behavior apiBehavior = ThrottledBehavior.fromWaiting(
    RateLimit.perSecond(5),
    () -> callExternalApi()
);
addBehavior(apiBehavior);
```

### Skipping mode — skips execution when rate exceeded

```java
Behavior apiBehavior = ThrottledBehavior.fromSkipping(
    RateLimit.perSecond(5),
    () -> callExternalApi()
);
```

### Cyclic mode — periodic with rate limiting

```java
// Execute at most 5 times/second, and no faster than every 500ms
Behavior apiBehavior = ThrottledBehavior.cyclic(
    RateLimit.perSecond(5),
    Duration.ofMillis(500),
    () -> pollQueue()
);
```

### Subclassing (full control)

```java
@Agent("api-caller")
public class ApiCallerAgent extends BaseAgent {

    private final ThrottledBehavior apiPoller = new ThrottledBehavior(
            "api-poller",
            RateLimit.perSecond(5),
            Duration.ofMillis(200),
            true   // waitForPermit
    ) {
        @Override
        protected void throttledAction() {
            fetchAndProcess();
        }

        @Override
        protected void onRateLimitExceeded() {
            log.warn("API rate limit exceeded, backing off");
        }
    };

    @Override
    protected void onStart() {
        addBehavior(apiPoller);
    }
}
```

### ThrottledBehavior API summary

| Method | Description |
|--------|-------------|
| `fromWaiting(limit, action)` | Create blocking throttled behavior |
| `fromSkipping(limit, action)` | Create non-blocking (skip on limit) behavior |
| `cyclic(limit, interval, action)` | Periodic + throttled |
| `getRateLimiterStats()` | Current rate limiter statistics |
| `getThrottledExecutions()` | Successful execution count |
| `getRejectedExecutions()` | Skipped execution count (skip mode only) |
| `availablePermits()` | Current token count |
| `resetRateLimiter()` | Reset limiter state |

---

## Complete example: Filtered + throttled agent

```java
@Agent("order-enricher")
public class OrderEnricherAgent extends BaseAgent {

    @Override
    protected void onStart() {
        // Only handle urgent orders from external sources
        MessageFilter filter = CompositeFilter.and(
            TopicFilter.startsWith("orders."),
            HeaderFilter.equals("priority", "HIGH"),
            CompositeFilter.not(TopicFilter.startsWith("orders.internal."))
        );

        messageService.subscribe(filter, this::enqueueOrder);

        // Enrich at most 20 times/second; burst of 40 allowed
        addBehavior(ThrottledBehavior.fromWaiting(
            RateLimit.perSecond(20).withBurst(40),
            this::processNextFromQueue
        ));
    }

    private void enqueueOrder(Message msg) {
        // Add to internal queue
    }

    private void processNextFromQueue() {
        // Pull from queue and call enrichment API
    }
}
```

---

## See Also

- [Agent Development Guide](agent-development.md) — `@AgenorMessageHandler`, behaviors
- [Architecture Guide](architecture.md) — module overview
- [LLM Integration Guide](llm-integration.md) — rate limiting for LLM API calls
