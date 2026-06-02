# CircuitBreakerBehavior

## Overview

**CircuitBreakerBehavior** implements the Circuit Breaker pattern for fault tolerance and cascading failure prevention in distributed systems. It protects your application from repeatedly calling a failing service, allowing it time to recover while providing fallback mechanisms.

## Pattern Description

The Circuit Breaker pattern acts like an electrical circuit breaker - it "opens" when too many failures occur, preventing further calls to a failing service. After a recovery period, it transitions to a "half-open" state to test if the service has recovered.

### States

```
     ┌─────────┐
     │ CLOSED  │◄────────────────┐
     └────┬────┘                 │
          │                      │
    failures ≥                   │
    threshold            successes ≥
          │               threshold
          ▼                      │
     ┌─────────┐                 │
     │  OPEN   │                 │
     └────┬────┘                 │
          │                      │
    recovery                     │
    timeout                      │
    elapsed                      │
          │                      │
          ▼                      │
     ┌──────────┐                │
     │HALF_OPEN │────────────────┘
     └──────────┘
          │
    any failure
          │
          ▼
       (back to OPEN)
```

#### CLOSED
- **Normal operation** - all requests are allowed through
- Failures are counted
- When failure count reaches threshold → transition to OPEN

#### OPEN
- **Circuit is broken** - requests are rejected immediately
- No calls are made to the protected service
- After recovery timeout → transition to HALF_OPEN
- Provides fast failure responses

#### HALF_OPEN
- **Testing recovery** - limited requests are allowed
- If success threshold is reached → transition to CLOSED
- If any failure occurs → transition back to OPEN
- Gradual recovery testing

## Features

- ✅ **Three-state machine**: CLOSED → OPEN → HALF_OPEN → CLOSED
- ✅ **Configurable thresholds**: Failure count, success count, recovery timeout
- ✅ **Fallback mechanism**: Provide alternative responses when circuit is open
- ✅ **Comprehensive metrics**: Track all requests, successes, failures, rejections
- ✅ **State change callbacks**: React to circuit state transitions
- ✅ **Thread-safe**: Safe for concurrent access
- ✅ **Manual controls**: Reset and trip for testing/admin purposes

## Usage

### Basic Example

```java
CircuitBreakerBehavior<String> breaker = new CircuitBreakerBehavior<>(
    "external-api-breaker",
    5,                          // Open after 5 consecutive failures
    Duration.ofSeconds(30),     // Wait 30s before testing recovery
    3                           // Close after 3 consecutive successes
) {
    @Override
    protected String executeAction() throws Exception {
        return externalApiClient.call();
    }
    
    @Override
    protected String fallback(Exception e) {
        return "Service temporarily unavailable - using cached data";
    }
};

// Make a call through the circuit breaker
try {
    String result = breaker.call();
    System.out.println("Success: " + result);
} catch (CircuitBreakerOpenException e) {
    System.out.println("Circuit is open - using fallback");
    String fallback = breaker.fallback(e);
} catch (Exception e) {
    System.out.println("Request failed: " + e.getMessage());
}
```

### With Agent Integration

```java
@Agent("payment-processor")
public class PaymentProcessorAgent extends BaseAgent {
    
    private CircuitBreakerBehavior<PaymentResult> paymentBreaker;
    
    @Override
    protected void onStart() {
        // Create circuit breaker for external payment gateway
        paymentBreaker = new CircuitBreakerBehavior<>(
            "payment-gateway-breaker",
            3,                      // Open after 3 failures
            Duration.ofSeconds(60), // Try recovery after 1 minute
            2                       // Close after 2 successes
        ) {
            @Override
            protected PaymentResult executeAction() throws Exception {
                return paymentGateway.processPayment();
            }
            
            @Override
            protected PaymentResult fallback(Exception e) {
                return PaymentResult.queued("Payment queued for retry");
            }
        };
        
        // Monitor state changes
        paymentBreaker.onStateChange(state -> 
            log.warn("Payment gateway circuit breaker: {}", state)
        );
        
        addBehavior(paymentBreaker);
    }
    
    @AgenorMessageHandler("payment.request")
    public void handlePaymentRequest(Message message) {
        try {
            PaymentResult result = paymentBreaker.call();
            sendResponse(message, result);
        } catch (CircuitBreakerOpenException e) {
            sendResponse(message, paymentBreaker.fallback(e));
        }
    }
}
```

### Factory Methods

```java
// Standard configuration (5 failures, 30s timeout, 3 successes)
var breaker = CircuitBreakerBehavior.standard(
    "api-breaker",
    () -> externalApi.call()
);

// Custom configuration
var breaker = CircuitBreakerBehavior.custom(
    "custom-breaker",
    () -> service.call(),
    10,                      // 10 failures to open
    Duration.ofMinutes(5),   // 5 minutes recovery
    5                        // 5 successes to close
);
```

