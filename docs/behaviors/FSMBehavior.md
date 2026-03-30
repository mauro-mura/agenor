# FSMBehavior - Finite State Machine

## Overview

`FSMBehavior` models agent logic as a **Finite State Machine**: each state maps to a child `Behavior`, and guarded transitions move the FSM from one state to the next based on runtime conditions.

**Since**: v0.3.0 | **Type**: `BehaviorType.FSM` | **Package**: `dev.jentic.runtime.behavior.composite` | **Extends**: `CompositeBehavior`

> **Scheduling note**: `FSMBehavior` has type `BehaviorType.FSM`, which is **not scheduled automatically** by the `SimpleBehaviorScheduler`. You must drive it explicitly — see [Basic Usage](#basic-usage) below.

---

## Key Features

- ✅ **State-behavior mapping** — each state executes its own `Behavior`
- ✅ **Guarded transitions** — `Predicate<FSMBehavior>` controls when to switch states
- ✅ **Transition actions** — optional `Behavior` executed during a transition
- ✅ **State timeout** — abort a state if it exceeds a configured duration
- ✅ **Force transitions** — `transitionTo()` bypasses guards
- ✅ **Fluent builder** — construct complex FSMs declaratively

---

## Concepts

```
States:       [IDLE] ──► [PROCESSING] ──► [DONE]
                │                             │
                └─────────────────────────────┘ (reset → IDLE)

Transitions:  guarded by Predicate<FSMBehavior>
              optional: execute a Behavior during transition
```

On each call to `execute()`:
1. The `Behavior` bound to `currentState` is executed.
2. Transitions from `currentState` are evaluated in insertion order.
3. The first matching transition fires, changing `currentState`.

---

## Basic Usage

`FSMBehavior` is **not scheduled automatically** by `SimpleBehaviorScheduler` — its type `BehaviorType.FSM` is not in the set of types the scheduler drives. You must tick it explicitly using a `CyclicBehavior` driver that calls `execute()` at a fixed interval.

```java
FSMBehavior fsm = FSMBehavior.builder("order-fsm", "IDLE")
    .state("IDLE",       new WaitForOrderBehavior())
    .state("PROCESSING", new ProcessOrderBehavior())
    .state("DONE",       new NotifyCompletionBehavior())
    .transition("IDLE",       "PROCESSING", fsm -> orderQueue.hasItems())
    .transition("PROCESSING", "DONE",       fsm -> orderProcessor.isComplete())
    .transition("DONE",       "IDLE",       fsm -> true) // always reset
    .build();

// FSMBehavior is on-demand: a CyclicBehavior driver ticks it at regular intervals
CyclicBehavior driver = CyclicBehavior.from(
        "order-fsm-driver",
        Duration.ofMillis(300),
        () -> fsm.execute());

agent.addBehavior(driver);
```

> **Common mistake**: calling `agent.addBehavior(fsm)` directly — the FSM is registered but never executed. Always add the driver, not the FSM itself.

---

## Transitions with Actions

```java
FSMBehavior fsm = FSMBehavior.builder("payment-fsm", "PENDING")
    .state("PENDING",   new AwaitPaymentBehavior())
    .state("CONFIRMED", new FulfillOrderBehavior())
    .state("FAILED",    new RefundBehavior())
    .transition("PENDING", "CONFIRMED",
                fsm -> paymentService.isConfirmed(),
                "payment-confirm",
                new LogTransitionBehavior("CONFIRMED"))
    .transition("PENDING", "FAILED",
                fsm -> paymentService.hasFailed(),
                "payment-fail",
                new LogTransitionBehavior("FAILED"))
    .build();
```

---

## State Timeout

```java
// Abort a state if it takes longer than 5 seconds
FSMBehavior fsm = FSMBehavior.builder("timed-fsm", "INIT", Duration.ofSeconds(5))
    .state("INIT",    new InitBehavior())
    .state("RUNNING", new RunBehavior())
    .transition("INIT", "RUNNING", fsm -> initialized)
    .build();
```

---

## Constructors

```java
// Direct construction
new FSMBehavior(String behaviorId, String initialState)
new FSMBehavior(String behaviorId, String initialState, Duration stateTimeout)
```

---

## API Reference

### Builder

```java
FSMBehavior.builder(String behaviorId, String initialState)
FSMBehavior.builder(String behaviorId, String initialState, Duration stateTimeout)

builder
    .state(String name, Behavior behavior)
    .transition(String from, String to, Predicate<FSMBehavior> condition)
    .transition(String from, String to, Predicate<FSMBehavior> condition,
                String transitionName, Behavior transitionAction)
    .stateTimeout(Duration timeout)
    .build()            // throws IllegalStateException if initialState is not defined
```

### Runtime Control

```java
String state  = fsm.getCurrentState();
boolean inIt  = fsm.isInState("PROCESSING");
Set<String> s = fsm.getStateNames();

fsm.transitionTo("IDLE");       // force transition, ignores guards
fsm.reset();                    // return to initialState
fsm.setStateTimeout(Duration);  // change timeout at runtime
Duration t    = fsm.getStateTimeout();
```

---

## Scheduling Reference

| Type | Scheduled by SimpleBehaviorScheduler | Notes |
|---|---|---|
| `ONE_SHOT` | ✅ Yes | Executed once immediately |
| `CYCLIC` | ✅ Yes | Repeated at fixed interval |
| `EVENT_DRIVEN` | ❌ No | Registered, responds to messages |
| `WAKER` | ✅ Yes | Checks wake condition periodically |
| `FSM` | ❌ No | On-demand — requires explicit `CyclicBehavior` driver |

---

## Error Handling

- If `currentState` is not found in the state map, the FSM logs an error, sets `active=false`, and returns a failed `CompletableFuture`.
- Exceptions thrown by state behaviors are caught and logged; the FSM continues.
- Timeout causes a `TimeoutException` which is logged as a warning; the FSM continues to evaluate transitions.

---

## Use Cases

- Multi-step order processing workflows
- Connection lifecycle management (CONNECTING → CONNECTED → DISCONNECTED)
- Game agent AI (PATROL → CHASE → ATTACK → RETREAT)
- Protocol state machines

---

## See Also

- [SequentialBehavior](https://www.jentic.dev/docs/behaviors/SequentialBehavior/) - Linear step-by-step execution
- [ConditionalBehavior](https://www.jentic.dev/docs/behaviors/ConditionalBehavior/) - Single condition gate
- [ParallelBehavior](https://www.jentic.dev/docs/behaviors/ParallelBehavior/) - Concurrent child execution
- [Behavior Overview](https://www.jentic.dev/docs/behaviors/)
