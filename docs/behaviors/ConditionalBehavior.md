# ConditionalBehavior - Condition-Gated Execution

## Overview

`ConditionalBehavior` wraps an action behind a `Condition` gate. On each execution, the condition is evaluated against the owning agent; if satisfied, `conditionalAction()` runs; otherwise the tick is skipped and `onConditionNotMet()` is called.

**Since**: v0.3.0 | **Type**: `BehaviorType.CONDITIONAL` | **Package**: `dev.agenor.runtime.behavior.advanced`

---

## Key Features

- ✅ **Declarative condition** — decouples the guard from the action
- ✅ **Skip hook** — `onConditionNotMet()` for observability or side-effects
- ✅ **Execution metrics** — track satisfaction rate over time
- ✅ **Factory methods** — create from lambdas without subclassing

---

## Basic Usage

### Subclass

```java
public class LowStockAlertBehavior extends ConditionalBehavior {

    public LowStockAlertBehavior() {
        super(Condition.agentStatus(AgentStatus.RUNNING)
                       .and(Condition.custom(agent -> inventory.isLow())),
              Duration.ofMinutes(5));
    }

    @Override
    protected void conditionalAction() {
        notificationService.sendLowStockAlert(inventory.getLowItems());
    }

    @Override
    protected void onConditionNotMet() {
        log.trace("Stock level OK, skipping alert");
    }
}
```

### Factory

```java
ConditionalBehavior alert = ConditionalBehavior.from(
    Condition.custom(agent -> inventory.isLow()),
    () -> notificationService.sendLowStockAlert(inventory.getLowItems())
);
agent.addBehavior(alert);

// With fixed interval
ConditionalBehavior cyclicAlert = ConditionalBehavior.cyclic(
    Condition.custom(agent -> inventory.isLow()),
    Duration.ofMinutes(5),
    () -> notificationService.sendLowStockAlert(inventory.getLowItems())
);
agent.addBehavior(cyclicAlert);
```

---

## Constructors

```java
// No interval — runs on every scheduler tick
protected ConditionalBehavior(Condition condition)

// Fixed cyclic interval
protected ConditionalBehavior(Condition condition, Duration interval)

// Full control
protected ConditionalBehavior(String behaviorId, Condition condition, Duration interval)
```

---

## API Reference

### Abstract method

```java
protected abstract void conditionalAction();   // called when condition is met
```

### Override hooks

```java
protected void onConditionNotMet() { }         // called when condition is NOT met
```

### Metrics

```java
long successful = behavior.getSuccessfulExecutions(); // condition met, action ran
long skipped    = behavior.getSkippedExecutions();    // condition not met
double rate     = behavior.getSatisfactionRate();     // successful / total, [0-1]
```

---

## Use Cases

- Alert only when a threshold is crossed
- Skip processing when agent is not in `RUNNING` state
- Throttle notifications based on business rules
- Guard expensive operations behind cheap pre-checks

---

## See Also

- [FSMBehavior](FSMBehavior.md) - Multi-state condition-driven transitions
- [ThrottledBehavior](ThrottledBehavior.md) - Rate-based execution control
- [Behavior Overview](README.md)

---

**Since**: Jentic 0.3.0  
**Status**: Production Ready ✅