## Configuration

### Constructor Parameters

```java
public CircuitBreakerBehavior(
    String behaviorId,          // Unique identifier
    int failureThreshold,       // Failures before opening (> 0)
    Duration recoveryTimeout,   // Time to wait in OPEN state
    int successThreshold        // Successes to close from HALF_OPEN (> 0)
)
```

#### Recommended Values

| Scenario | Failures | Recovery | Successes | Rationale |
|----------|----------|----------|-----------|-----------|
| **Fast Services** | 3-5 | 10-30s | 2-3 | Quick detection, fast recovery |
| **Slow Services** | 5-10 | 60-120s | 3-5 | More tolerance, longer recovery |
| **Critical Services** | 10-15 | 5-10s | 5-10 | High threshold, quick retry |
| **Flaky Services** | 3-5 | 30-60s | 5-10 | Quick open, careful close |

## Callbacks

### State Change Listener

Invoked whenever the circuit transitions between states:

```java
breaker.onStateChange(state -> {
    switch (state) {
        case CLOSED -> log.info("Circuit healthy - all systems go");
        case OPEN -> log.warn("Circuit opened - service failing");
        case HALF_OPEN -> log.info("Testing recovery...");
    }
});
```

### Success Listener

Invoked on every successful execution:

```java
breaker.onSuccess(result -> {
    metrics.recordSuccess();
    log.debug("Request successful: {}", result);
});
```

### Failure Listener

Invoked on every failed execution:

```java
breaker.onFailure(exception -> {
    metrics.recordFailure();
    log.error("Request failed", exception);
    
    // Alert on specific errors
    if (exception instanceof TimeoutException) {
        alerting.sendAlert("Service timeout detected");
    }
});
```

## Metrics

### Available Metrics

```java
CircuitBreakerMetrics metrics = breaker.getMetrics();

// Request counters
long total = metrics.totalRequests();
long successes = metrics.successfulRequests();
long failures = metrics.failedRequests();
long rejected = metrics.rejectedRequests();

// Rates
double successRate = metrics.successRate();      // 0-100%
double failureRate = metrics.failureRate();      // 0-100%

// State information
State currentState = metrics.currentState();
int stateChanges = metrics.stateChangeCount();
Duration timeInState = metrics.timeInCurrentState();

// Consecutive counters
int consecutiveFailures = metrics.consecutiveFailures();
int consecutiveSuccesses = metrics.consecutiveSuccesses();
```

### Metrics Example

```java
// Periodic metrics reporting
@Behavior(type = CYCLIC, interval = "60s")
public void reportMetrics() {
    var metrics = breaker.getMetrics();
    
    log.info("Circuit Breaker Metrics:");
    log.info("  State: {} (for {})", 
        metrics.currentState(), 
        metrics.timeInCurrentState()
    );
    log.info("  Total Requests: {}", metrics.totalRequests());
    log.info("  Success Rate: {:.1f}%", metrics.successRate());
    log.info("  Rejected: {}", metrics.rejectedRequests());
    
    // Export to monitoring system
    metricsCollector.gauge("circuit.success_rate", metrics.successRate());
    metricsCollector.counter("circuit.state_changes", metrics.stateChangeCount());
}
```

## Manual Controls

### Reset

Manually reset the circuit to CLOSED state (use with caution):

```java
breaker.reset();  // Force CLOSED state
```

**Use cases**:
- Administrative override after maintenance
- Testing scenarios
- Emergency service restoration

### Trip

Manually trip the circuit to OPEN state:

```java
breaker.trip();  // Force OPEN state
```

**Use cases**:
- Planned maintenance windows
- Emergency shutdown
- Testing failure scenarios

## Best Practices

### 1. Choose Appropriate Thresholds

```java
// ❌ BAD: Too sensitive - might open on transient errors
new CircuitBreakerBehavior("breaker", 1, Duration.ofSeconds(5), 1);

// ✅ GOOD: Balanced - tolerates occasional failures
new CircuitBreakerBehavior("breaker", 5, Duration.ofSeconds(30), 3);
```

### 2. Implement Meaningful Fallbacks

```java
@Override
protected String fallback(Exception e) {
    // ❌ BAD: Just return null
    return null;
    
    // ✅ GOOD: Provide useful fallback
    return cacheService.getLastKnownGood()
        .orElse("Service temporarily unavailable");
}
```

### 3. Monitor State Changes

```java
// ✅ GOOD: Alert on circuit opens
breaker.onStateChange(state -> {
    if (state == State.OPEN) {
        alertService.send(
            "Circuit breaker opened for " + breaker.getBehaviorId(),
            Severity.HIGH
        );
    }
});
```

