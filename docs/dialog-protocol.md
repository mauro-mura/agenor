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
│                        AGENOR CORE                          │
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
│                      AGENOR RUNTIME                         │
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
│                     AGENOR ADAPTERS                         │
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

    // No lifecycle wiring needed: the capability registers itself on the agent's
    // start/stop hooks when it is constructed (since 0.26.0)
    private final DialogueCapability dialogue = new DialogueCapability(this);

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

> **Agents that do not extend `BaseAgent`.** Auto-wiring works through the
> `LifecycleHooks` interface, which `BaseAgent` implements. An agent implementing
> `Agent` directly (or one that overrides `start()`/`stop()` wholesale instead of
> `onStart()`/`onStop()`) must do the wiring itself: call `dialogue.initialize()` from
> its `start()` and `dialogue.shutdown()` from its `stop()`. Skipping `shutdown()`
> leaks its inbound registration and its retention sweep thread for the lifetime of the
> process. Calling `initialize()` twice is harmless — it is idempotent, so mixing
> automatic and manual wiring is safe.

### How dialogue receives messages

Since **0.27.0**, dialogue does not hold a subscription of its own on a `BaseAgent`. The
agent's [mailbox](mailbox.md) owns the single subscription to its recipient channel and
routes each message to exactly one consumer: anything carrying a known performative goes to
dialogue, everything else goes to the direct-message path. `initialize()` registers that
consumer and `shutdown()` withdraws it.

Two consequences worth knowing:

- **A dialogue message no longer also reaches `onDirectMessage()`.** ADR-029 D3 allowed that
  fallthrough; ADR-032 amends it in scope. An agent that watched its own dialogue traffic from
  `onDirectMessage()` must use a `@DialogueHandler` instead.
- **An agent that is not a `BaseAgent` has no mailbox**, so dialogue subscribes to the
  recipient channel directly, exactly as before, and its own classifier guard still applies.

### Handlers on a base class

`@DialogueHandler` methods are discovered across the whole class hierarchy, so shared
handlers can live on an abstract base agent. An override of an annotated method is
registered once, and the subclass version is the one invoked.

### 2. Send Requests

```java
public class Example {
    public void example(DialogueCapability dialogue) {
        // Simple request — the future resolves on the final INFORM/FAILURE,
        // never on an intermediate AGREE (ADR-026)
        dialogue.request("other-agent", taskData)
            .thenAccept(response -> {
                if (response.performative() == Performative.INFORM) {
                    // Success!
                    var result = response.content();
                }
            });

        // To observe an intermediate AGREE (e.g. for progress reporting),
        // register a listener instead of relying on the request() future
        dialogue.getConversationManager()
            .onMessage(conversationId, msg -> {
                if (msg.performative() == Performative.AGREE) {
                    // Request accepted, work is starting
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
- `AWAITING_RESPONSE` → INFORM → `COMPLETED` (responder collapsing AGREE + INFORM)
- `AWAITING_RESPONSE` → FAILURE → `FAILED` (responder failing without agreeing first)
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

### Protocol violations are reported, not enforced

Every message added to a conversation that has a protocol is checked against it. A performative
the protocol does not allow in the current state is logged at WARN, naming the conversation, the
sender, the state and what that state allows:

```
Protocol violation in conversation conv-42 (request): agent-b sent INFORM in state INITIATED,
which allows [REQUEST]
```

Nothing is rejected: the message still enters the history and the state machine still runs, so a
non-compliant peer degrades exactly as before — you just find out about it. The check is made
from the **sender's** point of view, so a normal reply is never flagged.

Two legitimate situations do produce a warning, and they are not bugs: a reply that arrives after
the initiator's timeout (state `TIMEOUT`) or after `cancel()` (state `CANCELLED`). Neither state
allows anything, because the conversation is over — the reply really did arrive out of protocol.

A conversation with no protocol is never checked; there is no state machine to check against.

## Commitments

Commitments track obligations created during dialogue:

```java
// Check active commitments as performer
var myCommitments = dialogue.getCommitmentTracker()
    .getActiveAsPerformer(agentId);

// Check commitments I'm waiting on
var waitingFor = dialogue.getCommitmentTracker()
    .getActiveAsRequester(agentId);

// Force an immediate violation check (deadline exceeded).
// You rarely need this: the retention sweep already runs it — see below.
var violations = dialogue.getCommitmentTracker()
    .checkViolations();
```

While the capability is initialized, its background sweep marks overdue commitments as
`VIOLATED` on its own and logs each one at WARN. Detection is delayed by at most one sweep
interval (default one minute):

```java
private final DialogueCapability dialogue = DialogueCapability.builder(this)
    .sweepInterval(Duration.ofSeconds(5))   // tighter violation detection
    .build();
```

The same sweep drops terminated commitments once they are older than the retention window
(default 5 minutes), so poll `get(commitmentId)` within that window or raise `retention()`.
Pruning runs before the violation check, so a commitment that has just been marked `VIOLATED`
stays readable for at least one full interval.

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

### Exposing Agenor Agent as A2A Server

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
agenor-core/
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

agenor-runtime/
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

agenor-adapters/
└── a2a/
    ├── AgenorA2AAdapter.java       # Main routing
    ├── AgenorA2AClient.java        # External client
    ├── AgenorAgentExecutor.java    # Server-side
    ├── DialogueA2AConverter.java   # Conversion
    └── A2AAdapterConfig.java       # Configuration
```

