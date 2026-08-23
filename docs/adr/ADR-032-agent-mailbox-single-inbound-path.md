# ADR-032: Agent Mailbox — a Single Inbound Path per Agent

**Status**: Accepted
**Date**: 2026-08-18
**Last Modified**: 2026-08-23 (accepted; see the scope note in D-1, and D-1/D-5 on who
owns a mailbox and what the contract carries)
**Authors**: Project Team
**References**: ADR-029 (Protocol Validation and Dialogue Message Classification),
ADR-027 (Minimal Runtime Core — LLM / Generic-MAS Module Split), ADR-021 (Redis
MessageTransport), ADR-019 (OpenTelemetry Instrumentation), ADR-009 (Agent Dialogue
Protocol), ADR-004 (Progressive Complexity Strategy), ADR-002 (Interface-First
Architecture)

---

## Context

An agent's inbound message path has no owner. Two components subscribe to the same
recipient channel independently, and what happens next depends on which dispatcher is
wired in. Three consequences follow. The second of them was a live defect in shipped
code; it was fixed directly and released in 0.26.0, but the fix left the structure that
produced it standing — see §2.

### 1. Every agent registers two rival subscriptions

A `BaseAgent` that owns a `DialogueCapability` subscribes to its own recipient channel
**twice**:

```java
// BaseAgent.autoSubscribeDirectMessages()  — BaseAgent.java:504
directMessageSubscription = messageDispatcher.subscribeRecipient(
        getAgentId(), MessageHandler.sync(this::handleDirectMessage));

// DialogueCapability.doInitialize()        — DialogueCapability.java:199
subscription = dispatcher.subscribeRecipient(
        agent.getAgentId(), MessageHandler.sync(this::handleIncomingMessage));
```

ADR-029 already identified this double subscription as the root cause of a resource leak
and fixed the symptom. The structure itself was left in place.

### 2. On Redis, the second subscription silently destroys the first

`InMemoryMessageDispatcher` keeps a `List` per agent id and fires both handlers.
`RedisMessageDispatcher` keeps a `Map`:

```java
private final Map<String, MessageHandler> directHandlers = new ConcurrentHashMap<>();  // :57
...
directHandlers.put(localAgentId, handler);                                             // :180
```

`put`, not append. `DialogueCapability` registers from a start hook, and start hooks run
*after* `autoSubscribeDirectMessages()` (`BaseAgent.start():205` then `:217`), so the
dialogue handler **replaces** `BaseAgent.handleDirectMessage`.

**`@AgenorMessageHandler` direct delivery was dead on the Redis transport.** Worse,
`Subscription.of(localAgentId, () -> directHandlers.remove(localAgentId))` (`:183`) meant
either component's shutdown revoked whichever handler currently held the slot.

This was a live defect in shipped code, so it was **fixed independently of this ADR**
rather than waiting for it: `directHandlers` now holds a list per agent and delivers to
every handler, matching `InMemoryMessageDispatcher`. That restores the documented contract
without changing the structure — two components still subscribe independently, and the
dispatchers still agree only by convention. This ADR removes the structure.

It is the same class of defect as the four found during ADR-028 Part 1 — a seam between
two components that are each individually well tested.

### 3. Ordering, bounds and observability all differ by transport

`InMemoryMessageDispatcher` holds one static executor (`:45`) and starts a fresh virtual
thread **per handler per message**:

```java
for (var handler : handlers) {
    Thread.startVirtualThread(() -> { ... handler.handle(msg).join(); ... });   // :292
}
```

Two messages sent to the same agent a microsecond apart may execute in either order and
concurrently. The codebase treats this as a hazard, not a feature —
`DefaultConversation.java:37-38` explains its lock exists because "the dispatcher delivers
each message on its own virtual thread with no per-recipient serialisation".

Redis does not behave this way: `ConsumerLoop` processes a node stream serially on one
virtual thread, giving per-node FIFO. Observability splits the same way — the in-memory
dispatcher emits `message.send` only (`:98`, `:146`) and nothing on delivery, while the
Redis adapter emits `message.receive` (`ConsumerLoop.java:145`).

