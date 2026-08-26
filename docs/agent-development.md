# Agent Development Guide

This guide shows how to build agents, behaviors, and message handlers with Agenor.

## Prerequisites
- Java 21+
- Maven 3.9+
- Add dependencies:
  - `dev.agenor:agenor-core`
  - `dev.agenor:agenor-runtime`
  - `dev.agenor:agenor-runtime-scanning` — required if you use `scanPackage(...)` for classpath-based agent discovery (ADR-027); not needed if you register agents directly with `registerAgent(Agent)`.

## Create Your First Agent

```java
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.Message;
import dev.agenor.runtime.agent.BaseAgent;

import static dev.agenor.core.BehaviorType.CYCLIC;

@Agent("hello-agent")
public class HelloAgent extends BaseAgent {

    @Behavior(type = CYCLIC, interval = "5s")
    public void sayHello() {
        getMessageDispatcher().publish(Message.builder()
                .topic("greetings")
                .content("Hello from " + getAgentId())
                .build());
    }

    @AgenorMessageHandler("greetings")
    public void handleGreeting(Message message) {
        log.info("Received: {}", message.getContent());
    }
}
```

## Bootstrapping the Runtime

```java
import dev.agenor.runtime.AgenorRuntime;

public class App {
    public static void main(String[] args) {
        var runtime = AgenorRuntime.builder()
                .scanPackage("com.example.agents")
                .build();

        runtime.start();
    }
}
```

## Agent Lifecycle

Every `BaseAgent` exposes two overridable hooks that are called by the runtime during startup and shutdown:

```java
@Agent("my-agent")
public class MyAgent extends BaseAgent {

    @Override
    protected void onStart() {
        // Called after services are injected and behaviors are registered.
        // Safe to use getMessageDispatcher(), agentDirectory, memoryStore here.
        log.info("Agent {} is starting", getAgentId());
    }

    @Override
    protected void onStop() {
        // Called before behaviors are stopped and the agent is unregistered.
        // Use this to flush state, close connections, etc.
        log.info("Agent {} is stopping", getAgentId());
    }
}
```

`onStart()` is invoked after all services have been injected; `onStop()` is invoked before behavior teardown and directory unregistration.

### LifecycleListener and LoggingLifecycleListener

The runtime uses a `LifecycleManager` that tracks status transitions (STARTING → RUNNING → STOPPING → STOPPED) and notifies registered `LifecycleListener` implementations:

```java
// LifecycleListener functional interface
LifecycleListener listener = (agentId, oldStatus, newStatus) ->
    System.out.printf("Agent %s: %s → %s%n", agentId, oldStatus, newStatus);

lifecycleManager.addLifecycleListener(listener);

// Built-in SLF4J-based listener
lifecycleManager.addLifecycleListener(LifecycleListener.logging());
// equivalent to:
lifecycleManager.addLifecycleListener(new LoggingLifecycleListener());
```

`LoggingLifecycleListener` logs every status change at INFO level via SLF4J. Use it during development or as a reference for custom implementations.

## Behaviors

Supported behavior types:
- Cyclic: run at a fixed interval (`agenor-runtime`)
- One-shot: run once and complete (`agenor-runtime`)
- Event-driven: react to incoming messages (`agenor-runtime`)
- Waker: run once after a delay (`agenor-runtime`)
- Composite: sequential, parallel, FSM (`agenor-runtime-ext`, optional — see ADR-027)
- Advanced: conditional, throttled, batch, retry, circuit breaker, scheduled, pipeline (`agenor-runtime-ext`, optional — see ADR-027)

Annotate public methods on your agent class with `@Behavior` and use `BehaviorType` plus optional timing parameters like `interval` or `delay`.

## Message Handling

Use `@AgenorMessageHandler("topic")` on public methods that accept a `Message` parameter. The in-memory message service will deliver matching topic messages within the JVM.

## LLM Agents — LLMAgent

`LLMAgent` extends `BaseAgent` with conversation history management, context window budgeting, and long-term fact storage. Use it instead of `BaseAgent` when your agent needs to interact with an LLM.

**When to prefer `LLMAgent` over `BaseAgent`:**
- The agent must maintain a conversation history across turns.
- The agent needs to inject relevant past facts into the LLM prompt.
- You want auto-summarization of long conversations.

### Minimal Example