### 4. Combine with Other Patterns

```java
// Circuit Breaker + Retry
RetryBehavior<String> retry = new RetryBehavior<>(
    "retry-wrapper",
    3,
    BackoffStrategy.EXPONENTIAL,
    Duration.ofSeconds(1)
) {
    @Override
    protected String attemptAction() throws Exception {
        return circuitBreaker.call();  // Protected by circuit breaker
    }
    
    @Override
    protected boolean shouldRetry(Exception e) {
        // Don't retry if circuit is open
        return !(e instanceof CircuitBreakerOpenException);
    }
};
```

### 5. Use Separate Breakers for Different Services

```java
// ✅ GOOD: One breaker per external service
private CircuitBreakerBehavior<Data> databaseBreaker;
private CircuitBreakerBehavior<String> apiBreaker;
private CircuitBreakerBehavior<Result> cacheBreaker;

// ❌ BAD: Single breaker for everything
private CircuitBreakerBehavior<Object> singleBreaker;
```

## Real-World Examples

### Example 1: Payment Gateway Protection

```java
public class PaymentService {
    private final CircuitBreakerBehavior<PaymentResult> breaker;
    
    public PaymentService() {
        this.breaker = new CircuitBreakerBehavior<>(
            "stripe-breaker",
            5,                          // Open after 5 failures
            Duration.ofMinutes(2),      // 2 minute recovery
            3                           // 3 successes to close
        ) {
            @Override
            protected PaymentResult executeAction() throws Exception {
                return stripeApi.charge(paymentRequest);
            }
            
            @Override
            protected PaymentResult fallback(Exception e) {
                // Queue for later processing
                paymentQueue.enqueue(paymentRequest);
                return PaymentResult.queued();
            }
        };
        
        // Alert on state changes
        breaker.onStateChange(state -> {
            if (state == State.OPEN) {
                ops.alert("Stripe payment gateway down!");
            }
        });
    }
    
    public PaymentResult processPayment(PaymentRequest request) {
        try {
            return breaker.call();
        } catch (CircuitBreakerOpenException e) {
            // Circuit is open - return fallback
            return breaker.fallback(e);
        } catch (Exception e) {
            // Unexpected error
            log.error("Payment processing error", e);
            throw new PaymentException(e);
        }
    }
}
```

### Example 2: Database Query Protection

```java
@Agent("user-service")
public class UserServiceAgent extends BaseAgent {
    
    private CircuitBreakerBehavior<List<User>> dbBreaker;
    
    @Override
    protected void onStart() {
        dbBreaker = CircuitBreakerBehavior.standard(
            "postgres-breaker",
            () -> database.executeQuery("SELECT * FROM users")
        );
        
        // Fallback to read replica
        dbBreaker.onStateChange(state -> {
            if (state == State.OPEN) {
                log.warn("Primary DB unreachable, using read replica");
            }
        });
        
        addBehavior(dbBreaker);
    }
    
    @AgenorMessageHandler("user.list")
    public void handleUserListRequest(Message message) {
        try {
            List<User> users = dbBreaker.call();
            sendResponse(message, users);
        } catch (CircuitBreakerOpenException e) {
            // Use read replica as fallback
            List<User> users = readReplicaDb.getUsers();
            sendResponse(message, users);
        }
    }
}
```

### Example 3: API Rate Limiting Protection

```java
public class RateLimitedApiClient {
    private final CircuitBreakerBehavior<ApiResponse> breaker;
    
    public RateLimitedApiClient() {
        this.breaker = new CircuitBreakerBehavior<>(
            "ratelimit-breaker",
            3,                          // Open after 3 rate limit errors
            Duration.ofMinutes(1),      // Wait 1 minute (rate limit window)
            2                           // 2 successes to close
        ) {
            @Override
            protected ApiResponse executeAction() throws Exception {
                ApiResponse response = httpClient.get("/api/data");
                
                // Treat rate limits as circuit-opening failures
                if (response.statusCode() == 429) {
                    throw new RateLimitException("Rate limit exceeded");
                }
                
                return response;
            }
            
            @Override
            protected ApiResponse fallback(Exception e) {
                return ApiResponse.cached("Using cached data");
            }
        };
    }
}
```

## Integration with Other Behaviors

### With RetryBehavior

```java
// Outer: Circuit Breaker (fast-fail)
// Inner: Retry (resilience)
CircuitBreakerBehavior<String> circuit = 
    CircuitBreakerBehavior.standard("api-circuit", () -> {
        // Retry logic inside circuit breaker
        return RetryBehavior.standard("api-retry", () -> 
            api.call()
        ).call();
    });
```

### With ThrottledBehavior

