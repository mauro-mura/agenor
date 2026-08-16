# ADR-031: Conversation-State Durability

**Status**: Accepted
**Date**: 2026-08-16
**Authors**: Project Team
**References**: ADR-004 (Progressive Complexity Strategy), ADR-009 (Agent Dialogue Protocol),
ADR-021 (Redis MessageTransport), ADR-023 (Persistent Agent Directory with JDBC),
ADR-030 (`Message.content` Typing Across Transports), ADR-002 (Interface-First Architecture)

---

## Context

Every other stateful capability in the framework has a rung on ADR-004's ladder: messaging goes
in-memory → Redis (ADR-021), the directory in-memory → JDBC (ADR-023), the HITL queue in-memory →
JDBC (ADR-024). Dialogue has none. `DefaultConversationManager` and `DefaultCommitmentTracker`
are the only implementations, and nothing states whether that is a decision or an omission.

The question has to be asked precisely, because two different things were being conflated under
the word "distributed", and the answer differs for each.

### Axis 1 — transport reach: already crosses runtimes

A dialogue is an exchange of `Message`s over whatever `MessageDispatcher` the agent holds. With
Redis messaging (ADR-021) or the A2A adapter, **an agent in one runtime can already hold a
conversation with an agent in another** — that works today and no decision here changes it.

The documentation was ambiguous on exactly this point. `dialog-protocol.md` said conversation
state is "held in memory and node-local", which is true of the *state* and easy to misread as
"dialogue only works within one node", which is false. That wording predates the constraint that
prompted this ADR and is corrected as part of it.

### Axis 2 — state durability: node-local, and deliberately more so since 0.26.0

What each agent keeps about a conversation — its `ProtocolState`, message history, pending
response futures, commitments — lives in that agent's own maps, in that agent's process. It does
not survive a restart, and 0.26.0 made it strictly more volatile, on purpose:

- `DialogueCapability.shutdown()` clears the conversation manager, which is what makes a stopped
  agent restartable;
- terminated conversations and commitments are swept after a retention window (default 5
  minutes).

So the real question is not "can agents in different runtimes talk?" (yes) but "**should an
agent's own conversation state outlive its own process?**"

### What durability would actually have to buy

An honest answer needs the failure mode, not the feature list. If agent A restarts mid-request:

- A's peer B is unaffected in its own state and simply waits out **its own** timeout;
- A loses the `CompletableFuture` it was going to complete — and that future cannot be restored
  by any store, because it is a live object in a process that no longer exists;
- A's commitments to B are lost, so nothing marks them `VIOLATED`.

A persistent `ConversationManager` would restore items 1 and 3 — the state — while item 2, the
part the calling code actually awaits, is unrecoverable without a fundamentally different
programming model (durable continuations, or a request/response protocol rebuilt on top of an
outbox). **Persisting the state alone would produce an agent that remembers a conversation it
can no longer complete**, which is a worse failure than forgetting it, because it looks like it
worked.

## Decision

**Option A — volatile by contract.** Dialogue state is node-local, lives and dies with the
agent's process, and this is a specified property rather than an implementation detail. Nothing
durable may be modelled on `Conversation` or `Commitment`.

Concretely:

1. `Conversation` and `Commitment` Javadoc state the lifetime explicitly, so the contract is
   visible to someone reading only the API.
2. `dialog-protocol.md` gets a restart/failover section separating the two axes above, and its
   "node-local" wording is fixed so it can no longer be read as "local-only".
3. No persistent implementation is built. The seam to do so already exists and stays open.

### Why not build the persistent implementation now

Per the project's on-demand ADR policy, and because the seam is already in place: 0.26.0's
`ConversationManagerFactory` + `DialogueCapability.builder(agent)` mean a durable
`ConversationManager` can be dropped in **without changing this decision or any user code**
(ADR-002). Choosing Option A today therefore costs nothing later — it is not a door being
closed, it is a door nobody has asked to walk through.