Nothing on the receive side is bounded — no `ArrayBlockingQueue`, `Semaphore` or bounded
executor exists anywhere in the repository — and nothing can be interposed between
`deliverToReceiver` and the user handler.

**The same program therefore has different ordering, different backpressure and different
tracing depending on which dispatcher is configured**, which is exactly the silent break
ADR-004 promises cannot happen.

### What this ADR is not

An earlier draft paired this with a JADE-style `receive(selector, timeout)` — a pull API
letting an agent ask for a specific message. That was evaluated and **rejected**; see
*Alternatives considered*. This ADR is only about who owns the inbound path.

---

## Decision

### D-1 — One mailbox per agent, owning the only subscription

A `BaseAgent` gets a mailbox. It registers the **single** persistent `subscribeRecipient`
for that agent; `BaseAgent`'s dispatch and `DialogueCapability` become *consumers of its
drain* rather than rival subscribers.

The mailbox belongs to `BaseAgent` and not to the `Agent` interface, because the interface
declares no message handling at all — only identity, lifecycle and behaviours. There is no
second lane for a drain to route non-dialogue traffic into, so an agent implemented directly
against `Agent` has no mailbox and keeps whatever inbound path it arranges for itself. For
that case `DialogueCapability` still takes out its own subscription, which is also why its
classifier guard stays. This matches where the rest of an agent's machinery already lives:
the dispatcher, directory, scheduler, memory store, descriptor and annotated direct-message
handlers are all `BaseAgent`'s.

```
subscribeRecipient(agentId, mailbox::offer)      // the only subscription

single-consumer drain, per agent:
  1. DialogueMessage.isDialogueMessage(msg)?   -> dialogue path
  2. otherwise                                 -> @AgenorMessageHandler / onDirectMessage
```

The drain is the inbound path's owner: exactly one component decides where a message
goes, and it decides the same way on every transport.

Rule 1 reuses ADR-029's `DialogueMessage.isDialogueMessage(Message)` verbatim. That
function was specified as pure and dependency-free precisely so a router could use it;
this is that router. No new classification logic is introduced.

The mailbox is not optional and has no modes. An agent without a `DialogueCapability`
simply has a drain whose rule 1 never matches.

#### Scope note (2026-08-23, at acceptance)

"The only subscription" means the only **persistent, framework-owned** one. Reconnaissance
before implementation found two further call sites that subscribe to a live agent's own
recipient channel for the duration of a single request and then unsubscribe:

- `AgenorA2AAdapter.sendInternal()` subscribes to `replyTo`, which for an internal agent is
  that agent's own id, to await the final reply (ADR-026).
- `OrderOrchestratorAgent.requestReply()` in the examples subscribes to `getAgentId()` and
  correlates on `correlationId`.

Both are correlated request/reply helpers, not selective receive, and both keep working:
the dispatchers fan out to every registered handler per recipient id. They are therefore
**not** required to move behind the mailbox. Routing them through the drain would mean
giving the mailbox a correlation-aware claim API — the pull API this ADR rejects on
evidence — so the invariant an implementation must hold, and a test must assert, is *one
persistent subscription per agent after start*, not *one subscription ever*.

### D-2 — Bounded, with an explicit overflow policy

The mailbox has a finite capacity and a configured policy on overflow.

| Setting | Default | Notes |
|---|---|---|
| `capacity` | 1024 messages | per agent |
| `overflowPolicy` | `DROP_OLDEST` | also `DROP_NEWEST`, `REJECT` |

`DROP_OLDEST` logs at WARN: it fails visibly and keeps the agent responsive to current
traffic. `REJECT` fails the sender's future with a dedicated exception rather than
dropping silently. Blocking the producer is **not** offered — the producer may be a Redis
consumer loop whose stall would back up an entire node's stream.

**There is no retention window and no reaper.** The drain routes every message it takes;
nothing is ever left in the queue awaiting a future claim. The queue is a hand-off buffer
between the delivery thread and the drain, not a store. This is a direct consequence of
declining `receive()` — with a pull API, unmatched messages would linger and would need
the retention machinery `DialogueCapability` uses.

Bounding still matters: the queue grows whenever producers outrun the drain, and this
subsystem has a history — four resource leaks were fixed in it during 0.26.0 and ADR-029
closed a fifth. This is the message path's first backpressure of any kind.

