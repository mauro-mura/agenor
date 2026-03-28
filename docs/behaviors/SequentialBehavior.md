# SequentialBehavior - Step-by-Step Execution

## Overview

`SequentialBehavior` executes child behaviors **one after another**, waiting for each to complete before starting the next. It supports both single-pass and repeating-sequence modes.

**Since**: v0.3.0 | **Type**: `BehaviorType.SEQUENTIAL` | **Package**: `dev.jentic.runtime.behavior.composite` | **Extends**: `CompositeBehavior`

---

## ⚠️ Scheduling Behaviour — Manual Trigger Required

`SequentialBehavior` is an **on-demand** behavior. `SimpleBehaviorScheduler` does **not** auto-schedule it; calling `addBehavior()` only registers the instance so the framework can inject the agent reference. **You must call `execute()` yourself** to start the pipeline.

```java
// WRONG — pipeline is registered but never runs
addBehavior(reportPipeline);

// CORRECT — trigger explicitly after registration
addBehavior(reportPipeline);
reportPipeline.execute()
    .thenRun(() -> log.info("Pipeline completed"))
    .exceptionally(ex -> { log.error("Pipeline failed", ex); return null; });
```

This applies to all composite / control-flow behaviors: `SEQUENTIAL`, `PARALLEL`, `FSM`, `PIPELINE`, `RETRY`, `CIRCUIT_BREAKER`.

---

## Execution Modes

| Mode | `repeatSequence` | Behavior |
|------|-----------------|----------|
| **One-shot** (default) | `false` | Runs all steps once, then sets `active=false` |
| **Repeating** | `true` | Each scheduler tick advances one step; wraps around |

In **repeating** mode, the `BehaviorScheduler` calls `execute()` on each tick. Each call executes exactly one step, increments the internal index, then returns. When the last step finishes the index resets to 0 for the next tick.

In **one-shot** mode, a single `execute()` call chains all steps internally via `CompletableFuture` composition and sets `active=false` when all steps are done.

---

## Basic Usage

### One-shot sequence

```java
SequentialBehavior startup = new SequentialBehavior("startup");
startup.addChildBehavior(new ConnectDatabaseBehavior());
startup.addChildBehavior(new LoadConfigurationBehavior());
startup.addChildBehavior(new RegisterWithDirectoryBehavior());

addBehavior(startup);         // register (injects agent reference)
startup.execute();            // trigger — required, not automatic
```

### Repeating sequence (round-robin)

In repeating mode `execute()` advances one step per call. Drive it from a `CyclicBehavior` or call it manually on each tick.

```java
SequentialBehavior roundRobin = new SequentialBehavior("round-robin", true);
roundRobin.addChildBehavior(new ProcessQueueABehavior());
roundRobin.addChildBehavior(new ProcessQueueBBehavior());
roundRobin.addChildBehavior(new ProcessQueueCBehavior());

addBehavior(roundRobin);

// drive from a cyclic ticker
CyclicBehavior ticker = new CyclicBehavior("rr-ticker", Duration.ofSeconds(1),
    () -> roundRobin.execute());
addBehavior(ticker);
```

---

## Step Timeout

```java
SequentialBehavior pipeline = new SequentialBehavior(
    "pipeline",
    false,
    Duration.ofSeconds(10) // each step must complete within 10s
);
pipeline.addChildBehavior(new StepOneBehavior());
pipeline.addChildBehavior(new StepTwoBehavior());
```

---

## Constructors

```java
new SequentialBehavior(String behaviorId)
new SequentialBehavior(String behaviorId, boolean repeatSequence)
new SequentialBehavior(String behaviorId, boolean repeatSequence, Duration stepTimeout)
```

---

## API Reference

```java
// Inherited from CompositeBehavior
behavior.addChildBehavior(Behavior child);          // add a child step
List<Behavior> steps = behavior.getChildBehaviors(); // get steps (immutable)

// SequentialBehavior-specific
int current = behavior.getCurrentStep();   // current step index (0-based); equals size when one-shot is done
int total   = behavior.getTotalSteps();    // total number of child behaviors

behavior.reset();                           // restart sequence from step 0

behavior.setStepTimeout(Duration.ofSeconds(5));
Duration t  = behavior.getStepTimeout();   // null if no timeout configured
```

---

## Error Handling

Error handling differs between the two execution modes:

- **One-shot mode**: if a step throws or times out, the error is logged and execution **advances to the next step**. The sequence never aborts mid-way due to a single step failure.
- **Repeating mode**: if a step throws or times out, the error is logged and the step index is incremented (so the next scheduler tick will execute the next step). However, the `CompletableFuture` returned by the current tick completes **exceptionally** — the exception is not suppressed.
