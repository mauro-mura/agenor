# Agent Mailbox

Every `BaseAgent` has a mailbox. You never create one, wire one, or call into one — it opens
when the agent starts and closes when it stops.

This page is short on purpose. The mailbox is infrastructure, and infrastructure you have to
read about has failed at its job. What follows is only the part that reaches your code: four
things that change what your agent does.

## 1. A dialogue message no longer reaches `onDirectMessage()`

Each message goes to exactly one place: `@DialogueHandler` if it carries a performative,
`@AgenorMessageHandler` or `onDirectMessage()` otherwise.

If your agent overrides `onDirectMessage()` and watches its own dialogue traffic there, that
logic will stop running. Move it to a `@DialogueHandler`. If you filtered with
`isDialogueMessage()` inside `onDirectMessage()`, drop the filter and the branch it guarded.

*Changed in 0.27.0.*

## 2. Your handlers may overlap

Messages are **claimed** in arrival order, one at a time. They are not **handled** one at a
time: each handler runs on its own virtual thread, so handlers may overlap and finish out of
order.

Guard shared state exactly as you did before. "My agent has a mailbox" does not mean "my
handlers are serialised" — that would let one slow handler stall everything else the agent has
to do.

Up to 64 handlers run at once per agent by default. Past that the agent applies backpressure to
whatever is delivering to it.

## 3. A handler that throws is retried

If your handler throws, the message is **not** acknowledged. On a transport with redelivery —
Redis Streams — it comes back, and after `maxDeliveryAttempts` it lands in the dead-letter
stream. See [Redis Messaging](adapters/redis.md#failure-modes-and-recovery).

This is the behaviour to design for, and it has one practical consequence: **make your handlers
idempotent**, because the same message may arrive more than once.

If you want a failure contained rather than retried, catch it in the handler. That decision
belongs in your code, where it is visible, rather than in a framework that quietly swallows it.

*Changed in 0.27.0.*

## 4. An overloaded agent drops messages, visibly

The mailbox is bounded. An agent whose producers outrun it does not accumulate an unbounded
backlog any more — it drops.

| Setting | Default | Meaning |
|---|---|---|
| `capacity` | 1024 | messages held before the policy applies |
| `overflowPolicy` | `DROP_OLDEST` | what happens when a message arrives at a full mailbox |
| `maxConcurrentHandlers` | 64 | how many of this agent's handlers run at once |

| Policy | Behaviour |
|---|---|
| `DROP_OLDEST` | discards the head to make room — the agent stays responsive to current traffic |
| `DROP_NEWEST` | discards the arrival — the queued backlog is preserved |
| `REJECT` | fails the send with `MailboxOverflowException` instead of dropping |

A dropped message is **not acknowledged**, so on a transport with redelivery it is retried and
eventually dead-lettered. Overflow shows up where you already look for lost work, not only as a
warning in a log.

Override the defaults for one agent:

```java
public class BusyAgent extends BaseAgent {

    public BusyAgent() {
        super("busy-agent", "Busy Agent");
    }

    @Override
    protected MailboxConfig mailboxConfig() {
        return new MailboxConfig(4096, OverflowPolicy.REJECT);
    }
}
```

`mailboxConfig()` is read once, when the agent starts.

## Watching one

```java
agent.mailbox().ifPresent(box ->
    log.info("{} queued of {}", box.size(), box.capacity()));
```

## Two things it is not

- **Not a store.** It holds nothing across a restart and retains nothing awaiting a request.
  There is no pull API by design: an agent waiting for a specific reply uses the dialogue
  protocol's `request()` / `query()` / `callForProposals()` futures.
- **Not a deduplicator.** Where the transport is at-least-once, the same message may arrive
  twice. See point 3.

## See also

- [Messaging](messaging.md) — the dispatcher interfaces underneath
- [Dialog Protocol](dialog-protocol.md) — the other destination a message can reach
- [ADR-032](adr/ADR-032-agent-mailbox-single-inbound-path.md) — why the inbound path has one owner
- [ADR-033](adr/ADR-033-mailbox-delivery-semantics.md) — why a failed handler is retried, and what it costs