Building it speculatively would cost something real: a schema, a migration, an expiry policy, a
contract-test suite across implementations, and the semantics of a restored-but-uncompletable
conversation described above — the hardest part, and the one no store answers.

If a concrete adopter requirement appears, Option B (a JDBC or Redis `ConversationManager` behind
the existing seam, following ADR-023's two-phase shape) becomes its own work plan, and it must
answer the pending-future problem before writing any SQL.

### What "durable enough" looks like today, without this ADR changing

The timeout is the recovery mechanism, and it is already there: every `request()`/`query()`/
`callForProposals()` arms one, and 0.26.0 made the timeout path clean up its own pending entry.
A peer that never replies — because it crashed, restarted, or was never there — resolves the
caller's future exceptionally within a bounded time. That is the failover story, and it is
sufficient for the conversation lengths dialogue is designed for (seconds to minutes), not for
long-running sagas. An agent that needs a business process to survive restarts should model that
process in its own persistent state (`@Persist`, ADR-015's HITL queue) and use dialogue as the
transport for its steps, not as the record of them.

## Options considered

1. **Option A — volatile by contract.** Chosen. Costs a documentation pass; states the contract
   where a developer will find it; leaves the seam open.
2. **Option B — ship a persistent `ConversationManager`** (JDBC first, Redis second, mirroring
   ADR-023/ADR-028). Rejected for now: no adopter requirement, and it does not solve the part
   that actually breaks on restart (the pending future). Revisitable behind the existing seam
   without revisiting this ADR's contract.
3. **Leave it unspecified.** Rejected explicitly. That is the status quo, and it is what let
   "node-local state" be read as "node-local dialogue". An unwritten contract is one every
   adopter has to rediscover, and some will discover it in production.

## Consequences

### Positive

- The contract is stated instead of assumed, in both the guide and the Javadoc.
- The transport/durability confusion is resolved in writing: cross-runtime dialogue is supported
  and always was; cross-restart dialogue state is not.
- ADR-004's ladder now has an explicit "this rung is intentionally empty, and here is the seam"
  entry for dialogue, rather than a silent gap.

### Negative / trade-offs

- An agent that restarts mid-dialogue loses that dialogue with no way to resume. Peers recover
  through their own timeouts, which is bounded but not free — a long `timeout` on a
  `callForProposals` is a long stall for the caller.
- Commitments do not survive a restart, so a `VIOLATED` transition that would have fired after
  the restart never fires. Anything that must be audited belongs in the agent's own persistent
  state, not in the commitment tracker.
- Dialogue is therefore unsuitable, as designed, for long-running business processes. This ADR
  makes that a documented boundary rather than a discovered one.

## Relationship to ADR-030

The two were written together, and the order matters. Once cross-runtime dialogue is affirmed as
supported (axis 1), the Redis transport is in play, and on that transport every non-`String`
payload was a `ClassCastException` invisible to a test suite that only ever used the in-memory
dispatcher. **ADR-030 closes that hole; without it, "dialogue crosses runtimes" would have been
true in principle and broken in practice for any payload that is not a string.**

## Related ADRs

- **ADR-009** (Agent Dialogue Protocol): defines the `Conversation`/`Commitment` model whose
  lifetime this ADR fixes.
- **ADR-004** (Progressive Complexity Strategy): this is the ladder rung for dialogue state —
  deliberately left at the first step, with the seam for the second already built.
- **ADR-002** (Interface-First Architecture): the reason Option A costs nothing later —
  `ConversationManager` is an interface and `DialogueCapability` depends only on it.
- **ADR-021** (Redis MessageTransport) and the A2A adapter: what makes cross-runtime dialogue
  work today, on the transport axis.
- **ADR-030** (`Message.content` Typing Across Transports): the prerequisite that makes
  cross-runtime dialogue usable rather than merely reachable.
