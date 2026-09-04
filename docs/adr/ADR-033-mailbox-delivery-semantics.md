# ADR-033: Mailbox Delivery Semantics — the Mailbox Carries the Chain, It Does Not End It

**Status**: Accepted
**Date**: 2026-08-24
**Authors**: Project Team
**References**: ADR-032 (Agent Mailbox — a Single Inbound Path per Agent), ADR-021 (Redis
MessageTransport), ADR-004 (Progressive Complexity Strategy), ADR-002 (Interface-First
Architecture)

---

## Context

ADR-032 gave every `BaseAgent` a mailbox that owns its inbound path. Its D-3 placed "any
delivery semantics beyond what the transport provides" out of scope. That was read as *the
mailbox adds nothing*. What it actually did was **terminate** what the transport already
provided, and the trade was never made explicitly.

The chain the Redis adapter documents, and implements in `ConsumerLoop.processMessage`:

```java
handler.handle(msg).join();                       // throws -> no XACK
client.xack(streamKey, consumerGroup, entryId);   // only on success
```

An entry is acknowledged only after its handler completes. On failure it stays in the Pending
Entries List, is redelivered after `pendingEntriesTimeoutMs`, and after `maxDeliveryAttempts`
moves to the dead-letter stream. `docs/adapters/redis.md` documents exactly this.

Since ADR-032 the handler that loop is given is `MessageHandler.sync(box::offer)`, which
returns as soon as the message is queued. Four consequences, all shipped:

1. **Acknowledgement happens at enqueue, not after processing.** Every message is acked the
   moment it enters the queue.
2. **A failing application handler is invisible to the transport.** `DefaultAgentMailbox.route()`
   dispatches onto a detached virtual thread where the exception is logged and nothing else.
   No redelivery, no dead-letter.
3. **`DROP_OLDEST` — the default — loses messages silently.** `offer` returned `true` even when
   it discarded a message, so the discarded message had already been acknowledged. A WARN in a
   log is the only trace.
4. **`stop()` discarded acknowledged work.** It cleared the queue without waiting for anything.

Only `OverflowPolicy.REJECT` preserved the chain, and it is not the default.

Two further defects share the same mechanism, which is why they are decided here and not
separately:

- **In-flight handlers were unbounded.** `route()` started a virtual thread per message and
  returned, so the drain never blocked. Under the actual overload case — slow handlers — the
  queue stays near-empty while thread count and heap grow without limit. The 1024-message bound
  protected only against bursts exceeding the *spawn* rate, which is microseconds. Describing it
  as "the message path's first backpressure" overstated what was delivered: it bounded a queue
  that was not the bottleneck.
- **`stop()` had no quiescence.** It interrupted the drain and cleared the queue but did not
  wait for handler threads already started.

And then, underneath all of it, a third thing — found only by running the test this ADR is
written around, which is the point of writing the test first:

> **`ConsumerLoop` never reclaimed pending entries.** Its read loop issued `XREADGROUP` with a
> `lastConsumed` offset and nothing else. That returns entries nobody has read yet, so an entry
> left unacknowledged sat in the Pending Entries List forever. `XAUTOCLAIM` was called nowhere
> in the adapter and `pendingEntriesTimeoutMs` was read by nothing — a configuration property
> with a default, a documented table row and a Spring Boot key, consumed by no code.

The consequence is larger than the one this ADR set out to fix: **redelivery on Redis had never
worked**, before ADR-032 as much as after. `maxDeliveryAttempts` and the dead-letter hop in
`processMessage` were unreachable, because reaching them requires seeing the same entry twice
and nothing ever delivered an entry twice. The mailbox did terminate the chain, exactly as
described above — but repairing only the mailbox would have restored a chain whose far end was
missing, and the test would still have failed.

`AgentMailbox` was introduced in 0.27.0, which is unreleased. Nothing here breaks a shipped
API.

## Decision

**The mailbox carries the transport's delivery chain through to the handler.**

### D-1 — `offer` reports the outcome of processing, not of queueing

```java
CompletableFuture<Void> offer(Message message);
```

