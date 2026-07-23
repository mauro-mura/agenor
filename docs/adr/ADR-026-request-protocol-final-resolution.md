# ADR-026: REQUEST Protocol Final-Resolution Semantics

**Status**: Accepted
**Date**: 2026-07-23
**Authors**: Project Team
**References**: ADR-009 (Agent Dialogue Protocol)

---

## Context

ADR-009 documents, under *Known Limitations*, that the FIPA REQUEST protocol's two-step
reply sequence (`REQUEST → AGREE → INFORM`) is not correctly resolved by
`ConversationManager.request()` (`agenor-runtime`) or `AgenorA2AAdapter.sendInternal()`
(`agenor-adapters`). Both `AGREE` and `INFORM` set `inReplyTo = REQUEST.id`, and the
pending-response map is keyed on `inReplyTo`, so the returned `CompletableFuture` resolves
on the **first** reply (the intermediate `AGREE`). The final `INFORM` arrives after the
future is already completed and is silently discarded.

This is demonstrated live by `RequestProtocolExample`: the INFORM's "Completed: ..." line is
never printed. It was left as an open item at the end of the Jentic→Agenor migration and ADR-009
explicitly deferred the decision to a future ADR — this is that ADR.

### Why a generic "wait for FSM terminal state" does not work

The obvious fix — resolve the future only when `conversation.getState().isTerminal()` — breaks
`ContractNetProtocol`. Its FSM keeps `AWAITING_RESPONSE` (non-terminal, "collecting proposals")
after a `PROPOSE` or `REFUSE`:

```java
case AWAITING_RESPONSE -> switch (received) {
    case PROPOSE, REFUSE -> AWAITING_RESPONSE; // collecting proposals
    case AGREE -> AGREED;
    ...
```

`ConversationManager.callForProposals()` creates one conversation per participant and expects
each participant's future to resolve on its **first** reply (`PROPOSE` or `REFUSE`), not on a
terminal FSM state. A generic terminal-state gate would silently hang every `callForProposals()`
call.

## Decision

Adopt **fire-and-final** semantics, implemented as a targeted gate rather than a generic
terminal-state check:

> The pending-response future resolves on the first reply, **unless** that reply's
> performative is `AGREE` and the conversation's protocol still expects `INFORM` or `FAILURE`
> afterwards (`protocol.allowedPerformatives(newState, isResponder=true)` contains one of
> them). In that case, resolution is deferred to the next reply carrying `INFORM` or
> `FAILURE`.

Concretely: `RequestProtocol` conversations defer past `AGREE` and resolve on `INFORM`/
`FAILURE`. `QueryProtocol` conversations never send `AGREE`, so they are unaffected.
`ContractNetProtocol`'s per-participant conversations resolve on `PROPOSE`/`REFUSE` exactly as
before — `AGREE` there is sent by the initiator, not received as a pending-response reply, so
the gate never engages on that path either.

If a conversation has no associated protocol (`conversation.getProtocol()` empty), the legacy
behavior is kept (resolve on first reply) to avoid introducing silent hangs for unregistered
custom protocols.

Callers that need to observe the intermediate `AGREE` (e.g. for progress reporting) use the
existing `onMessage(conversationId, handler)` listener, now promoted from
`DefaultConversationManager` onto the `ConversationManager` interface.

**`AgenorA2AAdapter.sendInternal()` uses a simpler, local variant of the same rule.** This
method lives in `agenor-adapters`, which depends only on `agenor-core` (not `agenor-runtime`),
so it has no access to `ConversationManager`/`ProtocolRegistry` and cannot reuse the FSM-based
gate directly without adding a new inter-module dependency the architecture (interface-first,
swappable runtime) does not otherwise require. Instead, its existing per-call temporary
subscription is kept open across an intermediate `AGREE` and only resolves the returned future
on the next non-`AGREE` reply. This is a narrower, unconditional version of the same fire-and-
final principle (no protocol lookup needed) — safe here because the adapter always plays the
initiator role in a single request/response exchange, unlike `callForProposals()`.

### Options considered

1. **Formalize fire-and-first-reply as canonical** (i.e., document the current bug as
   intended behavior, direct callers to `sendWithStreaming()`). Rejected: contradicts the
   documented FIPA REQUEST semantics and the framework's own example
   (`RequestProtocolExample`) is written expecting the INFORM to be observable synchronously.
2. **Chain `inReplyTo`** (`INFORM.inReplyTo = AGREE.id` instead of `REQUEST.id`) and track the
   full reply chain in `ConversationManager`. Rejected: requires responders to change how they
   construct `INFORM` messages, which is a wire-format change with no corresponding upstream
   FIPA requirement (FIPA REQUEST replies all correlate to the original REQUEST). Higher blast
   radius than a gate confined to `ConversationManager`.
3. **Gate resolution on `AGREE` + `allowedPerformatives` check** (chosen). Reuses the existing,
   already-tested `Protocol` FSM abstraction; touches only the two files that own the bug;
   leaves `QueryProtocol` and `ContractNetProtocol` behavior unchanged by construction.

## Consequences

### Positive

- `ConversationManager.request()` and `AgenorA2AAdapter.sendInternal()` now return the actual
  final outcome of a REQUEST exchange, matching FIPA semantics and the framework's own
  documentation/examples.
- No new correlation mechanism or wire-format change; reuses `Protocol.allowedPerformatives()`.
- `ContractNetProtocol.callForProposals()` and `QueryProtocol` are provably unaffected (neither
  path ever satisfies the `AGREE`-gate condition).

### Negative / trade-offs

- **Behavior change**: any caller of `ConversationManager.request()` that previously relied on
  receiving the `AGREE` message from the returned future must switch to
  `onMessage(conversationId, handler)` to observe it. This is called out in CHANGELOG.md as a
  breaking-ish fix, since the old behavior was undocumented-as-correct and only ever the
  documented bug.
- A responder that sends `AGREE` but never follows up with `INFORM`/`FAILURE` now causes the
  caller's future to time out instead of completing early with a false "success". This is the
  correct behavior for a non-compliant responder, not a regression.

### Known limitation carried forward

`AgenorAgentExecutor` (the A2A server-side bridge, `agenor-adapters/.../a2a/AgenorAgentExecutor.java`)
has the identical bug: it subscribes for the first reply and marks the A2A task `complete()`
using the `AGREE` message's content. Fixing it requires giving it a `ConversationManager`
dependency, which changes its public constructor (used by `SupportA2AServer` and
`A2AIntegrationExample`). This is deliberately out of scope for this ADR and is deferred to a
separate branch/PR, following the same pattern ADR-009 used to defer this fix.

## Related ADRs

- ADR-009: Agent Dialogue Protocol (documents the original limitation this ADR resolves)
