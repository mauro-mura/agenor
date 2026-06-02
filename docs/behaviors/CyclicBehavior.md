# CyclicBehavior - Periodic Execution

## Overview

`CyclicBehavior` executes its action repeatedly at a **fixed interval**. The scheduler calls `execute()` each time the interval elapses.

**Since**: v0.1.0 | **Type**: `BehaviorType.CYCLIC` | **Package**: `dev.agenor.runtime.behavior`

---

## Usage

### Subclass

```java
public class HealthCheckBehavior extends CyclicBehavior {

    public HealthCheckBehavior() {
        super("health-check", Duration.ofSeconds(30));
    }

    @Override
    protected void action() {
        boolean healthy = downstream.ping();
        if (!healthy) {
            log.warn("Downstream unhealthy");
        }
    }
}
agent.addBehavior(new HealthCheckBehavior());
```

### Factory

```java
agent.addBehavior(CyclicBehavior.from(Duration.ofSeconds(30), () -> {
    boolean healthy = downstream.ping();
    if (!healthy) log.warn("Downstream unhealthy");
}));

// Named
agent.addBehavior(CyclicBehavior.from("health-check", Duration.ofSeconds(30),
    () -> downstream.ping()));
```

---

## Constructors

```java
protected CyclicBehavior(Duration interval)
protected CyclicBehavior(String behaviorId, Duration interval)
```

## Factory Methods

```java
static CyclicBehavior from(Duration interval, Runnable action)
static CyclicBehavior from(String name, Duration interval, Runnable action)
```

---

## See Also

- [ScheduledBehavior](ScheduledBehavior.md) - Cron-based scheduling
- [ThrottledBehavior](ThrottledBehavior.md) - Rate-limited cyclic execution
- [Behavior Overview](README.md)
