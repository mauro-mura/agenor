# SequentialBehavior — Step-by-Step Execution

**Since**: v0.3.0 | **Updated**: v0.14.0  
**Type**: `BehaviorType.SEQUENTIAL` | **Package**: `dev.agenor.runtime.behavior.composite`

## Overview

`SequentialBehavior` executes child behaviors **one after another**, waiting for each to complete
before starting the next. Calling `agent.addBehavior()` is sufficient to start it — no manual
`execute()` call required.

The execution mode is determined by whether an `interval` is provided:

| Mode | Constructor | `SchedulingHint` | Behavior |
|------|-------------|-----------------|----------|
| **One-shot** | no `interval` | `ONCE` | Runs all steps once, then becomes inactive |
| **Repeating** | `interval` provided | `CYCLIC` | Each scheduler tick advances one step; wraps around |

## One-shot sequence

```java
SequentialBehavior startup = new SequentialBehavior("startup");
startup.addChildBehavior(new ConnectDatabaseBehavior());
        startup.addChildBehavior(new LoadConfigurationBehavior());
        startup.addChildBehavior(new RegisterWithDirectoryBehavior());

        agent.addBehavior(startup); // registers and triggers automatically
```

## Repeating sequence (round-robin)

Each scheduler tick advances exactly one step. Use `OneShotBehavior` children — their interval
is irrelevant, the parent controls the tick rate.

```java
// Advance one queue every 200ms, cycling through all children
SequentialBehavior roundRobin = new SequentialBehavior("queue-poller", Duration.ofMillis(200));
roundRobin.addChildBehavior(OneShotBehavior.from("poll-north",   this::pollNorth));
        roundRobin.addChildBehavior(OneShotBehavior.from("poll-central", this::pollCentral));
        roundRobin.addChildBehavior(OneShotBehavior.from("poll-south",   this::pollSouth));

        agent.addBehavior(roundRobin); // registers and schedules cyclically
```

## One-shot with step timeout

```java
SequentialBehavior pipeline = new SequentialBehavior("pipeline")
        .withStepTimeout(Duration.ofSeconds(10));
pipeline.addChildBehavior(new StepOneBehavior());
        pipeline.addChildBehavior(new StepTwoBehavior());

        agent.addBehavior(pipeline);
```

## Repeating with step timeout

```java
SequentialBehavior roundRobin = new SequentialBehavior("poller", Duration.ofMillis(200))
        .withStepTimeout(Duration.ofSeconds(5));
roundRobin.addChildBehavior(OneShotBehavior.from("poll-north", this::pollNorth));
roundRobin.addChildBehavior(OneShotBehavior.from("poll-south", this::pollSouth));

agent.addBehavior(roundRobin);
```

## Constructors

```java
// One-shot (most common)
new SequentialBehavior(String behaviorId)

// Repeating — interval drives the scheduler tick rate
new SequentialBehavior(String behaviorId, Duration interval)
```

## Step timeout

Use the fluent `withStepTimeout()` method on either constructor:

```java
new SequentialBehavior("id").withStepTimeout(Duration.ofSeconds(10))
new SequentialBehavior("id", Duration.ofMillis(200)).withStepTimeout(Duration.ofSeconds(5))
```

## API Reference

```java
int      getCurrentStep()        // zero-based index of the next step to execute
int      getTotalSteps()         // total number of child behaviors
boolean  isRepeating()           // true if constructed with a non-null interval
void     reset()                 // restart sequence from step 0

void     setStepTimeout(Duration t)
Duration getStepTimeout()
```

## Error Handling

If a step fails or times out, the error is logged and execution advances to the next step.
The sequence never aborts mid-way due to a single-step failure.

## How It Works — SchedulingHint

`SequentialBehavior` derives its scheduling mode from the `interval` field:

- `interval == null` → `SchedulingHint.ONCE` → scheduler calls `scheduleOneShot()`
- `interval != null` → `SchedulingHint.CYCLIC` → scheduler calls `scheduleCyclic()`

`SimpleBehaviorScheduler.scheduleComposite()` reads this hint and dispatches accordingly.
Other composites that are genuinely on-demand (`FSM`, `RETRY`, `CIRCUIT_BREAKER`, `PIPELINE`)
return `SchedulingHint.ON_DEMAND` and remain unscheduled.

## See Also

- [`ParallelBehavior`](ParallelBehavior.md) — concurrent fan-out, also auto-scheduled as ONCE
- [`CyclicBehavior`](CyclicBehavior.md) — simpler periodic execution without child composition
