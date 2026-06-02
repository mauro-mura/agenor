# Dialogue Protocol

> Semantic communication layer for agent-to-agent interaction.

## Overview

The Dialogue Protocol provides structured, meaningful communication between agents using:
- **Performatives** - Communicative acts (REQUEST, INFORM, AGREE, etc.)
- **Conversations** - Multi-turn dialogue tracking with state machines
- **Commitments** - Observable promises created during interaction
- **Protocols** - Predefined interaction patterns (Request, Query, Contract-Net)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        JENTIC CORE                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Performative│  │DialogueMsg  │  │ Conversation        │  │
│  │ (enum)      │  │ (record)    │  │ (interface)         │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Commitment  │  │ Protocol    │  │ @DialogueHandler    │  │
│  │ (interface) │  │ (interface) │  │ (annotation)        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                      JENTIC RUNTIME                         │
│  ┌────────────────────┐  ┌──────────────────┐               │
│  │ DefaultConversation│  │ DefaultCommitment│               │
│  │ Manager            │  │ Tracker          │               │
│  └────────────────────┘  └──────────────────┘               │
│  ┌───────────────────┐  ┌──────────────────┐                │
│  │ DialogueCapability│  │ DialogueHandler  │                │
│  │ (composition)     │  │ Registry         │                │
│  └───────────────────┘  └──────────────────┘                │
│  ┌────────────────────────────────────────┐                 │
│  │ Protocol Implementations               │                 │
│  │ • RequestProtocol                      │                 │
│  │ • QueryProtocol                        │                 │
│  │ • ContractNetProtocol                  │                 │
│  └────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                     JENTIC ADAPTERS                         │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ AgenorA2AAdapter │  │ AgenorA2AClient  │                 │
│  │ (routing)        │  │ (HTTP client)    │                 │
│  └──────────────────┘  └──────────────────┘                 │
│  ┌───────────────────┐  ┌────────────────────┐              │
│  │AgenorAgentExecutor│  │DialogueA2AConverter│              │
│  │ (server)          │  │ (conversion)       │              │
│  └───────────────────┘  └────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

## Performatives

10 communicative acts based on FIPA ACL:

| Performative | Purpose | Creates Commitment |
|--------------|---------|-------------------|
| `REQUEST` | Ask agent to perform action | Yes (pending) |
| `QUERY` | Ask for information | Yes (pending) |
| `INFORM` | Provide information/result | No |
| `AGREE` | Accept a request | Yes (active) |
| `REFUSE` | Decline a request | No |
| `FAILURE` | Report execution failure | No |
| `PROPOSE` | Submit a proposal | Yes (pending) |
| `CFP` | Call for proposals | Yes (pending) |
| `CANCEL` | Cancel ongoing action | No |
| `NOTIFY` | Asynchronous notification | No |

## Quick Start

### 1. Add DialogueCapability to Your Agent

```java
public class MyAgent extends BaseAgent {
    
    private final DialogueCapability dialogue = new DialogueCapability(this);
    
    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());
    }
    
    @Override
    protected void onStop() {
        dialogue.shutdown();
    }
    
    // Handle incoming requests
    @DialogueHandler(performatives = Performative.REQUEST)
    public void handleRequest(DialogueMessage msg) {
        // Process and respond
        dialogue.agree(msg);
        
        // ... do work ...
        
        dialogue.inform(msg, result);
    }
}
```

### 2. Send Requests

```java
public class Example {
    public void example(DialogueCapability dialogue) {
        // Simple request
        dialogue.request("other-agent", taskData)
            .thenAccept(response -> {
                if (response.performative() == Performative.INFORM) {
                    // Success!
                    var result = response.content();
                }
            });

        // Query with timeout
        dialogue.query("data-agent", "what is X?", Duration.ofSeconds(10))
            .thenAccept(response -> {
                String answer = response.content().toString();
            });
    }
}
```

### 3. Contract-Net (Multi-Party Negotiation)

```java
public class NegotiationExample {
    public void negotiate(DialogueCapability dialogue, List<String> workers, Object taskSpec) {
        // Manager broadcasts CFP
        var proposals = dialogue.callForProposals(
            workers,
            taskSpec,
            Duration.ofSeconds(30)
        ).join();

        // Select best proposal
        var best = proposals.stream()
            .filter(p -> p.performative() == Performative.PROPOSE)
            .min(Comparator.comparing(p -> p.content().toString())) // Example comparator
            .orElseThrow();

        // Accept winner
        dialogue.reply(best, Performative.AGREE, "You're selected");
    }
}
```

## Protocols

### Request Protocol

```
Initiator                    Participant
    │                            │
    │───── REQUEST ─────────────►│
    │                            │
    │◄──── AGREE/REFUSE ─────────│
    │                            │
    │◄──── INFORM/FAILURE ───────│
    │                            │
```