### D-3 — Node-local

The mailbox lives in the agent's process and holds nothing across a restart. It is a
buffer in front of dispatch, not a store, so there is no durable state to consider and
ADR-031's volatile-by-contract position is untouched.

Out of scope: durable mailboxes, mailbox migration between nodes, and any delivery
semantics beyond what the transport provides. ADR-021's Redis Streams consumer groups are
at-least-once, so **the mailbox must tolerate duplicate delivery** and must not present
itself as a deduplication layer.

### D-4 — Claim ordering is guaranteed; handler concurrency is unchanged

The drain takes messages **in arrival order, one at a time**. This is a new guarantee —
Context §3 shows none exists in-memory today — and it makes the in-memory dispatcher agree
with Redis instead of leaving ordering transport-dependent.

Claiming is serialised. **Handler invocation is not**: once the drain has routed a message
it dispatches the handler call onto a virtual thread, exactly as the dispatcher does
today. The guarantee is therefore *claim order*, not *completion order*.

This limit is deliberate. Running handlers inline would give full end-to-end ordering, but
one slow handler would then stall its agent's entire queue — a behaviour change existing
users did not ask for and would find hard to diagnose. Claim ordering removes the
transport discrepancy, which is the defect; serialising user code is a different and much
larger decision.

P2-2's defensive synchronisation in `DefaultConversation` is **not removed**. Dialogue's
timeout path still mutates conversation state from another thread, so the lock still
guards a real race. Recorded here so it is not later mistaken for dead code.

### D-5 — Placement: `agenor-runtime`, no new SPI, no runtime accessor

The `AgentMailbox` contract and `MailboxConfig` go in **`agenor-core`**; the
implementation goes in **`agenor-runtime`** alongside `BaseAgent` and
`InMemoryMessageDispatcher`. It is core agent machinery, not an optional feature, so it
does not belong in `agenor-runtime-ext`.

The contract carries what its collaborators actually use: `offer`, `size`, `capacity`, the
registration of the dialogue consumer — the drain's two lanes are the decision, so they are
not an implementation detail — and `start`/`stop`, as `Agent` and `BehaviorScheduler` already
do. `BaseAgent` builds its mailbox through a protected `createMailbox` hook alongside
`mailboxConfig`, so the runtime's implementation is named in exactly one overridable place
and an agent can supply its own without touching how the inbound path is wired. Anything
narrower would leave the contract decorative, with collaborators bound to the concrete
class — the coupling ADR-002 exists to prevent.

ADR-027's amendment governs two points, and both are discharged:

- **C1** — no new feature-specific accessor on `AgenorRuntime`. There is no
  `runtime.getMailbox()`; the mailbox is reached through the agent that owns it.
- **C2** — "before adding a sixth core SPI, evaluate consolidating them… and record the
  outcome". **No sixth SPI is added.** The mailbox is unconditional machinery in a module
  every deployment already has, and needs no `ServiceLoader` extension point. C2's
  evaluation therefore concludes without proposing consolidation, which is the outcome
  this ADR records.

---

## Alternatives considered

### A pull API: `receive(selector, timeout)`

The original G1 proposal added a JADE-style selective receive, letting an agent await a
message matching an arbitrary predicate. It was rejected on evidence:

- **The framework already waits, twice.** `DialogueCapability.request`/`query`/
  `callForProposals` return futures and are used at six sites in `agenor-examples` —
  `request` 2, `query` 3, `callForProposals` 1. Each takes a timeout, defaulting to 30 s
  (`DialogueCapability:66`); on expiry the future fails, the pending entry is removed and
  the conversation moves to `TIMEOUT`. That is a complete initiator-side await story, and
  it is the framework's actual idiom. `BaseAgent.requestFrom` sits beside it and is
  *already* a selector-based await — a predicate over `subscribeFiltered` with a timeout
  and auto-unsubscribe — merely hard-wired to correlation id.