The returned future completes when the handler for that message completes, and completes
exceptionally when the handler throws. `BaseAgent` hands `box::offer` to
`subscribeRecipient` directly, so `ConsumerLoop` acknowledges after processing, exactly as it
did before ADR-032.

A dropped message completes the future **exceptionally** with `MailboxOverflowException`. It
is not acknowledged, so the transport redelivers it and it eventually reaches the dead-letter
stream. Overflow becomes visible where operators already look, instead of in a WARN nobody
reads. This applies to the evicted message under `DROP_OLDEST` as much as to the rejected one
under `DROP_NEWEST`.

`OverflowPolicy.REJECT` still throws `MailboxOverflowException` synchronously. The caller is
the transport's consumer loop, which catches it and declines to acknowledge — the same
outcome by a shorter route.

### D-2 — In-flight handlers are bounded, and that is the real backpressure

The drain acquires a permit before dispatching a handler and releases it when the handler
completes. With the permits exhausted the drain blocks, the queue fills, and only then does the
overflow policy apply. This is what makes the queue bound meaningful: the queue is now
downstream of a real constraint instead of a buffer in front of an unbounded one.

`MailboxConfig.maxConcurrentHandlers` defaults to 64. High enough that an ordinary agent never
meets it; low enough that one slow handler cannot spawn threads without limit.

This does **not** reintroduce head-of-line blocking. Up to 64 handlers still run concurrently
and may still complete out of order. ADR-032's guarantee is unchanged: **claim order, not
completion order**.

### D-3 — `stop()` waits for in-flight handlers

`stop()` interrupts the drain, then waits — up to five seconds — for every in-flight handler
to finish before clearing what remains. Anything still queued at that point is completed
exceptionally, so the transport does not acknowledge it and redelivers it to whoever holds the
agent next.

### D-4 — `offer` retries before it evicts

`offer` synchronised on the external monitor of an `ArrayBlockingQueue`, which locks internally
with its own `ReentrantLock`. Under `DROP_OLDEST` a concurrent `take()` between the failed
`offer` and the `poll` discarded a message that did not need discarding. `offer` now retries
once before evicting.

### D-5 — An annotated handler's failure is no longer swallowed

The chain was terminated in **two** places, not one. Even with D-1 in place,
`BaseAgent.handleDirectMessage` caught whatever an `@AgenorMessageHandler` threw, logged it,
and returned normally — so the mailbox saw a success and the transport acknowledged. The
headline case, an annotated handler that throws, stayed broken.

It now runs every matching handler, as before, and rethrows the first failure with the rest
attached as suppressed exceptions. `MessageHandler.sync` already turns a throw into a failed
future, so nothing else had to change for the failure to reach `offer`'s outcome.

This is a behaviour change for agents that relied on a throwing handler being contained: such a
message is now redelivered, and eventually dead-lettered, instead of disappearing. An agent
that wants a failure contained should catch it in the handler, which is where the decision
belongs and where it is visible.

### D-6 — The consumer loop reclaims pending entries

After each read pass, and no more often than `pendingEntriesTimeoutMs`, the loop issues
`XAUTOCLAIM` from `0-0` with `minIdleTime = pendingEntriesTimeoutMs` and puts whatever it
reclaims back through `processMessage` — which is where the delivery-attempt count and the
dead-letter hop already lived, waiting for a caller.

Reclaiming from `0-0` covers entries abandoned by a consumer that died as well as ones this
consumer failed to handle, so a restarted node picks up its own pending work. That is what
`docs/adapters/redis.md` already claimed under *Consumer loop crash / JVM restart*.

This is the change that makes the rest observable. D-1 and D-5 ensure a failure reaches the
transport; without D-6 the transport had nothing to do with it.

## Options considered

1. **Accept the change: delivery is best-effort past the mailbox boundary** (rejected). Cheaper
   — rewrite `docs/adapters/redis.md`, state it in the contract, done. Rejected because it
   silently downgrades the guarantee exactly where an application can act on it. ADR-004
   promises that moving from in-memory to a durable backend needs no code change; under this
   option you move to Redis, pay for durability, and agent handlers do not get it. A guarantee
   that stops at the last hop before the application is not a guarantee the application can use.