State transitions:
- `INITIATED` → REQUEST → `AWAITING_RESPONSE`
- `AWAITING_RESPONSE` → AGREE → `AGREED`
- `AWAITING_RESPONSE` → REFUSE → `REFUSED`
- `AGREED` → INFORM → `COMPLETED`
- `AGREED` → FAILURE → `FAILED`

### Query Protocol

```
Initiator                    Participant
    │                            │
    │───── QUERY ───────────────►│
    │                            │
    │◄──── INFORM/REFUSE ────────│
    │                            │
```

Direct response without AGREE phase.

### Contract-Net Protocol

```
Manager                      Workers (multiple)
    │                            │
    │───── CFP ─────────────────►│ (broadcast)
    │                            │
    │◄──── PROPOSE/REFUSE ───────│ (multiple responses)
    │                            │
    │───── AGREE ───────────────►│ (to selected worker)
    │                            │
    │◄──── INFORM/FAILURE ───────│
    │                            │
```

## Commitments

Commitments track obligations created during dialogue:

```java
// Check active commitments as performer
var myCommitments = dialogue.getCommitmentTracker()
    .getActiveAsPerformer(agentId);

// Check commitments I'm waiting on
var waitingFor = dialogue.getCommitmentTracker()
    .getActiveAsRequester(agentId);

// Detect violations (deadline exceeded)
var violations = dialogue.getCommitmentTracker()
    .checkViolations();
```

Commitment states:
- `PENDING` - Created but not yet accepted
- `ACTIVE` - Accepted, execution expected
- `FULFILLED` - Successfully completed
- `VIOLATED` - Deadline exceeded or broken
- `CANCELLED` - Cancelled by performer
- `RELEASED` - Released by requester

## A2A Integration

The adapter layer enables communication with external A2A agents:

```java
public class A2AExample {
    public void send(AgenorA2AAdapter adapter, Object data) {
        // Send to internal agent (auto-detected)
        adapter.send(DialogueMessage.builder()
            .receiverId("internal-agent")
            .performative(Performative.REQUEST)
            .content(data)
            .build());

        // Send to external A2A agent (URL)
        adapter.send(DialogueMessage.builder()
            .receiverId("https://external-agent.com")
            .performative(Performative.QUERY)
            .content("question")
            .build());
    }
}
```

### Exposing Jentic Agent as A2A Server

```java
public class A2AServerExample {
    public void execute(AgenorAgentExecutor executor, Object a2aRequest) {
        // Handle incoming A2A request
        executor.execute(a2aRequest, status -> {
            // Status updates: "working", "completed", "failed"
        });
    }
}
```

## Package Structure

```
jentic-core/
└── dialogue/
    ├── Performative.java           # Enum (10 values)
    ├── DialogueMessage.java        # Record wrapping Message
    ├── Conversation.java           # Interface
    ├── ConversationManager.java    # Interface  
    ├── Commitment.java             # Interface
    ├── CommitmentState.java        # Enum
    ├── CommitmentTracker.java      # Interface
    ├── DialogueHandler.java        # Annotation
    └── protocol/
        ├── Protocol.java           # Interface
        └── ProtocolState.java      # Enum

jentic-runtime/
└── dialogue/
    ├── DefaultConversation.java
    ├── DefaultConversationManager.java
    ├── DefaultCommitment.java
    ├── DefaultCommitmentTracker.java
    ├── DialogueCapability.java     # Agent composition
    ├── DialogueHandlerRegistry.java
    └── protocol/
        ├── RequestProtocol.java
        ├── QueryProtocol.java
        ├── ContractNetProtocol.java
        └── ProtocolRegistry.java

jentic-adapters/
└── a2a/
    ├── AgenorA2AAdapter.java       # Main routing
    ├── AgenorA2AClient.java        # External client
    ├── AgenorAgentExecutor.java    # Server-side
    ├── DialogueA2AConverter.java   # Conversion
    └── A2AAdapterConfig.java       # Configuration
```

## Examples

See `jentic-examples/src/main/java/dev/jentic/examples/dialogue/`:

- `RequestProtocolExample.java` - Request protocol (order processing)
- `QueryProtocolExample.java` - Query protocol (knowledge base)
- `ContractNetExample.java` - Contract-Net (multi-worker task allocation)

### Running Examples

```bash
# Request Protocol
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass="dev.agenor.examples.dialogue.RequestProtocolExample"

# Query Protocol
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass="dev.agenor.examples.dialogue.QueryProtocolExample"

# Contract-Net Protocol
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass="dev.agenor.examples.dialogue.ContractNetExample"
```

## Best Practices

1. **Always initialize DialogueCapability in onStart()**
2. **Use appropriate timeouts for async operations**
3. **Handle all response types (INFORM, REFUSE, FAILURE)**
4. **Track commitments for long-running operations**
5. **Use protocol-specific handlers when possible**
6. **Prefer composition (DialogueCapability) over inheritance**

## Version History

- **0.5.0** - Initial dialogue protocol implementation
  - Core types and interfaces
  - Three protocol implementations
  - Agent integration via DialogueCapability
  - A2A adapter for external communication