- **The existing ones are dead.** `requestFrom` has zero callers outside its own unit
  test, across 89 example files and all framework code. It is `protected`, and it fails
  outright on any dispatcher that is not a `FilterableSubscriber` — which means every
  remote transport. The documented progress hook is in the same state:
  `ConversationManager.onMessage(conversationId, Consumer)` has zero uses outside three
  assertions in `DefaultConversationManagerTest`, and an initiator cannot use it even in
  principle — `request()` generates the conversation id internally and hands back only a
  `CompletableFuture<DialogueMessage>`, so the caller never learns the id it would have to
  pass. Adding a third await API beside two dead ones is not a gap being filled.
- **Its unique cases have no demonstrated use.** Awaiting an unsolicited message, matching
  an arbitrary predicate, first-of-N, and selective consumption that leaves non-matches
  queued: zero occurrences in examples, adapters or tests.
- **The apparent counter-evidence points elsewhere.** Three hand-rolled
  subscribe+future+timeout helpers exist (`OrderOrchestratorAgent.java:207`,
  `CustomerSupportExample.java:101`, `AgenorAgentExecutor.java:102`). All three match on
  `request.id().equals(msg.correlationId())` — correlated request/reply. They are evidence
  that `requestFrom` is unusable, not that a mailbox pull API is needed.
- **The ergonomic that makes it worth having cannot be shipped.** JADE's `receive()` is
  valuable because it is straight-line and blocking. `SimpleBehaviorScheduler` is
  `new ScheduledThreadPoolExecutor(4)` — platform threads, runtime-global — and
  `executeBehavior` does `behavior.execute().join()` (`:258`), so four blocked behaviours
  would stall every agent in the process. An asynchronous `receive()` is just a third
  future-returning API.

Declining it also removes the retention machinery D-2 would otherwise need, and leaves the
selector question (`MessageSelector` vs `MessageFilter`) unopened.

The gaps the evidence actually points at are elsewhere, and this ADR addresses neither:

1. **A first-class progress API for dialogue.** ADR-009 already records it — "AGREE for
   progress, INFORM for the result… deserves a first-class API rather than a documented
   workaround" — and `ContractNetExample.java:137` shows the shape: after sending `AGREE`
   the manager cannot await the worker's final `INFORM`, so it only prints it.
2. **A usable correlated request/reply on the plain path** — public, transport-neutral and
   topic-capable — to replace `requestFrom`. The three hand-rolled helpers above are its
   backing evidence: each one works around the API that should already have served it.

Both are dialogue- and messaging-side improvements, not mailboxes.

### Opt-in mailbox

Making the mailbox per-agent opt-in was considered. It would split the framework into two
kinds of agent, forcing every example, guide and reusable behaviour to pick a side, and it
would leave Context §2's defect in place for agents that opted out. Rejected.

### Inline routing without a queue

A single subscription with an inline router would fix Context §2 alone. Rejected because
ordering (D-4) requires a single consumer taking messages in sequence, which requires a
queue, and a queue requires bounds.

---

## Consequences

### Positive

- **The Redis handler-overwrite defect cannot recur.** It has already been fixed directly
  (Context §2), but that fix keeps the two dispatchers agreeing only by convention — each
  must independently remember to deliver to every subscriber. With one `subscribeRecipient`
  per agent there is no per-transport routing table to get wrong in the first place.
- **Claim ordering becomes a guarantee** instead of an accident that differs by transport.
- **The receive side becomes bounded** — the message path's first backpressure of any kind,
  closing the growth class that produced five leaks in this subsystem.
- **The inbound path acquires an owner.** One component decides routing, so the rule can be
  read in one place instead of inferred from subscription order and dispatcher internals.
- **A receive-side interception point now exists.** The drain is the natural home for a
  transport-independent receive-side span — today only the Redis consumer loop emits one —
  and for any future interceptor chain, without another dispatcher decorator.
- One subscription per agent also means one unsubscribe, removing the lifecycle asymmetry
  where either component's shutdown could revoke the other's delivery.

### Negative / trade-offs

- **A dialogue message no longer reaches `onDirectMessage()`.** This contradicts ADR-029's
  D3 and is handled as a scoped amendment there — see below. Agents that override
  `onDirectMessage()` and rely on observing dialogue traffic will stop seeing it.
- **Ordering is claim order, not completion order** (D-4). Handlers still run concurrently,
  so an agent cannot assume serialised handler execution. The guarantee is narrower than
  "the mailbox makes my agent single-threaded", and that distinction will need stating
  clearly in the docs or it will be misread.
