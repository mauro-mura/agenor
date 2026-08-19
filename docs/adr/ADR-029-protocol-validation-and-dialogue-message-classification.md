# ADR-029: Protocol Validation Enforcement and Dialogue Message Classification

**Status**: Accepted
**Date**: 2026-08-15
**Last Modified**: 2026-08-18
**Authors**: Project Team
**References**: ADR-009 (Agent Dialogue Protocol), ADR-026 (REQUEST Protocol Final-Resolution
Semantics), ADR-002 (Interface-First Architecture)

---

## Context

Two behaviours in the dialogue runtime silently absorb information that the framework already
has. Both change what a running agent observes, which is why they are decided here rather than
shipped as ordinary fixes.

### 1. `Protocol.isValid()` is dead API

`Protocol` declares a validation method (`agenor-core/.../protocol/Protocol.java`):

```java
default boolean isValid(ProtocolState state, Performative performative, boolean isInitiator) {
    return allowedPerformatives(state, isInitiator).contains(performative);
}
```

Nothing in the codebase calls it. `DefaultConversation.addMessage()` advances the conversation
purely through `protocol.nextState(...)`, and every built-in protocol returns `current` for an
unexpected performative. A peer that sends `INFORM` before any `REQUEST`, or replies twice, or
answers a conversation it already completed, produces no state change, no error and no log
line. The FSM is therefore descriptive, not enforcing: the framework knows the exchange is
malformed and discards that knowledge.

### 2. Every direct message is interpreted as dialogue

`InMemoryMessageDispatcher.subscribeRecipient()` appends handlers to a list per agent id — it
does not replace. A `BaseAgent` that owns a `DialogueCapability` therefore holds **two**
subscriptions on its own recipient channel: `BaseAgent.autoSubscribeDirectMessages()` and
`DialogueCapability.initialize()`. Every inbound direct message is delivered to both.

On the dialogue side, `DialogueMessage.fromMessage()` is lenient by design: a missing
`performative` header defaults to `INFORM` and a missing `conversationId` is fabricated as a
fresh UUID. A plain `sendTo()` message with no dialogue headers is thus reinterpreted as a
well-formed dialogue `INFORM` belonging to a brand-new conversation, and:

- `DefaultConversationManager.handleIncoming()` creates a conversation for that unknown
  `conversationId`;
- the fabricated message is dispatched to any `@DialogueHandler` that matches `INFORM`.

The first consequence is a resource leak, and it is not covered by the retention sweep added in
0.26.0: that sweep removes conversations whose state `isTerminal()`, and a phantom conversation
built from a single fabricated `INFORM` never reaches a terminal state. The `conversations` map
therefore grows by one permanent entry per non-dialogue direct message, for the lifetime of the
agent.

This is the fourth resource leak found in this subsystem; the other three were fixed in 0.26.0
(pending-response futures on timeout, terminated conversations, terminated commitments).

## Decision

### D1 — Classification is a pure static function on `DialogueMessage`

Add to `agenor-core`:

```java
public static boolean isDialogueMessage(Message message)
```

It returns `true` if and only if the message's `performative` header is present **and** maps to
a known `Performative` constant. It has no state and no dependencies beyond `Message` and
`Performative`, so any component that routes inbound messages can use the same rule without
taking a dependency on the dialogue runtime.

The second condition matters as much as the first. Today an unrecognised performative string
falls back to `INFORM`:

```java
try {
    return Performative.valueOf(perf.toUpperCase());
} catch (IllegalArgumentException e) {
    return Performative.INFORM;   // a newer peer's performative executes as INFORM
}
```

Under this decision such a message is not classified as dialogue at all: it takes the direct
path and is logged, instead of being executed as something its sender never said.

`conversationId` is deliberately **not** part of the condition. A first message from a peer that
does not set one is still dialogue, and fabricating the id there is legitimate.

### D2 — `fromMessage()` stays lenient and unchanged

`fromMessage()` is the **converter**; `isDialogueMessage()` is the **classifier**. Converting
without classifying first is the caller asserting it already knows what it holds — which is
exactly the situation of the two A2A call sites (`AgenorAgentExecutor.execute()` and
`AgenorA2AAdapter.sendInternal()`), each awaiting the reply to a dialogue exchange it initiated
itself.

No public signature in `agenor-core` changes, and neither A2A call site is touched.

### D3 — Only the dialogue path applies the classifier

`DialogueCapability.handleIncomingMessage()` discards non-dialogue messages before they reach
`ConversationManager.handleIncoming()` or the handler registry. That closes both harms: no
phantom conversation, no `@DialogueHandler` invoked on traffic that is not dialogue.

`BaseAgent.handleDirectMessage()` deliberately does **not** filter. An agent *without* a
`DialogueCapability` must still be able to receive a message that happens to carry dialogue
headers, and `BaseAgent` has no way to know whether dialogue is active without a coupling that
does not exist today and that this ADR is not willing to introduce for the benefit it buys.

