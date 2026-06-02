# Agent Development Guide

This guide shows how to build agents, behaviors, and message handlers with Jentic.

## Prerequisites
- Java 21+
- Maven 3.9+
- Add dependencies:
  - `dev.agenor:jentic-core`
  - `dev.agenor:jentic-runtime`

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

Supported behavior types (see `jentic-runtime` implementations):
- Cyclic: run at a fixed interval
- One-shot: run once and complete
- Event-driven: react to incoming messages
- Waker: run once after a delay
- Composite: sequential, parallel, FSM (runtime support utilities)
- Advanced: conditional, throttled, batch, retry, circuit breaker, scheduled, pipeline

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

### PredicateFilter — arbitrary predicate on Message

```java
MessageFilter recent = new PredicateFilter(
    msg -> msg.timestamp().isAfter(Instant.now().minusSeconds(60)),
    "recent-messages"
);
```

### CompositeFilter — AND / OR / NOT

```java
MessageFilter combined = CompositeFilter.and(
    TopicFilter.startsWith("order."),
    HeaderFilter.equals("priority", "HIGH")
);

MessageFilter either = CompositeFilter.or(
    TopicFilter.exact("alert"),
    TopicFilter.exact("warning")
);

MessageFilter notTest = CompositeFilter.not(HeaderFilter.equals("env", "test"));
```

### Registering a filter on a subscription

Filters are applied at subscription time via `FilterableSubscriber.subscribeFiltered(filter, handler)`. The in-memory dispatcher implements this capability; cast when needed:

```java
@Override
protected void onStart() {
    MessageFilter filter = CompositeFilter.and(
        TopicFilter.startsWith("orders."),
        HeaderFilter.equals("priority", "HIGH")
    );
    ((FilterableSubscriber) getMessageDispatcher()).subscribeFiltered(filter, msg -> handleHighPriorityOrder(msg));
}
```

## Rate Limiting

Jentic provides two `RateLimiter` implementations in `dev.agenor.runtime.ratelimit`.

### SlidingWindowRateLimiter

Tracks request timestamps in a rolling time window. Good for smooth traffic shaping.

```java
RateLimit limit = RateLimit.of(100, Duration.ofSeconds(60)); // 100 req / 60 s
RateLimiter limiter = new SlidingWindowRateLimiter(limit);

if (limiter.tryAcquire()) {
    callApi();
} else {
    log.warn("Rate limit exceeded");
}

// Blocking acquire (waits until a permit is available)
limiter.acquire().join();

// Blocking with timeout
boolean acquired = limiter.acquire(Duration.ofSeconds(5)).join();
```

### TokenBucketRateLimiter

Classic token-bucket algorithm. Tokens refill at a steady rate, supporting short bursts.

```java
RateLimit limit = RateLimit.of(10, Duration.ofSeconds(1)); // 10 tokens/s
RateLimiter limiter = new TokenBucketRateLimiter(limit);

limiter.acquire().thenRun(this::processRequest);
```

### Integration with ThrottledBehavior

The `ThrottledBehavior` wrapper applies a `RateLimiter` to any existing behavior:

```java
RateLimiter limiter = new TokenBucketRateLimiter(RateLimit.of(5, Duration.ofSeconds(1)));
Behavior throttled = new ThrottledBehavior(myBehavior, limiter);
addBehavior(throttled);
```

## Conditions

Conditions are `Predicate<Agent>`-like objects (`dev.agenor.core.condition.Condition`) used to gate behavior execution at runtime. Jentic supplies three factory classes in `dev.agenor.runtime.condition`.

### AgentCondition — based on agent state

```java
Condition running    = AgentCondition.isRunning();
Condition hasStatus  = AgentCondition.hasStatus(AgentStatus.RUNNING);
Condition idPattern  = AgentCondition.idMatches("worker-.*");
Condition nameHas    = AgentCondition.nameContains("processor");
```

### SystemCondition — based on JVM / OS metrics

```java
Condition lowCpu    = SystemCondition.cpuBelow(70.0);     // CPU < 70 %
Condition lowMem    = SystemCondition.memoryBelow(80.0);   // heap < 80 %
Condition healthy   = SystemCondition.systemHealthy();     // cpu<80 && mem<80
Condition overload  = SystemCondition.systemUnderLoad();   // cpu>70 || mem>70
```

### TimeCondition — based on wall-clock time

```java
Condition business = TimeCondition.businessHours(); // 09:00 – 17:00
Condition weekday  = TimeCondition.weekday();
Condition weekend  = TimeCondition.weekend();
Condition morning  = TimeCondition.beforeHour(12);
Condition evening  = TimeCondition.afterHour(18);
```

### Using Conditions with ConditionalBehavior and WakerBehavior

```java
// Only run during business hours on weekdays
Condition condition = TimeCondition.businessHours().and(TimeCondition.weekday());

Behavior conditional = new ConditionalBehavior(myBehavior, condition);
addBehavior(conditional);

// WakerBehavior: fire once when condition becomes true
Behavior waker = new WakerBehavior(() -> notifyOnCall(), SystemCondition.systemHealthy());
addBehavior(waker);
```

Conditions also compose with `and()`, `or()`, `negate()` (default methods on `Condition`).

## Dialogue and Protocols

Jentic supports structured agent communication through `DialogueCapability`. Attach it to any `BaseAgent` via composition and initialise it in `onStart()`.

```java
@Agent("coordinator")
public class CoordinatorAgent extends BaseAgent {

    private final DialogueCapability dialogue = new DialogueCapability(this);

    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());
    }

    @Override
    protected void onStop() {
        dialogue.shutdown();
    }

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
See `jentic-examples`:
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
