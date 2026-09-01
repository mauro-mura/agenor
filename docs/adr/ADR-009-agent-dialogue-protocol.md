# ADR-009: Agent Dialogue Protocol

**Status**: Accepted  
**Date**: 2025-12-13  
**Last Modified**: 2026-09-01 (see Amendment below)  
**Authors**: Project Team

> **Amendment — 2026-05-17**: Following ADR-020 (Core API Refactor), the internal transport is
> `MessageDispatcher` (and its capability interfaces `TopicPublisher`, `TopicSubscriber`,
> `DirectMessenger`, `DirectReceiver`) rather than the now-removed `MessageService`. All
> references to `MessageService` in this document have been updated accordingly.

## Context

The Agenor framework requires standardized agent communication. Analysis reveals two distinct domains:

1. **Intra-Runtime**: Agents within the same JVM communicating via `MessageDispatcher`
2. **Extra-Runtime**: Agents communicating with external systems (other runtimes, LLM agents, third-party services)

### Current Capabilities

| Feature | Status | Notes |
|---------|--------|-------|
| `Message` record | ✅ | Basic message structure |
| `MessageDispatcher` | ✅ | Topic pub-sub and direct messaging |
| `AgentDirectory` | ✅ | Agent registration/discovery |
| Correlation ID | ✅ | Request-response pattern |
| Headers/metadata | ✅ | Extensible via map |

### Missing Capabilities

1. **Communicative intent**: No performatives (REQUEST, INFORM, AGREE, etc.)
2. **Conversation tracking**: No multi-message exchange management
3. **External interoperability**: No standard protocol for external agents

### Modern Agent Protocol Landscape

Since 2024, protocols have emerged for LLM-based agent systems:

| Protocol | Owner | Focus | Adoption |
|----------|-------|-------|----------|
| **A2A** | Google | Agent-to-agent, task delegation | Growing |
| **ACP** | IBM | Rich messaging, observability | Emerging |
| **MCP** | Anthropic | Agent-to-tool | Widespread |

**Key insight**: Implementing a custom wire protocol competes with industry standards without adding value. Better to leverage existing infrastructure internally and bridge to standards externally.

## Decision

Implement a **dual-domain architecture**:

1. **Dialogue Layer** (internal): Lightweight semantic layer over existing `MessageDispatcher`
2. **A2A Bridge** (external): Adapter for interoperability with external agents

```
┌─────────────────────────────────────────────────────────────┐
│                     AGENOR RUNTIME (JVM)                    │
│                                                             │
│   ┌─────────┐     MessageDispatcher    ┌─────────┐           │
│   │ Agent A │◄───────────────────────►│ Agent B │           │
│   └─────────┘   + Dialogue semantics  └─────────┘           │
│        │                                  │                 │
│        │           ┌─────────┐            │                 │
│        └──────────►│ Agent C │◄───────────┘                 │
│                    └─────────┘                              │
│                         │                                   │
│                         ▼                                   │
│              ┌───────────────────┐                          │
│              │    A2A Bridge     │  (agenor-adapters)       │
│              └─────────┬─────────┘                          │
└────────────────────────┼────────────────────────────────────┘
                         │ HTTP/SSE
                         ▼
              ┌───────────────────┐
              │  External Agents  │
              │   (A2A Protocol)  │
              └───────────────────┘
```

### Key Design Decisions

#### 1. Dialogue as Semantic Layer (not Transport)

**Decision**: `DialogueMessage` is a flat record that encodes dialogue metadata as `Message` headers. No new transport.

```java
public record DialogueMessage(
    String id,
    String conversationId,
    String senderId,
    String receiverId,
    Performative performative,
    Object content,
    String protocol,
    String inReplyTo,       // ID of the message this replies to; null if not a reply
    Instant timestamp,
    Map<String, Object> metadata
) {
    public Message toMessage() {
        // Encode dialogue metadata in Message headers (no prefix)
        return Message.builder()
            .id(id)
            .senderId(senderId)
            .receiverId(receiverId)
            .correlationId(inReplyTo)   // inReplyTo travels as correlationId
            .content(content)
            .header("conversationId", conversationId)
            .header("performative", performative.name())
            .header("protocol", protocol)   // omitted when null
            .build();
    }

    public static DialogueMessage fromMessage(Message msg) {
        // conversationId ← header "conversationId" (random UUID if absent)
        // performative  ← header "performative"  (defaults to INFORM if absent/unknown)
        // inReplyTo     ← msg.correlationId()
    }
}
```