The accepted consequence is that a dialogue message still reaches `onDirectMessage()`, whose
default implementation is a `log.trace`. An agent that overrides `onDirectMessage()` and also
uses dialogue will observe its own dialogue traffic there, and must ignore it — the same
`isDialogueMessage()` call is available for that. Because D1 is a pure function, moving to a
symmetric rule later is a one-line change if this trade-off ever stops being acceptable.

### D4 — Validation warns, it does not throw

`DefaultConversation.addMessage()` validates before transitioning. On a violation it logs at
WARN — conversation id, current state, offending performative, and the set that was allowed —
then proceeds exactly as today: the message is still appended to the history and
`nextState(...)` still runs (which, for the built-in protocols, leaves the state unchanged for
an unexpected performative). The only observable change is the warning.

Throwing was rejected as the first step. It would turn a peer's misbehaviour into a local
failure, break already-deployed non-compliant peers, and authorise far more behaviour change
than one increment should. Making the severity configurable was likewise deferred: there is no
consumer asking for it, and adding a knob before anyone has seen the warnings is the wrong
order.

When a conversation has no protocol (`getProtocol()` empty — an unregistered custom protocol, or
a message with no `protocol` header) no validation happens. There is no FSM to validate against,
and inventing one would reintroduce the guessing this ADR removes.

### D5 — Validation uses the *sender's* perspective

`allowedPerformatives(state, isInitiator)` is defined from the point of view of the party
**sending** the performative. Validating an inbound message with the local conversation's own
`isInitiator` flag would flag every legitimate reply: an incoming `AGREE` in `AWAITING_RESPONSE`
on an initiator's conversation yields `allowed = {CANCEL}` for `RequestProtocol`.

The direction is derivable inside `DefaultConversation` without knowing the local agent id:

```java
boolean senderIsInitiator = message.senderId().equals(initiatorId);
protocol.isValid(state, message.performative(), senderIsInitiator);
```

This holds in both roles because `initiatorId`/`participantId` are populated consistently on
both sides: `DefaultConversationManager.initiateConversation()` sets
`(localAgentId, targetAgentId, isInitiator = true)`, and `handleIncoming()` sets
`(message.senderId(), localAgentId, isInitiator = false)`.

`DefaultConversationManager.shouldResolvePendingResponse()` already hardcodes
`allowedPerformatives(state, false)` for exactly this reason (ADR-026). The two must stay
consistent: both read the set from the perspective of the party that sends the reply.

Every legitimate flow of the three built-in protocols was walked by hand in both roles under
this rule. All pass, with one exception, which is D6.

### D6 — `RequestProtocol` must be made internally consistent

`RequestProtocol`'s two halves contradict each other:

```java
case AWAITING_RESPONSE -> switch (received) {
    case AGREE -> AGREED;
    case REFUSE -> REFUSED;
    case INFORM -> COMPLETED;
    case FAILURE -> FAILED;      // nextState accepts FAILURE here
    ...

case AWAITING_RESPONSE -> Set.of(AGREE, REFUSE, INFORM);   // allowedPerformatives does not
```

A responder that fails immediately, without first sending `AGREE`, follows a transition the FSM
explicitly defines while sending a performative the same FSM declares disallowed. This was
invisible while `isValid()` was dead. Enforcing it would make `DialogueCapability.failure(...)`
— a public convenience method — emit a false violation warning on a perfectly correct exchange.

`FAILURE` is therefore added to `allowedPerformatives(AWAITING_RESPONSE, isInitiator = false)`.
This is a correction of the protocol definition, not a relaxation of the check: the transition
was already there.

`QueryProtocol` and `ContractNetProtocol` were audited the same way and are already consistent
in both roles.

## Options considered

1. **`fromMessage()` returns `Optional<DialogueMessage>`** (strict converter, no separate
   classifier). Rejected: it changes the signature of a public `agenor-core` method, and forces
   the two A2A call sites to handle an empty result that has no meaning for them — they are
   converting a reply to a dialogue message they sent themselves. It also conflates two distinct
   questions ("is this dialogue?" and "give me the dialogue view of it") into one return value.
2. **Symmetric filtering — `BaseAgent.handleDirectMessage()` skips dialogue messages too.**
   Rejected for now: an agent with no `DialogueCapability` would silently drop every dialogue
   message, since nothing else would handle it. Making the filter conditional requires
   `BaseAgent` to know whether a dialogue capability is attached, which is a coupling this
   subsystem does not otherwise need. D1 keeps the door open at the cost of one line.
3. **A single subscription owned by `BaseAgent`, which routes to dialogue.** Rejected: it
   removes the duplication at the root but breaks `DialogueCapability` for agents that implement
   `Agent` directly without extending `BaseAgent` — a supported and exercised configuration
   (`A2AIntegrationExample`, `RequestProtocolExample`). The capability must remain usable
   independently of the base class, per ADR-002.
4. **`isValid()` throws on violation.** Rejected as the first step; see D4. Revisitable once
   there is field evidence of what the warnings actually catch.
5. **Classify on `conversationId` presence instead of `performative`.** Rejected: `performative`
   is the header that declares communicative intent, and requiring `conversationId` would
   misclassify a legitimate first message from a peer that does not set one.