## Examples

See `agenor-examples/src/main/java/dev/agenor/examples/dialogue/`:

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

## Conversation state: what it is and how long it lives

Conversation state is **per-agent**, not a shared blackboard. Every `DialogueCapability`
owns its own `ConversationManager`, `CommitmentTracker` and handler registry; nothing is
shared between agents in the same JVM except the transport (`MessageDispatcher`). There is
no central place to query "all conversations in the runtime" — each agent tracks the
dialogues it takes part in, from its own point of view.

### Two different questions, two different answers

"Node-local" describes where the *state* lives, not how far a dialogue can reach. Keep the
two apart (ADR-031):

| | Answer |
|---|---|
| **Can agents in different runtimes hold a dialogue?** | **Yes**, and always could. A dialogue is an exchange of `Message`s over whatever `MessageDispatcher` the agent holds — with Redis messaging (ADR-021) or the A2A adapter, the peer can be in another process, on another host. |
| **Does an agent's conversation state survive that agent's restart?** | **No**, by design. |

### Restart and failover semantics

Conversation state is **held in memory, in the agent's own process**. When an agent
restarts:

- every in-flight conversation it was tracking is gone, and so are its commitments — nothing
  will mark an abandoned commitment `VIOLATED` after the restart;
- the peer is **not** notified. It waits out its own timeout and its future completes
  exceptionally — that timeout *is* the failover mechanism, and it is armed on every
  `request()`, `query()` and `callForProposals()`;
- a `CompletableFuture` the restarted agent was going to complete cannot be recovered by any
  means, since it was a live object in a process that no longer exists.

So **do not model anything durable on top of a `Conversation` or a `Commitment`**. If a
business process must survive a restart, keep it in the agent's own persistent state
(`@Persist`, or the HITL approval queue) and use dialogue to carry its steps, not to record
them. Dialogue is built for exchanges measured in seconds to minutes, not for long-running
sagas.

This is a contract, not a limitation waiting to be fixed: `ConversationManager` is an
interface and `DialogueCapability` takes it through a factory, so a persistent implementation
can be added later without changing any agent code — see ADR-031 for why one has not been
built.

Conversations and commitments that have reached a terminal state are swept automatically
(default: every minute, keeping the last 5 minutes), so long-lived agents do not accumulate
them. The window is configurable:

```java
DialogueCapability dialogue = DialogueCapability.builder(this)
    .retention(Duration.ofMinutes(30))
    .sweepInterval(Duration.ofMinutes(5))
    .build();
```

## Mixing dialogue with plain direct messages

An agent can use `@AgenorMessageHandler`/`onDirectMessage()` and dialogue at the same time.
Both read the same recipient channel, so the framework has to decide which path each inbound
message belongs to. The rule is a single pure function:

```java
DialogueMessage.isDialogueMessage(message)   // performative header present and recognised
```

A message that does not qualify is **never** turned into a conversation: it is left to the
direct-message path. A message whose `performative` header holds a value this runtime does not
know is treated the same way and logged at WARN, rather than being executed as an `INFORM` its
sender never sent.

The reverse is not filtered: a dialogue message **also** reaches `onDirectMessage()`, because an
agent without a `DialogueCapability` must still be able to receive it. If you override
`onDirectMessage()` on an agent that speaks dialogue, skip dialogue traffic yourself:

```java
@Override
protected void onDirectMessage(Message message) {
    if (DialogueMessage.isDialogueMessage(message)) {
        return;   // handled by @DialogueHandler
    }
    ...
}
```

See ADR-029 for the reasoning behind the asymmetry.

## Swapping the conversation manager

`DialogueCapability` depends only on the `ConversationManager` and `CommitmentTracker`
interfaces. An alternative implementation — or a `ProtocolRegistry` carrying a custom
`Protocol` — is supplied through the same builder, without forking anything:

```java
DialogueCapability dialogue = DialogueCapability.builder(this)
    .protocolRegistry(registryWithMyProtocol)
    .conversationManagerFactory(MyConversationManager::new)
    .build();
```

## Best Practices

1. **Let DialogueCapability wire itself** — override `onStart()`/`onStop()` only for your
   own logic. If your agent is not a `BaseAgent`, call `initialize()`/`shutdown()` yourself
2. **Use appropriate timeouts for async operations**
3. **Handle all response types (INFORM, REFUSE, FAILURE)**
4. **Track commitments for long-running operations**
5. **Use protocol-specific handlers when possible**
6. **Prefer composition (DialogueCapability) over inheritance**

## Version History

- **0.26.0** - Lifecycle and resource-management hardening
  - `DialogueCapability` self-registers via `LifecycleHooks`; `initialize()` is idempotent
    and resolves the dispatcher from the agent (`initialize(MessageDispatcher)` deprecated)
  - Injectable `ConversationManager` / `ProtocolRegistry` / `CommitmentTracker` via
    `DialogueCapability.builder(agent)`
  - Terminated conversations, commitments and timed-out pending responses no longer
    accumulate for the lifetime of the agent
  - `@DialogueHandler` methods inherited from a base class are now discovered
- **0.5.0** - Initial dialogue protocol implementation
  - Core types and interfaces
  - Three protocol implementations
  - Agent integration via DialogueCapability
  - A2A adapter for external communication