- **A new failure mode: mailbox overflow.** An agent whose producers outrun it now drops
  messages with a WARN where it previously accumulated unbounded backlog. That is the
  intended trade, but it is a behaviour change and belongs in the migration notes.
- **`DialogueCapability` gains a dependency on the mailbox** for its inbound path, where it
  previously needed only a `MessageDispatcher`. Its own `isDialogueMessage()` guard becomes
  redundant for `BaseAgent`, but must stay for any `Agent` that is not one.
- **The mailbox cannot deduplicate.** ADR-021's transport is at-least-once, so a redelivered
  message is routed twice. Applications needing idempotence must still provide it.
- **Agenor remains push-only.** No pull primitive is added, so a system built around
  selective receive still has no direct equivalent. The framework's answer stays
  "`@DialogueHandler` plus `request()`/`query()`".
- Memory per agent rises by the queue's footprint — bounded by D-2, but no longer zero.

## Amendment to ADR-029

ADR-029's **D3** decided that only the dialogue path applies the classifier, and explicitly
accepted the consequence that "a dialogue message still reaches `onDirectMessage()`", on
the grounds that `BaseAgent` had "no way to know whether dialogue is active without a
coupling that does not exist today".

The drain creates exactly that knowledge in a legitimate place: routing happens once, at a
point that already sees both consumers, so no coupling between `BaseAgent` and
`DialogueCapability` is introduced. Rule 1 of D-1 therefore supersedes D3's fallthrough.

D3 anticipated this precisely:

> Because D1 is a pure function, moving to a symmetric rule later is a one-line change if
> this trade-off ever stops being acceptable.

ADR-029 is amended in scope, **not** replaced. Its D1 (the pure classifier), D2
(`fromMessage()` stays lenient) and D4–D6 (validation warns) are untouched and remain in
force, and its Status stays `Accepted`. Only D3's routing consequence changes.

## Implementation and verification

Three assertions carry the substance of this ADR and must exist:

- **One subscription.** Start an agent with both `@AgenorMessageHandler` methods and a
  `DialogueCapability`, and assert the dispatcher holds exactly **one** recipient
  subscription for that agent id — not that delivery happens to work.
- **Every subscriber still receives.** The regression tests added with the Context §2 fix
  (`RedisMessageDispatcherTest.SubscribeRecipient`) must keep passing once the mailbox
  replaces the routing table. They assert two handlers on one agent id both receive, and
  that unsubscribing one leaves the other live — properties the mailbox has to preserve
  through its drain rather than through the dispatcher.
- **Bounded and ordered.** Push more than `capacity` messages faster than the drain
  consumes and assert size never exceeds `capacity` and the policy behaves as configured;
  separately assert messages are claimed in send order.

Plus the ADR-029 amendment: a dialogue message reaches `@DialogueHandler` and **not**
`onDirectMessage()`.

Implementation is sequenced in `work-plan-g1-agent-mailbox-20260818.md`, whose tasks are
gated on this ADR being `Accepted`.

## Related ADRs

- **ADR-029** (Protocol Validation and Dialogue Message Classification): supplies
  `isDialogueMessage()` as the drain's routing predicate; its D3 is amended in scope by
  this ADR, as recorded above. ADR-029 identified the double subscription; this ADR removes
  it.
- **ADR-027** (Minimal Runtime Core): its C1 and C2 conventions are discharged in D-5.
- **ADR-021** (Redis MessageTransport): the at-least-once semantics D-3 defers to, and the
  transport whose handler-overwrite defect D-1 closes.
- **ADR-009** (Agent Dialogue Protocol): `DialogueCapability` stops being an independent
  subscriber and becomes a drain consumer. Its recorded open gap — a first-class API for
  observing `AGREE` as progress — is noted in *Alternatives considered* and left open.
- **ADR-019** (OpenTelemetry Instrumentation): the drain is where a transport-independent
  receive-side span belongs; today only the Redis consumer loop emits one.
- **ADR-004** (Progressive Complexity Strategy): ordering, backpressure and tracing that
  differ by dispatcher are the silent break ADR-004 promises cannot happen.