2. **Run handlers on the drain thread** (rejected). Gives full end-to-end ordering and makes
   acknowledgement trivially correct, at the price ADR-032 already refused: one slow handler
   stalls its agent's entire queue.

3. **Carry the chain, bound the concurrency** (chosen). Keeps ADR-032's claim-order guarantee
   and its refusal of head-of-line blocking, restores the transport's chain, and turns the
   queue bound into real backpressure by putting a genuine constraint behind it.

## Consequences

**Good.** At-least-once survives to the handler on transports that offer it — through all
three places that used to end it, one of which meant Redis redelivery had never functioned at
all. A handler that
throws is redelivered and eventually dead-lettered, which is what `docs/adapters/redis.md` has
promised all along. Overflow is visible in the dead-letter stream. Handler concurrency is
bounded per agent. Shutdown no longer discards acknowledged work.

**Costs.** A slow handler now applies backpressure to its transport's consumer loop, which on
Redis serves an entire node — a single pathological agent can slow delivery for its neighbours.
That is the honest shape of the trade: the alternative was losing its messages quietly. Agents
whose handlers may block indefinitely should bound them, not the mailbox.

**Unchanged.** The in-memory dispatcher dispatches each handler on its own virtual thread and
never inspects the returned future, so `sendTo` does not block on the recipient's handler and
in-memory behaviour is exactly as before. It has no redelivery to preserve.

**Not addressed here.** The mailbox still belongs to `BaseAgent` rather than to the agent
contract, so an agent implementing `Agent` directly gets none of this. That limitation is
recorded in ADR-032 and is worth fixing when interceptors or node-to-node security make the
bypass real, not before.

---

## Amendment (2026-09-04) — a failed message has somewhere to go on both transports

The **Unchanged** clause above is reversed in part. Its two halves were both true; the
inference between them was not.

It read: "the in-memory dispatcher ... never inspects the returned future, so `sendTo` does not
block on the recipient's handler and in-memory behaviour is exactly as before. It has no
redelivery to preserve." That a transport cannot *retry* a message says nothing about whether
the framework can *record* having lost it, and this ADR let the first fact settle the second.
The result was that the outcome future D-1 introduced had exactly one downstream consumer,
`ConsumerLoop.processMessage`. `InMemoryMessageDispatcher` joined the identical future in three
places and answered every failure with a `log.error`.

The consequence is the one this project has a rule against. The same agent, the same mailbox
and the same throwing handler produced a dead letter on Redis and a log line in memory — so a
developer verifying against `InMemoryMessageDispatcher` was not verifying the behaviour they
would get in production. The case could not even be *stated* in
`MessageDispatcherContractTests`, because in memory there was nothing to assert against.

**What is added.** `DeadLetterQueue` in `agenor-core` — `record(DeadLetter)` for a message the
framework has given up on, `recent(limit)` to read them back. Reading is the half that matters:
a queue that could only be written to would be a logger with more steps, and the Redis DLQ
stream, which had existed since ADR-021, was exactly that. Two implementations ship with it:
`InMemoryDeadLetterQueue`, a bounded buffer wired into the three dispatcher paths that used to
end in `log.error`, and `RedisDeadLetterQueue` over the `<stream>:dlq` stream, whose entries
now also carry the reason, the attempt count and the time. Three cases in
`MessageDispatcherContractTests` hold both transports to the same answer, and the web console
has a view that works on either because it reads the port rather than the sniffer.

**Still unchanged, and deliberately.** Redelivery. The in-memory transport retries nothing and
this adds no retry — `DeadLetter.attempts` reads 1 there and the exhausted `maxDeliveryAttempts`
on Redis, which is a fact about the transport rather than a placeholder. `sendTo` still does not
block on the recipient's handler in memory, so its future still completes where Redis fails it;
the contract suite looks away from that difference on purpose, because putting it in a contract
would make the asymmetry official.

**Deliberately not here.** Supervision. No restart policy, no backoff, no escalation to a
parent. The failure becomes *observable*, not *handled*, and the rest waits for someone who
needs it.

This is an amendment rather than ADR-034 because it is the observational tail of the decision
this ADR already took. A new document would have left the clause above standing beside one that
contradicted it — two places to look, one of them wrong.
