# Jentic Behaviors — Overview

Behaviors are the primary mechanism for implementing agent logic. Every behavior belongs to exactly one agent and runs under the control of the `BehaviorScheduler`.

## Behavior Types

| Type | Class | Description |
|------|-------|-------------|
| `ONE_SHOT` | `OneShotBehavior` | Executes once, then stops |
| `CYCLIC` | `CyclicBehavior` | Executes repeatedly at a fixed interval |
| `EVENT_DRIVEN` | `EventDrivenBehavior` | Reacts to incoming messages on a topic |
| `WAKER` | `WakerBehavior` | Wakes up when a condition or time is met |
| `SCHEDULED` | `ScheduledBehavior` | Cron-based time scheduling |
| `PARALLEL` | `ParallelBehavior` | Runs child behaviors concurrently |
| `SEQUENTIAL` | `SequentialBehavior` | Runs child behaviors one after another |
| `FSM` | `FSMBehavior` | Finite State Machine with guarded transitions |
| `CUSTOM` | `ConditionalBehavior` | Executes only when a `Condition` is satisfied |
| `CUSTOM` | `ThrottledBehavior` | Rate-limited execution via token bucket |

## Advanced / Pattern Behaviors

| Behavior | Description |
|----------|-------------|
| `BatchBehavior` | Collects items into batches, flushes on size or timeout |
| `CircuitBreakerBehavior` | Fault-tolerance circuit breaker pattern |
| `PipelineBehavior` | Multi-stage sequential data transformation |
| `RetryBehavior` | Automatic retry with configurable back-off |
| `ReflectionBehavior` | Generate → Critique → Revise loop for LLM output self-improvement |
| `Human-In-The-Loop` | Human-In-The-Loop Checkpoint |

## Quick Reference

### Annotation-based (recommended)

```java
@JenticAgent("my-agent")
public class MyAgent extends BaseAgent {

    @JenticBehavior(type = CYCLIC, interval = "30s")
    public void poll() { ... }

    @JenticBehavior(type = ONE_SHOT)
    public void init() { ... }
}
```

### Programmatic

```java
agent.addBehavior(CyclicBehavior.from("poller", Duration.ofSeconds(30), this::poll));
agent.addBehavior(OneShotBehavior.from("init", this::init));
agent.addBehavior(EventDrivenBehavior.from("orders", msg -> handleOrder(msg)));
```

## Lifecycle

```
addBehavior() → [active=true] → execute() loops → stop() → [active=false]
                                                          ↑
                                              activate() resets active=true
```

`BaseBehavior.activate()` (since 0.4.0) allows a stopped behavior to be rescheduled — used internally by the agent restart mechanism.

## Documentation

- [OneShotBehavior](OneShotBehavior.md)
- [CyclicBehavior](CyclicBehavior.md)
- [EventDrivenBehavior](EventDrivenBehavior.md)
- [WakerBehavior](WakerBehavior.md)
- [ScheduledBehavior](ScheduledBehavior.md)
- [ConditionalBehavior](ConditionalBehavior.md)
- [ThrottledBehavior](ThrottledBehavior.md)
- [FSMBehavior](FSMBehavior.md)
- [ParallelBehavior](ParallelBehavior.md)
- [SequentialBehavior](SequentialBehavior.md)
- [BatchBehavior](BatchBehavior.md)
- [CircuitBreakerBehavior](CircuitBreakerBehavior.md)
- [PipelineBehavior](PipelineBehavior.md)
- [RetryBehavior](RetryBehavior.md)
- [ReflectionBehavior](ReflectionBehavior.md)
- [Human-In-The-Loop](hitl.md)