## Consequences

### Positive

- **The fourth resource leak is closed.** `conversations` no longer grows by one permanent,
  never-terminal entry per non-dialogue direct message. This is the concrete reason the change
  is not cosmetic: without it, any agent that mixes `sendTo()` traffic with dialogue leaks for
  as long as it runs.
- A `@DialogueHandler` can no longer fire on traffic that is not dialogue, so mixing
  `@AgenorMessageHandler`/`onDirectMessage()` with dialogue on one agent becomes predictable.
- Protocol violations become observable at the point where the framework already detects them,
  instead of being discarded. `Protocol.isValid()` stops being dead API, which also makes custom
  `Protocol` implementations testable against real traffic.
- A performative from a newer peer is no longer executed as `INFORM`.
- `RequestProtocol` becomes self-consistent, so `allowedPerformatives()` can be trusted by any
  future code that reads it — including ADR-026's resolution gate.

### Negative / trade-offs

- **New WARN lines appear in legitimate but late scenarios.** A reply that arrives after the
  initiator's timeout finds the conversation in `TIMEOUT`, and a reply after `cancel()` finds it
  in `CANCELLED`; `allowedPerformatives` is empty for both, so both warn. The information is
  correct — the reply genuinely arrived out of protocol — but it will look like a new bug to
  anyone who has not read this ADR. It is called out in CHANGELOG.md for the same reason.
- A dialogue message still reaches `onDirectMessage()` (D3). Agents that override it and also
  use dialogue must ignore dialogue traffic there.
- Behaviour change for anyone relying on the lenient path: a plain message that used to be
  delivered to a `@DialogueHandler(performatives = INFORM)` will no longer be. That path was
  never documented and produced a conversation with a random id, but it worked, and someone may
  have built on it.

## Implementation and verification

D1–D3 (classification) and D4–D6 (validation) are independent of each other and can land as two
separate changes. Two assertions carry the substance of this ADR and must be present:

- **The leak**: send a plain `Message` with no dialogue headers to an agent that has both a
  direct-message handler and a `@DialogueHandler`, and assert `getActiveConversations()` is
  **empty** afterwards — not merely that the dialogue handler did not fire. The dispatch
  assertion alone would pass even if the phantom conversation were still created.
- **The warning without a transition**: send an out-of-order performative (e.g. `INFORM` before
  any `REQUEST`) and assert the conversation's state is unchanged and the violation is reported.

Plus a regression test for D6: a `FAILURE` sent in `AWAITING_RESPONSE` on a REQUEST conversation
produces no violation warning.

## Related ADRs

- **ADR-009** (Agent Dialogue Protocol): defines `Protocol`, `DialogueMessage` and the
  capability wiring this ADR constrains. Its *Known Limitations* section records both problems
  and points here.
- **ADR-026** (REQUEST Protocol Final-Resolution Semantics): established the sender-perspective
  reading of `allowedPerformatives()` in `shouldResolvePendingResponse()`; D5 generalises it and
  D6 fixes the protocol definition both rely on.
- **ADR-002** (Interface-First Architecture): the reason option 3 was rejected —
  `DialogueCapability` must work for any `Agent`, not only for `BaseAgent`.
- **ADR-032** (Agent Mailbox — a Single Inbound Path per Agent): removes the double
  subscription this ADR identified, reuses D1's classifier as the routing predicate of the
  mailbox drain, and amends D3 in scope — see below.

## Amendment — 2026-08-18: D3's fallthrough superseded by ADR-032

**Scope: D3 only.** D1, D2 and D4–D6 are untouched and remain in force, and this ADR's
Status stays `Accepted`. This is not a replacement.

D3 decided that only the dialogue path applies the classifier, and accepted as a
consequence that "a dialogue message still reaches `onDirectMessage()`". The stated reason
was that `BaseAgent` "has no way to know whether dialogue is active without a coupling that
does not exist today and that this ADR is not willing to introduce".

ADR-032 introduces a single per-agent mailbox that owns the only `subscribeRecipient` for
that agent, with `BaseAgent`'s dispatch and `DialogueCapability` as consumers of its drain.
Routing therefore happens once, at a point that already sees both consumers — so the
knowledge D3 lacked becomes available **without** the coupling D3 refused. Under the drain,
a dialogue message is routed to the dialogue path and no longer falls through to
`onDirectMessage()`.

D3 anticipated exactly this:

> Because D1 is a pure function, moving to a symmetric rule later is a one-line change if
> this trade-off ever stops being acceptable.

What changes for users: an agent that overrides `onDirectMessage()` and relies on observing
its own dialogue traffic there will stop receiving it. D3 documented that fallthrough as an
accepted cost and told such agents to filter with `isDialogueMessage()`, so code may depend
on it. The migration is to use a `@DialogueHandler` instead.

What does **not** change: `isDialogueMessage()` keeps its exact semantics (performative
header present and naming a known `Performative`), `fromMessage()` stays lenient, and
validation still warns rather than throws.
