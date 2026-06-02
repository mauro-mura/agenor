# WakerBehavior - Condition or Time-Triggered Execution

## Overview

`WakerBehavior` polls a `Supplier<Boolean>` condition at a configurable interval and calls `onWake()` as soon as the condition becomes `true`. It is the go-to choice for **delayed** or **trigger-based** one-shot actions.

**Since**: v0.1.0 | **Type**: `BehaviorType.WAKER` | **Package**: `dev.agenor.runtime.behavior`

---

## Usage

### Subclass

```java
public class DataReadyBehavior extends WakerBehavior {

    public DataReadyBehavior() {
        super("data-ready",
              () -> dataStore.isDataAvailable(),
              Duration.ofSeconds(2));
    }

    @Override
    protected void onWake() {
        processData(dataStore.getData());
    }
}
agent.addBehavior(new DataReadyBehavior());
```

### Factory Methods

```java
// Wake at a specific instant (one-time, stops itself after firing)
agent.addBehavior(WakerBehavior.wakeAt(
    Instant.parse("2026-03-01T08:00:00Z"),
    () -> sendMorningReport()
));

// Wake after a delay from now (one-time)
agent.addBehavior(WakerBehavior.wakeAfter(
    Duration.ofMinutes(10),
    () -> retryConnection()
));

// Wake whenever a custom condition becomes true (recurrent until stopped)
agent.addBehavior(WakerBehavior.wakeWhen(
    () -> queue.size() > threshold,
    () -> drainQueue()
));
```

---

## Constructors

```java
protected WakerBehavior(Supplier<Boolean> wakeCondition)
protected WakerBehavior(Supplier<Boolean> wakeCondition, Duration checkInterval)
protected WakerBehavior(String behaviorId, Supplier<Boolean> wakeCondition, Duration checkInterval)
```

Default `checkInterval` is `1 second`.

## Factory Methods

```java
static WakerBehavior wakeAt(Instant wakeTime, Runnable action)
static WakerBehavior wakeAfter(Duration delay, Runnable action)
static WakerBehavior wakeWhen(Supplier<Boolean> condition, Runnable action)
```

`wakeAt` and `wakeAfter` call `stop()` after firing — they are one-time wakers.  
`wakeWhen` does **not** stop itself — override `onWake()` and call `stop()` manually if needed.

---

## See Also

- [OneShotBehavior](OneShotBehavior.md) - Immediate single execution
- [ScheduledBehavior](ScheduledBehavior.md) - Cron-based scheduling
- [Behavior Overview](README.md)