```java
@Agent("chat-bot")
public class ChatBot extends LLMAgent {

    @Override
    protected void onStart() {
        // Add a system prompt at startup
        if (hasLLMMemory()) {
            addConversationMessage(LLMMessage.system("You are a helpful assistant.")).join();
        }
    }

    @AgenorMessageHandler("user.message")
    public void handleUserMessage(Message msg) {
        String userInput = msg.getContent(String.class);

        // Record the user turn
        addConversationMessage(LLMMessage.user(userInput)).join();

        // Build a prompt that respects the context window budget
        List<LLMMessage> prompt = buildLLMPrompt(userInput, 2000).join();

        // Call your LLM provider
        String reply = myProvider.chat(LLMRequest.builder().messages(prompt).build())
                                 .join().content();

        // Record the assistant turn
        addConversationMessage(LLMMessage.assistant(reply)).join();

        getMessageDispatcher().publish(Message.builder().topic("bot.response").content(reply).build());
    }
}
```

### Key Methods

| Method | Description |
|--------|-------------|
| `addConversationMessage(LLMMessage)` | Append a message to the conversation history |
| `buildLLMPrompt(query, maxTokens)` | Build a prompt list applying the configured context window strategy |
| `storeFact(key, content)` | Store a fact in long-term LLM memory |
| `retrieveFacts(query, maxTokens)` | Retrieve relevant facts by semantic similarity |
| `hasLLMMemory()` | Guard: returns `true` only if `LLMMemoryManager` was injected |

### Configuring LLMMemoryManager

The runtime injects a `LLMMemoryManager` if one is registered. You can also pass it programmatically:

```java
var memoryManager = new DefaultLLMMemoryManager(memoryStore);
chatBot.setLLMMemoryManager(memoryManager);
```

Tune the budgets and strategy inside `onStart()`:

```java
@Override
protected void onStart() {
    setDefaultStrategy(ContextWindowStrategies.SLIDING);
    setDefaultConversationBudget(3000);  // tokens reserved for conversation
    setDefaultContextBudget(800);        // tokens reserved for retrieved facts
    configureAutoSummarization(6000, 15); // summarize after 6000 tokens, batch 15 msgs
}
```

For the complete LLM guide see [`docs/llm-integration.md`](llm-integration.md).

## Message Filters

Filters allow fine-grained control over which messages an agent or subscription receives. All filter classes are in `dev.agenor.runtime.filter` and implement `MessageFilter` (a `Predicate<Message>`).

### TopicFilter — filter by topic pattern

```java
MessageFilter exact   = TopicFilter.exact("orders.confirmed");
MessageFilter prefix  = TopicFilter.startsWith("sensor.");
MessageFilter wild    = TopicFilter.wildcard("sensor.alert.*"); // * = any chars
MessageFilter pattern = TopicFilter.regex("^orders\\.(confirmed|cancelled)$");
```

### HeaderFilter — filter by header key/value

```java
MessageFilter highPri = HeaderFilter.equals("priority", "HIGH");
MessageFilter hasAuth = HeaderFilter.exists("Authorization");
MessageFilter allowed = HeaderFilter.in("region", "EU", "US");
MessageFilter prefix  = HeaderFilter.startsWith("tenant", "acme-");
MessageFilter regex   = HeaderFilter.matches("version", "\\d+\\.\\d+");
```

### ContentFilter — filter by message content

```java
MessageFilter typed     = ContentFilter.ofType(OrderData.class);
MessageFilter nonNull   = ContentFilter.notNull();
MessageFilter custom    = ContentFilter.matching(obj -> obj instanceof String s && s.length() > 0);
```

### Any predicate

```java
MessageFilter recent = MessageFilter.of(
    msg -> msg.timestamp().isAfter(Instant.now().minusSeconds(60))
);
```

### Combining filters

Every `MessageFilter` combines with any other:

```java
MessageFilter combined = TopicFilter.startsWith("order.")
    .and(HeaderFilter.equals("priority", "HIGH"));

MessageFilter either = TopicFilter.exact("alert")
    .or(TopicFilter.exact("warning"));

MessageFilter notTest = HeaderFilter.equals("env", "test").negate();
```

### Registering a filter on a subscription

Filters are applied at subscription time via `FilterableSubscriber.subscribeFiltered(filter, handler)`. The in-memory dispatcher implements this capability; cast when needed:

```java
@Override
protected void onStart() {
    MessageFilter filter = TopicFilter.startsWith("orders.")
        .and(HeaderFilter.equals("priority", "HIGH"));
    ((FilterableSubscriber) getMessageDispatcher()).subscribeFiltered(filter, msg -> handleHighPriorityOrder(msg));
}
```

## Dialogue and Protocols

Agenor supports structured agent communication through `DialogueCapability`. Attach it to any `BaseAgent` via composition — it registers itself on the agent's start/stop hooks, so no lifecycle wiring is needed. An agent that implements `Agent` directly must call `dialogue.initialize()` from its `start()` and `dialogue.shutdown()` from its `stop()`.

```java
@Agent("coordinator")
public class CoordinatorAgent extends BaseAgent {

    private final DialogueCapability dialogue = new DialogueCapability(this);

    // Respond to incoming REQUEST performatives
    @DialogueHandler(performatives = {Performative.REQUEST})
    public void handleRequest(DialogueMessage msg) {
        String content = (String) msg.content();
        // ... process ...
        dialogue.inform(msg, "Done: " + content).join();
    }

    // Initiate a request to another agent
    public void askWorker(String workerId, String task) {
        dialogue.request(workerId, task)
                .thenAccept(reply -> log.info("Worker replied: {}", reply.content()));
    }
}
```

Supported performatives: `REQUEST`, `QUERY`, `INFORM`, `AGREE`, `REFUSE`, `FAILURE`, `PROPOSE`, `ACCEPT_PROPOSAL`, `REJECT_PROPOSAL`, `CFP`.

For the full protocol reference see [`docs/dialog-protocol.md`](dialog-protocol.md).

## Persistence and Annotations

### @Persist — mark fields for automatic persistence

```java
@Agent("order-processor")
public class OrderProcessorAgent extends BaseAgent {

    @Persist
    private int processedCount = 0;

    @Persist("customer_id")   // explicit key in persisted state
    private String customerId;

    @Persist(required = true) // fail fast on restore if missing
    private String sessionToken;

    @Persist(encrypted = true) // encrypted at rest
    private String apiKey;
}
```

The runtime reads all `@Persist` fields when saving state and restores them on reload. Use `value` to stabilise the schema key across renames.

### @PersistenceConfig — configure save strategy at class level

```java
@Agent("critical-agent")
@PersistenceConfig(
    strategy         = PersistenceStrategy.PERIODIC,
    interval         = "30s",
    autoSnapshot     = true,
    snapshotInterval = "1h",
    maxSnapshots     = 24
)
public class CriticalAgent extends BaseAgent {
    @Persist(required = true)
    private String currentOrderId;
}
```

Available strategies: `MANUAL`, `PERIODIC`, `ON_STOP`, `DEBOUNCED`.

When the annotation is absent the default is `MANUAL` — the agent must call `persistState()` explicitly.

### FilePersistenceService and PersistenceManager

For programmatic use outside the annotation system:

```java
FilePersistenceService persistence = new FilePersistenceService(Path.of("data/agents"));

// Save any serializable state
persistence.save("agent-123", Map.of("count", 42, "status", "active"));

// Load state back
Map<String, Object> state = persistence.load("agent-123");

// Via the higher-level PersistenceManager
PersistenceManager manager = new PersistenceManager(persistence);
manager.persist(myAgent);        // save @Persist fields
manager.restore(myAgent);        // restore @Persist fields
```

## Configuration
- Minimal code-based configuration via `AgenorRuntime.builder()`
- Optional YAML support via `ConfigurationLoader` (see Configuration Reference)

## Testing
- Use JUnit 5 and Mockito/AssertJ.
- For unit tests, instantiate your agent and invoke behavior methods directly.
- For integration-like tests, bootstrap a `AgenorRuntime` with a small package and verify message exchanges.

## Examples
See `agenor-examples`:
- Ping/Pong basic messaging
- Weather Station cyclic producer
- Task Manager event-driven processing
- Advanced: Conditional and Throttled behaviors
- Filtering examples
- E-Commerce orchestration demo
- Discovery examples

## Next Steps
- Read the Architecture Guide (`docs/architecture.md`)
- LLM integration: `docs/llm-integration.md`
- Dialogue protocol detail: `docs/dialog-protocol.md`