**Note — `inReplyTo` chaining**: both `AGREE` and `INFORM` set `inReplyTo = REQUEST.id`
(i.e., both reply directly to the original REQUEST, not chained AGREE → INFORM).
Historically this meant `ConversationManager.request()` resolved on the **first** reply
received (typically AGREE), not on the final INFORM — see *Known Limitations* below. This is
resolved by ADR-026: the future now defers past `AGREE` and resolves on the final
`INFORM`/`FAILURE`.

**Rationale**:
- Zero new infrastructure for intra-runtime
- Microsecond latency (in-memory)
- Leverages battle-tested `MessageDispatcher`

#### 2. Reduced Performatives (10 Core)

**Decision**: 10 pragmatic performatives covering all common patterns.

```java
public enum Performative {
    INFORM,         // Share information
    QUERY,          // Request information  
    REQUEST,        // Ask to perform action
    AGREE,          // Commit to action
    REFUSE,         // Decline action
    FAILURE,        // Report failure
    PROPOSE,        // Make proposal
    CFP,            // Call for proposals
    CANCEL,         // Cancel interaction
    NOT_UNDERSTOOD  // Parse/semantic error
}
```

**Rationale**: Research shows FIPA's 22+ performatives reduce to ~8 core operations in practice.

#### 3. Observable Commitment Tracking (Optional)

**Decision**: Commitment-based semantics as optional layer for auditability.

```java
public interface Commitment {
    String getId();
    String getDebtor();
    String getCreditor();
    CommitmentState getState();  // PENDING, ACTIVE, FULFILLED, VIOLATED
    List<CommitmentEvent> getHistory();
}

public interface CommitmentTracker {
    Commitment createFromMessage(DialogueMessage message);
    List<Commitment> getActiveAsDebtor(String agentId);
    List<Commitment> checkViolations();
}
```

**Rationale**: Observable commitments solve FIPA's unverifiable BDI semantics problem.

#### 4. A2A Bridge using Official SDK

**Decision**: Use the official `a2a-java-sdk` for external communication.

**Dependencies** (in agenor-adapters):
```xml
<dependency>
    <groupId>io.github.a2asdk</groupId>
    <artifactId>a2a-java-sdk-client</artifactId>
    <version>${a2a.sdk.version}</version>
</dependency>
<dependency>
    <groupId>io.github.a2asdk</groupId>
    <artifactId>a2a-java-sdk-server-common</artifactId>
    <version>${a2a.sdk.version}</version>
</dependency>
```

**Server-side** (expose internal agents):
```java
// Implements io.a2a.server.agentexecution.AgentExecutor
public class AgenorAgentExecutor implements AgentExecutor {
    
    private final MessageDispatcher messageDispatcher;
    private final DialogueA2AConverter converter;
    
    @Override
    public void execute(RequestContext context, EventQueue eventQueue) {
        TaskUpdater updater = new TaskUpdater(context, eventQueue);
        updater.submit();
        updater.startWork();
        
        // Convert A2A -> DialogueMessage -> route internally
        DialogueMessage msg = converter.fromA2A(context.getMessage());
        // Subscribe for reply before sending to avoid races
        CompletableFuture<Message> replyFuture = new CompletableFuture<>();
        var subscription = messageDispatcher.subscribeRecipient(externalSenderId,
                m -> { replyFuture.complete(m); return CompletableFuture.completedFuture(null); });
        try {
            messageDispatcher.sendTo(msg.toMessage());
            Message response = replyFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // Convert response -> A2A
            updater.addArtifact(converter.toA2AParts(response), null, null, null);
        } finally {
            subscription.unsubscribe();
        }
        updater.complete();
    }
}
```

