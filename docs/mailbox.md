# Agent Mailbox

Every `BaseAgent` has a mailbox, introduced in **0.27.0** by
[ADR-032](adr/ADR-032-agent-mailbox-single-inbound-path.md). It owns the agent's inbound path:
one subscription, one queue, one consumer deciding where each message goes.

You do not create or wire a mailbox. It opens when the agent starts and closes when it stops.
This page explains what it guarantees, because two of those guarantees are narrower than they
look.

## Who owns the inbound path

Before 0.27.0, an agent that also spoke the dialogue protocol subscribed to its own recipient
channel **twice** — once from `BaseAgent`, once from `DialogueCapability` — and what happened
next depended on which dispatcher was configured. The mailbox dissolves that: it registers the
agent's single persistent subscription, and both former subscribers become consumers of its
drain.

```
subscribeRecipient(agentId, mailbox::offer)      the only persistent subscription

drain, one message at a time:
  1. carries a known performative?  ->  @DialogueHandler
  2. otherwise                      ->  @AgenorMessageHandler / onDirectMessage()
```

Rule 1 uses `DialogueMessage.isDialogueMessage(Message)`, the same pure classifier the dialogue
layer already used. Routing now happens **once**, in one place, and the same way on every
transport.

An agent with no `DialogueCapability` simply has a drain whose first rule never matches. An
`Agent` implemented directly against the interface, rather than by extending `BaseAgent`, has no
mailbox at all and keeps its own subscription.

### A dialogue message no longer reaches `onDirectMessage()`

This is a behaviour change. [ADR-029](adr/ADR-029-protocol-validation-and-dialogue-message-classification.md)
D3 let dialogue traffic fall through to the direct path as well, because `BaseAgent` had no way
to know dialogue was active. The drain knows, so each message is now routed to exactly one
consumer.

If your agent overrides `onDirectMessage()` and relies on observing its own dialogue traffic
there, move that logic to a `@DialogueHandler`.

## Ordering: claim order, not completion order

The drain claims messages **in arrival order, one at a time**. That guarantee is new — in-memory
delivery previously started a virtual thread per handler per message with no sequencing, so two
messages sent a microsecond apart could be processed in either order, while the Redis transport
was already FIFO per node. The same program now behaves the same way on both.

**Handler invocation is still concurrent.** Once the drain has routed a message it hands the
handler call to a virtual thread and moves on. So:

- messages are *claimed* in the order they arrived;
- handlers may still *run* and *finish* in any order, and may overlap.

An agent must not read "my agent has a mailbox" as "my handlers run one at a time". Running
handlers on the drain thread would give full end-to-end ordering, but one slow handler would
then stall its agent's entire queue — a much larger decision than removing a transport
discrepancy, and not one this design makes for you. Guard shared state as you did before.

## Bounds and overflow

The mailbox is bounded. This is the receive side's first backpressure of any kind: before it,
nothing anywhere limited how much inbound work could pile up for one agent.

| Setting | Default | Meaning |
|---|---|---|
| `capacity` | 1024 | messages held before the policy applies |
| `overflowPolicy` | `DROP_OLDEST` | what happens to a message arriving at a full mailbox |

| Policy | Behaviour |
|---|---|
| `DROP_OLDEST` | discards the head to make room, logs a warning — the agent stays responsive to current traffic |
| `DROP_NEWEST` | discards the arrival, logs a warning — the queued backlog is preserved |
| `REJECT` | throws `MailboxOverflowException` at the sender instead of dropping silently |

Blocking the producer is deliberately not offered: the producer may be a transport consumer loop
serving an entire node, and stalling it would turn one slow agent into a node-wide outage.

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

## Scope

The mailbox is **node-local and in-memory**. It holds nothing across a restart: it is a hand-off
buffer in front of dispatch, not a store. Everything claimed is routed immediately — there is no
retention, no sweep, and deliberately **no pull API**: no `receive(selector, timeout)`, no
selector type. An agent waiting for a specific reply uses the dialogue protocol's
`request()` / `query()` / `callForProposals()` futures.

Where the transport is at-least-once — as Redis Streams consumer groups are — the same message
may be offered twice. **The mailbox does not deduplicate.** Applications needing idempotence must
still provide it.

## Observing it

`AgentMailbox` exposes `size()` and `capacity()`, and `BaseAgent.mailbox()` returns the mailbox
while the agent is started:

```java
agent.mailbox().ifPresent(box ->
    log.info("{} queued of {}", box.size(), box.capacity()));
```

Overflow is logged at WARN with the agent id and the capacity that was reached, so a mailbox
under pressure says so rather than silently losing traffic.

## See also

- [Messaging](messaging.md) — the dispatcher interfaces the mailbox subscribes through
- [Dialog Protocol](dialog-protocol.md) — the other consumer of the drain
- [ADR-032](adr/ADR-032-agent-mailbox-single-inbound-path.md) — the decision and its trade-offs
