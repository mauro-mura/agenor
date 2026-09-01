# Agenor Behaviors — Overview

A behavior is a unit of an agent's work, and its type answers one question: **when does this
run?** Every behavior belongs to exactly one agent and is driven by the `BehaviorScheduler`.

Three types answer that question, and you will not need a fourth for most agents.

| Type | Class | Runs |
|------|-------|------|
| `ONE_SHOT` | `OneShotBehavior` | once — immediately, or after `initialDelay` |
| `CYCLIC` | `CyclicBehavior` | repeatedly at `interval`, after an optional `initialDelay` |
| `FSM` | `FSMBehavior` | as a state machine that decides its own transitions (`agenor-runtime-ext`) |

## Annotation-based

```java
@Agent("my-agent")
public class MyAgent extends BaseAgent {

    @Behavior(type = ONE_SHOT)
    public void init() { ... }

    @Behavior(type = ONE_SHOT, initialDelay = "10s")
    public void announceReady() { ... }

    @Behavior(type = CYCLIC, interval = "30s", initialDelay = "5s")
    public void poll() { ... }
}
```

`FSM` is declared the same way and requires `agenor-runtime-ext` on the classpath; without it,
startup fails at `AgenorRuntime.start()` with `IllegalStateException`.

## Programmatic

```java
agent.addBehavior(OneShotBehavior.from("init", this::init));
agent.addBehavior(CyclicBehavior.from("poller", Duration.ofSeconds(30), this::poll));
```

## Composing behaviors

When work has several steps, compose rather than reach for a new type. `SequentialBehavior` runs
children in order and `ParallelBehavior` runs them together; both live in `agenor-runtime-ext`,
and both tell the scheduler how they want to be driven through
[`SchedulingHint`](../architecture.md) rather than through their type. Build a composite and hand
it to `addBehavior()` — there is no annotation for one, because an annotation cannot carry a list
of children.

```java
agent.addBehavior(new SequentialBehavior("fulfil", List.of(reserve, charge, ship)));
```

`HumanCheckpointBehavior` pauses a flow for a human decision — see
[Human-In-The-Loop](hitl.md).

## What is deliberately not here

Retrying, rate limiting, batching, circuit breaking and cron scheduling are **not** behavior
types. They wrap a call or shape a stream rather than answering when work runs, and each has a
better home:

| Concern | Where it belongs |
|---|---|
| retry, circuit breaking, outbound rate limiting | a resilience library (Resilience4j, Failsafe) around the call |
| inbound throttling and batching | the agent mailbox, which owns the receive path |
| cron | whatever already owns your schedule, or `CYCLIC` for a fixed cadence |
| gating on a condition | a check where the work happens |
| LLM reasoning patterns such as reflection | a strategy on `LLMAgent` — see `setReflectionStrategy` |

The annotation constants for these were deprecated in 0.28.0 and **removed in 0.30.0**. The
changelog for that release names the replacement for each one.

## Lifecycle

```
addBehavior() → [active=true] → execute() loops → stop() → [active=false]
                                                          ↑
                                              activate() resets active=true
```

`BaseBehavior.activate()` (since 0.4.0) allows a stopped behavior to be rescheduled — used
internally by the agent restart mechanism.

## Pages

- [OneShotBehavior](OneShotBehavior.md)
- [CyclicBehavior](CyclicBehavior.md)
- [FSMBehavior](FSMBehavior.md)
- [SequentialBehavior](SequentialBehavior.md)
- [Human-In-The-Loop](hitl.md)