**Client-side** (call external agents):
```java
// Uses io.a2a.client.Client
public class AgenorA2AClient {
    
    private final Client a2aClient;
    
    public CompletableFuture<DialogueMessage> sendToExternal(
            String agentUrl, DialogueMessage msg) {
        Message a2aMessage = converter.toA2AMessage(msg);
        // SDK handles transport, streaming, etc.
        return a2aClient.sendMessage(a2aMessage)
            .thenApply(converter::fromA2AResponse);
    }
}
```

**Rationale**:
- Official SDK: https://github.com/a2aproject/a2a-java (Apache 2.0)
- Supports JSON-RPC, gRPC, REST transports
- Built-in streaming, push notifications
- TCK available for compliance testing
- No custom protocol code needed

#### 5. Package Structure

```
agenor-core/
└── dialogue/
    ├── Performative.java           # Enum (10 values)
    ├── DialogueMessage.java        # Record wrapping Message
    ├── Conversation.java           # Interface
    ├── ConversationManager.java    # Interface
    ├── Commitment.java             # Interface
    ├── CommitmentState.java        # Enum
    ├── CommitmentTracker.java      # Interface
    └── protocol/
        ├── Protocol.java           # Interface
        └── ProtocolState.java      # Enum

agenor-runtime/
└── dialogue/
    ├── DefaultConversationManager.java
    ├── DefaultCommitmentTracker.java
    └── protocol/
        ├── RequestProtocol.java
        ├── QueryProtocol.java
        └── ContractNetProtocol.java

agenor-adapters/
└── a2a/
    ├── AgenorA2AAdapter.java         # Coordination/configuration
    ├── AgenorAgentExecutor.java      # Implements AgentExecutor (SDK)
    ├── AgenorAgentCardProducer.java  # Produces AgentCard for internal agents
    ├── AgenorA2AClient.java          # Wraps SDK Client
    └── DialogueA2AConverter.java     # DialogueMessage <-> A2A Message/Task
```

## Consequences

### Positive

1. **Zero overhead for internal communication**: Uses existing `MessageDispatcher`
2. **Industry-standard interoperability**: A2A bridge for external agents
3. **No protocol maintenance**: A2A SDK handles protocol complexity
4. **Simplified implementation**: SDK provides Client, Server, models
5. **Clear separation**: Internal semantics vs external wire protocol
6. **Optional external communication**: A2A bridge only when needed
7. **Observable semantics**: Commitment tracking for auditability
8. **TCK available**: A2A SDK includes test compatibility kit

### Negative

1. **A2A SDK dependency**: Additional dependency for external communication
2. **Two mental models**: Dialogue (internal) vs A2A (external)
3. **Conversion overhead**: DialogueMessage ↔ A2A Message at boundary

### Known Limitations

**REQUEST two-phase response**: The FIPA REQUEST protocol defines a two-step reply sequence:
`REQUEST → AGREE → INFORM`. The current implementation of `ConversationManager.request()`
and `AgenorA2AAdapter.send()` resolves the returned `CompletableFuture` on the **first**
reply received (AGREE), because both `AGREE` and `INFORM` set `inReplyTo = REQUEST.id` and
the pending-response map is keyed on `inReplyTo`. The INFORM message arrives after the
future is already resolved and is silently discarded.

Callers that need the final result should either:
- Use `sendWithStreaming()` to receive status updates including the INFORM
- Have the responder send only one reply (collapse AGREE + INFORM into a single INFORM)

A future ADR will decide whether to formalize fire-and-first-reply as the canonical
contract, or to introduce a chained `inReplyTo` (INFORM.inReplyTo = AGREE.id) that
allows `ConversationManager` to track the full sequence.

> **Resolved by ADR-026** (2026-07-23): adopts fire-and-final semantics via a targeted gate
> on the existing `Protocol` FSM. See ADR-026 for the decision and rationale.

