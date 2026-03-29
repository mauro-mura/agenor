# ParallelBehavior — Concurrent Child Execution

**Since**: v0.3.0 | **Updated**: v0.14.0  
**Type**: `BehaviorType.PARALLEL` | **Package**: `dev.jentic.runtime.behavior.composite`

## Overview

`ParallelBehavior` executes multiple child behaviors **concurrently** using virtual threads.
Calling `agent.addBehavior()` is sufficient to start it — no manual `execute()` call required.

It supports four completion strategies to control when the composite itself is considered done.

## Completion Strategies

| Strategy | Completes when… |
|----------|----------------|
| `ALL` *(default)* | All children have finished |
| `ANY` | At least one child has finished (others continue) |
| `FIRST` | The first child finishes |
| `N_OF_M` | At least N children have finished successfully |

## Basic Usage

```java
ParallelBehavior parallel = new ParallelBehavior("data-fetch", CompletionStrategy.ALL);
parallel.addChildBehavior(new FetchUsersBehavior());
parallel.addChildBehavior(new FetchProductsBehavior());
parallel.addChildBehavior(new FetchOrdersBehavior());

agent.addBehavior(parallel); // registers and triggers automatically — no extra call needed
```

## Completion Strategy Examples

### ANY — proceed as soon as one source responds

```java
ParallelBehavior fastest = new ParallelBehavior("fastest-cache", CompletionStrategy.ANY);
fastest.addChildBehavior(new ReadFromLocalCacheBehavior());
fastest.addChildBehavior(new ReadFromRemoteCacheBehavior());
fastest.addChildBehavior(new ReadFromDatabaseBehavior());

agent.addBehavior(fastest);
```

### FIRST — race, stop on first winner

```java
ParallelBehavior race = new ParallelBehavior("geo-lookup", CompletionStrategy.FIRST);
race.addChildBehavior(new GeoLookupProvider1Behavior());
race.addChildBehavior(new GeoLookupProvider2Behavior());

agent.addBehavior(race);
```

### N_OF_M — quorum

```java
// Require 2 of 3 replicas to confirm
ParallelBehavior quorum = new ParallelBehavior("write-quorum", CompletionStrategy.N_OF_M, 2);
quorum.addChildBehavior(new WriteToReplica1Behavior());
quorum.addChildBehavior(new WriteToReplica2Behavior());
quorum.addChildBehavior(new WriteToReplica3Behavior());

agent.addBehavior(quorum);
```

## Child Timeouts

```java
ParallelBehavior parallel = new ParallelBehavior(
    "bounded-parallel",
    CompletionStrategy.ALL,
    0,
    Duration.ofSeconds(5) // each child times out after 5s
);
parallel.addChildBehavior(new SlowBehavior());
parallel.addChildBehavior(new FastBehavior());

agent.addBehavior(parallel);
```

## Constructors

```java
new ParallelBehavior(String behaviorId)
new ParallelBehavior(String behaviorId, CompletionStrategy strategy)
new ParallelBehavior(String behaviorId, CompletionStrategy strategy, int requiredCompletions)
new ParallelBehavior(String behaviorId, CompletionStrategy strategy,
                     int requiredCompletions, Duration childTimeout)
```

`requiredCompletions` is only meaningful with `N_OF_M`.

## API Reference

```java
int  getCompletedCount()      // successful child completions in the last execute()
int  getFinishedCount()       // all finished (success + failure)

CompletionStrategy getStrategy()

Duration getChildTimeout()
void     setChildTimeout(Duration timeout)
```

## How It Works — SchedulingHint

`ParallelBehavior` overrides `getSchedulingHint()` to return `SchedulingHint.ONCE`.
`SimpleBehaviorScheduler` reads this and calls `scheduleOneShot()`, which fires `execute()`
immediately after registration. After all children finish (per the completion strategy),
the behavior becomes inactive.

## See Also

- [`SequentialBehavior`](SequentialBehavior.md) — ordered step-by-step execution, also auto-scheduled