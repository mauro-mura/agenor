# Agenor Behaviors — Overview

Behaviors are the primary mechanism for implementing agent logic. Every behavior belongs to exactly one agent and runs under the control of the `BehaviorScheduler`.

## Core Behaviors (`agenor-runtime`)

No extra dependency beyond `agenor-core` + `agenor-runtime`.

| Type | Class | Description |
|------|-------|-------------|
| `ONE_SHOT` | `OneShotBehavior` | Executes once, then stops |
| `CYCLIC` | `CyclicBehavior` | Executes repeatedly at a fixed interval |
| `EVENT_DRIVEN` | `EventDrivenBehavior` | Reacts to incoming messages on a topic |
| `WAKER` | `WakerBehavior` | Wakes up when a condition or time is met |
| `ONE_SHOT` | `ReflectionBehavior` | Generate → Critique → Revise loop; the built-in `DefaultReflectionStrategy` implementation requires `agenor-runtime-llm`, see [ReflectionBehavior](ReflectionBehavior.md) |

## Extended Behaviors (`agenor-runtime-ext`, ADR-027)

A pure multi-agent-system consumer never needs these. Add the module to use them:

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime-ext</artifactId>
</dependency>
```

| Type | Class | Description |
|------|-------|-------------|
| `SCHEDULED` | `ScheduledBehavior` | Cron-based time scheduling |
| `PARALLEL` | `ParallelBehavior` | Runs child behaviors concurrently |
| `SEQUENTIAL` | `SequentialBehavior` | Runs child behaviors one after another |
| `FSM` | `FSMBehavior` | Finite State Machine with guarded transitions |
| `CONDITIONAL` | `ConditionalBehavior` | Executes only when a `Condition` is satisfied |
| `THROTTLED` | `ThrottledBehavior` | Rate-limited execution via token bucket |
| `BATCH` | `BatchBehavior` | Collects items into batches, flushes on size or timeout |
| `RETRY` | `RetryBehavior` | Automatic retry with configurable back-off |
| `CIRCUIT_BREAKER` | `CircuitBreakerBehavior` | Fault-tolerance circuit breaker pattern |
| `PIPELINE` | `PipelineBehavior` | Multi-stage sequential data transformation |
| — | `HumanCheckpointBehavior` | Human-In-The-Loop Checkpoint, see [hitl.md](hitl.md) |

`CONDITIONAL`, `THROTTLED`, `BATCH`, `RETRY`, `SEQUENTIAL`, `PARALLEL`, and `FSM` can be declared via
`@Behavior`; without `agenor-runtime-ext` on the classpath this fails at `AgenorRuntime.start()`
with `IllegalStateException`. `SCHEDULED`, `PIPELINE`, and `CIRCUIT_BREAKER` have no annotation
shortcut and must be constructed programmatically — without the module, referencing code simply
won't compile.

## Quick Reference

### Annotation-based (recommended)

```java
@Agent("my-agent")
public class MyAgent extends BaseAgent {

    @Behavior(type = CYCLIC, interval = "30s")
    public void poll() { ... }

    @Behavior(type = ONE_SHOT)
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