**`Protocol.isValid()` is never called**: the FSM describes which performatives are allowed in
each state, but `DefaultConversation.addMessage()` only calls `nextState(...)`, which returns
the current state unchanged for an unexpected performative. A protocol violation therefore
produces no state change, no error and no log line — the framework detects the malformed
exchange and discards that knowledge.

**Non-dialogue messages are reinterpreted as dialogue**: `DialogueMessage.fromMessage()` defaults
a missing `performative` header to `INFORM` and fabricates a `conversationId`. Because a
`BaseAgent` with dialogue holds two subscriptions on its own recipient channel (its own
auto-subscription plus `DialogueCapability`'s), a plain `sendTo()` message is delivered to the
dialogue path as well and becomes a synthetic `INFORM` in a brand-new conversation — which is
also dispatched to any matching `@DialogueHandler`.

> **Resolved by ADR-029** (2026-08-15): validation is enforced at warning level from the
> sender's perspective, and a pure static `DialogueMessage.isDialogueMessage(Message)`
> classifier keeps non-dialogue traffic off the dialogue path. ADR-029 also records why the
> synthetic conversations were a resource leak the 0.26.0 retention sweep could not reach.

**Dialogue shares one correlation namespace with ad-hoc request/reply** (open, 2026-08-16):
`DialogueMessage.toMessage()`/`fromMessage()` map `inReplyTo` onto `Message.correlationId()` —
the same field the codebase uses for non-dialogue request/reply. Functionally consistent, and
not a defect: the two never collide in practice because ids are UUIDs. It is recorded as a
**layering** note, because it shares a root cause with the double-subscription problem ADR-029
fixed — dialogue is a convention layered on the generic envelope rather than a channel of its
own. Anything that later needs true isolation between dialogue and ad-hoc traffic starts here.

**The common REQUEST shape still needs boilerplate** (open, 2026-08-16): ADR-026 resolves the
caller's future on the *final* reply, which is the right default, but an initiator that wants to
observe the intermediate `AGREE` as progress must reach for
`getConversationManager().onMessage(...)`. "AGREE for progress, INFORM for the result" is the
common case and deserves a first-class API rather than a documented workaround. Not scheduled —
recorded so the next person to touch `DialogueCapability` has the option in front of them.

### Neutral

1. **No custom wire protocol**: Delegates to A2A SDK (intentional)
2. **Limited to A2A ecosystem**: Other protocols require additional bridges

## Comparison: What We Build vs What We Adopt

| Component | Build (Agenor) | Adopt (External) |
|-----------|----------------|------------------|
| Performatives | ✅ 10 core | - |
| Conversation tracking | ✅ | - |
| Commitment semantics | ✅ | - |
| Protocol FSM | ✅ | - |
| Internal transport | - | ✅ MessageDispatcher (`DirectMessenger` / `TopicPublisher`) |
| External protocol | - | ✅ A2A (via SDK) |
| Wire format | - | ✅ A2A JSON |
| Agent discovery (external) | - | ✅ A2A Agent Cards |
| A2A Client/Server | - | ✅ `a2a-java-sdk` |

## References

1. **A2A Java SDK**: https://github.com/a2aproject/a2a-java (Official SDK)
2. A2A Protocol Specification: https://a2a-protocol.org/
3. ACP: https://agentcommunicationprotocol.dev/introduction/welcome
4. FIPA ACL (historical): https://web.archive.org/web/20250719195949/http://www.fipa.org/specs/fipa00061/

## Related ADRs

- ADR-001: Interface-First Design
- ADR-007: LLMProvider as Core Interface

---

## Amendment — 2026-09-01: a commitment's roles come from the protocol, not the performative

**Status**: Accepted. Amends §3 *Observable Commitment Tracking*.

### What this fills

§3 decided *that* commitments exist and what they expose, and gave the tracker the signature
`Commitment createFromMessage(DialogueMessage message)`. That signature carries an assumption it
never states: **that a message alone determines who is bound.** It does not, and the
implementation built on it read the performative and nothing else:

```java
case REQUEST, QUERY, CFP -> { requester = sender; performer = receiver; }
case AGREE, PROPOSE      -> { performer = sender; requester = receiver; }
```

That is the request-shaped reading — A requests, B agrees, B performs — and it is correct for
`RequestProtocol` and `QueryProtocol`. **Contract Net reverses it.** There the initiator sends
`AGREE` to *accept* a proposal, so the party taking on the work is the receiver. Running the
contract-net example and reading the tracker showed a manager holding an active commitment in
which it was itself the performer of the task it had just delegated, with the acceptance string
`"You win!"` as the content. `getActiveAsRequester("manager")` — documented as "commitments I'm
waiting on" — returned nothing.

### D-A1 — `Protocol` answers the question

`Protocol` gains `boolean senderPerforms(Performative)`, a `default` method returning the
request-shaped reading, plus the static `Protocol.senderPerformsByDefault(Performative)` so an
implementation can override some performatives and defer the rest. `ContractNetProtocol`
overrides it: `PROPOSE` binds its sender, `CFP` and `AGREE` bind the receiver.

This is not a new mechanism. **ADR-029 D5** established that `allowedPerformatives(state,
isInitiator)` is read from the perspective of the sending party, that the perspective is
*derivable* rather than guessable, and that "inventing one would reintroduce the guessing this
ADR removes". Direction is a property of the protocol; a second consumer of that fact belongs on
the same interface. It also gives `ContractNetProtocol` a reason to be consulted beyond
validation, and lets a protocol written by a user participate without touching the tracker.

Alternatives rejected:

- **Switch on the protocol id inside `DefaultCommitmentTracker`.** Smaller diff, no public
  interface touched — and it hardcodes the built-in protocol names in the tracker, excludes any
  user-defined protocol, and puts protocol knowledge in a second place that can drift from the
  first.
- **An explicit API where the caller declares who commits.** No heuristic can then be wrong, but
  it moves the bookkeeping onto the user, which is the opposite of what §3 offers, and would
  make "commitments track obligations created during dialogue" false in a new way.
- **Retiring the commitment model.** It has no readers outside the framework today. It is also
  the piece §3 justifies as solving "FIPA's unverifiable BDI semantics problem", the
  documentation teaches it, and it was one method away from being right.

### D-A2 — one registry per capability

`DefaultCommitmentTracker` now takes the `ProtocolRegistry` that resolves a message's protocol.
`DialogueCapability` and `DefaultConversationManager` hand it the same instance they use
themselves, because two registries would let the manager's answer and the tracker's answer
diverge. A tracker constructed without one keeps the request-shaped reading for every message,
which is exactly the behaviour that preceded this amendment.

### Known limitations, deliberately not addressed here

Both were found with the defect above and are separate decisions, not omissions:

1. **The record is one-sided.** Each agent holds its own `CommitmentTracker`, and only the
   *sender* of a committing message creates an entry. After a contract-net `AGREE` the manager
   knows it is owed work; `worker-2`, which took the work on, holds nothing. Whether the
   receiving side should mirror the commitment, or whether the tracker should be shared, is a
   question about ownership of state and is not answered here.
2. **Two of the four committing performatives never commit.**
   `Performative.createsCommitment()` reports `REQUEST`, `PROPOSE`, `CFP` and `AGREE`, and
   `createFromMessage` handles all four — but commitments are created at exactly two call sites,
   guarded by `AGREE` and by `REQUEST`. A participant's `PROPOSE` creates nothing. Making it
   create something is not a one-line change: a contract net with *n* proposals would open *n*
   commitments of which one is honoured, and there is today no rule that discharges the losers.

### Correction to §3's listing

§3 sketches `getDebtor()` / `getCreditor()` and a single `getActiveAsDebtor(String)`. What was
built and shipped is `getPerformer()` / `getRequester()` and the pair `getActiveAsPerformer` /
`getActiveAsRequester`. The rename carries no semantic change, but §3 has described an interface
that does not exist since the layer was first implemented; this records it rather than editing
the original decision.
