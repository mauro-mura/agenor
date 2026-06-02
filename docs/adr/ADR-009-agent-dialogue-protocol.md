# ADR-009: Agent Dialogue Protocol

**Status**: Accepted  
**Date**: 2025-12-13  
**Last Modified**: 2026-05-17  
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

**Decision**: `DialogueMessage` wraps existing `Message`, adding performative semantics. No new transport.

```java
public record DialogueMessage(
    Message message,
    Performative performative,
    String conversationId,
    String inReplyTo
) {
    public Message toMessage() {
        // Encode dialogue metadata in headers
        return Message.builder()
            .from(message)
            .header("dialogue.performative", performative.name())
            .header("dialogue.conversationId", conversationId)
            .header("dialogue.inReplyTo", inReplyTo)
            .build();
    }
    
    public static DialogueMessage fromMessage(Message msg) {
        // Parse from headers
    }
}
```

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