```java
// Combine rate limiting with circuit breaking
ThrottledBehavior throttled = new ThrottledBehavior(
    "rate-limited",
    RateLimit.parse("100/minute"),
    Duration.ofMillis(100),
    true
) {
    @Override
    protected void throttledAction() {
        try {
            circuitBreaker.call();
        } catch (CircuitBreakerOpenException e) {
            log.warn("Circuit open, skipping request");
        }
    }
};
```

## Testing

### Unit Testing

```java
@Test
void shouldOpenCircuitAfterThresholdFailures() {
    CircuitBreakerBehavior<String> breaker = new CircuitBreakerBehavior<>(
        "test-breaker",
        3,
        Duration.ofSeconds(5),
        2
    ) {
        @Override
        protected String executeAction() throws Exception {
            throw new RuntimeException("Simulated failure");
        }
    };
    
    // Execute failures up to threshold
    for (int i = 0; i < 3; i++) {
        assertThrows(RuntimeException.class, () -> breaker.call());
    }
    
    // Circuit should now be OPEN
    assertTrue(breaker.isOpen());
    
    // Next call should be rejected
    assertThrows(
        CircuitBreakerOpenException.class,
        () -> breaker.call()
    );
}
```

### Integration Testing

```java
@Test
void shouldRecoverAfterTimeout() throws Exception {
    AtomicBoolean serviceFailing = new AtomicBoolean(true);
    
    CircuitBreakerBehavior<String> breaker = new CircuitBreakerBehavior<>(
        "recovery-test",
        2,
        Duration.ofSeconds(2),
        1
    ) {
        @Override
        protected String executeAction() throws Exception {
            if (serviceFailing.get()) {
                throw new RuntimeException("Service down");
            }
            return "success";
        }
    };
    
    // Trip the circuit
    breaker.trip();
    assertTrue(breaker.isOpen());
    
    // Wait for recovery timeout
    Thread.sleep(2500);
    
    // Service recovers
    serviceFailing.set(false);
    
    // Next call should succeed and close circuit
    String result = breaker.call();
    assertEquals("success", result);
    assertTrue(breaker.isClosed());
}
```

## Performance Considerations

### Memory Usage

- **Per instance**: ~256 bytes
- **Metrics storage**: ~200 bytes
- **Total per breaker**: ~500 bytes

### Throughput

- **State checks**: O(1) - atomic reference read
- **Counter updates**: O(1) - atomic increment
- **State transitions**: O(1) - CAS operation
- **Overhead**: <1μs per request

### Concurrency

- **Thread-safe**: All operations are atomic
- **Lock-free**: Uses CAS-based atomic operations
- **Scalable**: No contention under load

## Troubleshooting

### Circuit Opens Too Frequently

**Problem**: Circuit opens on transient errors

**Solutions**:
1. Increase failure threshold
2. Add pre-filtering for expected errors
3. Implement smarter failure detection

```java
@Override
protected String executeAction() throws Exception {
    try {
        return service.call();
    } catch (TransientException e) {
        // Don't count as circuit-opening failure
        log.debug("Transient error, ignoring");
        throw e;
    }
}
```

### Circuit Never Opens

**Problem**: Failures don't trigger circuit opening

**Solutions**:
1. Check if failures are actually reaching the circuit
2. Verify failure threshold isn't too high
3. Check exception handling

### Circuit Stuck Open

**Problem**: Circuit doesn't recover after service is healthy

**Solutions**:
1. Verify recovery timeout is reasonable
2. Check success threshold isn't too high
3. Manually reset if needed: `breaker.reset()`

## Related Patterns

- **Retry Pattern**: `RetryBehavior` - Automatic retry with backoff
- **Timeout Pattern**: Built into operations with `Duration` timeouts
- **Bulkhead Pattern**: Isolate failures using separate thread pools
- **Fallback Pattern**: Implemented via `fallback()` method

## References

- [Martin Fowler - Circuit Breaker](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Netflix Hystrix Design Principles](https://github.com/Netflix/Hystrix/wiki)
- [Resilience4j Documentation](https://resilience4j.readme.io/docs/circuitbreaker)
- [Release It! - Michael Nygard](https://pragprog.com/titles/mnee2/release-it-second-edition/)

## See Also

- [RetryBehavior](RetryBehavior.md) - Automatic retry logic
- [ThrottledBehavior](ThrottledBehavior.md) - Rate limiting
- [BatchBehavior](BatchBehavior.md) - Bulk processing
- [Behavior Overview](README.md) - All available behaviors

---

**Version**: 0.2.0  
**Since**: Jentic 0.2.0  
**Status**: Production Ready ✅

=== docs/behaviors/PipelineBehavior.md ===
# 🔄 PipelineBehavior - Complete Documentation

