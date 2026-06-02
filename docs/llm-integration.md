# LLM Integration Guide

This guide covers the complete integration of Large Language Models into Jentic agents: provider setup, the core API, function calling, LLM-aware agents, memory management, error handling, and testing patterns.

The LLM subsystem spans three modules:

- **`jentic-core`** (`dev.agenor.core.llm`, `dev.agenor.core.memory.llm`) — provider-agnostic interfaces and records
- **`jentic-adapters`** (`dev.agenor.adapters.llm`) — concrete provider implementations
- **`jentic-runtime`** (`dev.agenor.runtime.agent.LLMAgent`, `dev.agenor.runtime.memory.llm`) — LLM-aware base agent and memory management

---

## Package Overview

```
jentic-core / dev.agenor.core.llm
├── LLMProvider.java             # Core interface
├── LLMRequest.java              # Immutable request (builder)
├── LLMResponse.java             # Response with content + usage
├── LLMMessage.java              # Single conversation message
├── StreamingChunk.java          # Chunk for streaming responses
├── FunctionDefinition.java      # Declare callable functions
├── FunctionCall.java            # LLM's function call request
├── LLMMemoryAware.java          # Injection marker interface (since 0.10.0)
└── LLMException.java            # Typed error hierarchy

jentic-core / dev.agenor.core.memory.llm
├── LLMMemoryManager.java        # Interface for memory management
├── ContextWindowStrategy.java   # Strategy interface
└── TokenEstimator.java          # Token counting interface

jentic-adapters / dev.agenor.adapters.llm
├── LLMProviderFactory.java      # Factory (recommended entry point)
├── openai/OpenAIProvider.java
├── anthropic/AnthropicProvider.java
├── ollama/OllamaProvider.java
└── ToolConversionUtils.java     # FunctionDefinition → vendor schema

jentic-runtime / dev.agenor.runtime.agent
└── LLMAgent.java                # LLM-powered base agent

jentic-runtime / dev.agenor.runtime.memory.llm
├── DefaultLLMMemoryManager.java
├── ContextWindowStrategies.java # FIXED, SLIDING, SUMMARIZED constants
├── FixedWindowStrategy.java
├── SlidingWindowStrategy.java
├── SummarizationStrategy.java
├── TokenBudgetManager.java
├── ModelTokenLimits.java        # Known model context sizes
└── SimpleTokenEstimator.java
```

---

## Available Providers

### LLMProviderFactory (recommended)

`LLMProviderFactory` is the recommended entry point. It returns typed builders for each provider:

```java
// OpenAI
LLMProvider openai = LLMProviderFactory.openai()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")           // default: gpt-4o
    .temperature(0.7)
    .maxTokens(2000)
    .timeout(Duration.ofSeconds(60))
    .build();

// Anthropic
LLMProvider anthropic = LLMProviderFactory.anthropic()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-3-5-sonnet-20241022")  // default
    .temperature(0.7)
    .maxTokens(4096)
    .build();

// Ollama (no API key needed)
LLMProvider ollama = LLMProviderFactory.ollama()
    .baseUrl("http://localhost:11434")  // default
    .modelName("llama3.2")
    .temperature(0.8)
    .timeout(Duration.ofMinutes(2))
    .build();
```

### Environment variables

| Provider | Variable | Notes |
|----------|----------|-------|
| OpenAI | `OPENAI_API_KEY` | Required |
| Anthropic | `ANTHROPIC_API_KEY` | Required |
| Ollama | — | Local server, no key |

### Direct instantiation

The builders are also accessible directly via `new OpenAIProvider.Builder()`, `new AnthropicProvider.Builder()`, or `new OllamaProvider.Builder()` but `LLMProviderFactory` is preferred.

---

## LLMProvider API

```java
public interface LLMProvider {
    // Blocking async chat — returns a single response
    CompletableFuture<LLMResponse> chat(LLMRequest request);

    // Streaming — calls chunkHandler for each arriving token
    CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> chunkHandler);

    // List models this provider reports as available
    CompletableFuture<List<String>> getAvailableModels();

    String getProviderName();
    boolean supportsFunctionCalling();
    boolean supportsStreaming();
}
```

All methods return `CompletableFuture` for non-blocking execution. Providers are thread-safe and can be shared.

---

## Request and Response

### Building LLMRequest

Since 0.16.0 the `model` field is **optional**. When omitted, the provider uses its configured
model (set via `modelName(...)` at construction time). Supply an explicit model only when you
want to route a specific request to a different model than the provider default.

```java
// Common case — model inferred from provider configuration
LLMRequest request = LLMRequest.builder()
    .systemMessage("You are a helpful assistant.")
    .userMessage("What is the capital of France?")
    .temperature(0.7)
    .maxTokens(200)
    .build();

// Explicit per-request override — routes this request to a different model
LLMRequest expensiveRequest = LLMRequest.builder()
    .model("o3")
    .userMessage("Prove the Riemann hypothesis.")
    .build();
```

For multi-turn conversations, pass the full message list:

```java
List<LLMMessage> history = new ArrayList<>();
history.add(LLMMessage.system("You are a helpful assistant."));
history.add(LLMMessage.user("What is photosynthesis?"));

LLMRequest request = LLMRequest.builder()
    .messages(history)
    .build();
```

### LLMMessage roles

```java
LLMMessage.system("You are an assistant.");  // SYSTEM
LLMMessage.user("Hello!");                   // USER
LLMMessage.assistant("Hi there!");           // ASSISTANT
LLMMessage.function("fn_name", resultJson);  // FUNCTION (tool result)
```

### Reading LLMResponse

```java
provider.chat(request).thenAccept(response -> {
    System.out.println(response.content());
    System.out.println("Model: " + response.model());
    System.out.println("Tokens: " + response.usage().totalTokens());
    System.out.println("Finish: " + response.finishReason());
});
```

### Streaming with StreamingChunk

```java
provider.chatStream(request, chunk -> {
    if (chunk.hasContent()) {
        System.out.print(chunk.content());
    }
    if (chunk.isLast()) {
        System.out.println("\n[done]");
    }
}).join();
```

---

## Function Calling

All three providers support function/tool calling.

### Define a function

```java
FunctionDefinition weather = FunctionDefinition.builder("get_weather")
    .description("Get current weather for a city")
    .stringParameter("location", "City name, e.g. Paris", true)
    .enumParameter("unit", "Temperature unit", false, "celsius", "fahrenheit")
    .build();
```

### Send a request with functions

```java
LLMRequest request = LLMRequest.builder()
    .userMessage("What's the weather in Paris?")
    .addFunction(weather)
    .build();
```

### Handle function calls in the response

```java
provider.chat(request).thenAccept(response -> {
    if (response.hasFunctionCalls()) {
        FunctionCall call = response.functionCalls().get(0);
        String location = call.getStringArgument("location");  // "Paris"
        String unit     = call.getStringArgument("unit");      // "celsius" or null

        // Execute the function
        String result = fetchWeather(location, unit);

        // Continue conversation with the function result
        LLMRequest followUp = LLMRequest.builder()
            .messages(request.messages())
            .addMessage(response.toMessage())
            .addMessage(LLMMessage.function("get_weather", result))
            .build();

        provider.chat(followUp).thenAccept(final_ ->
            System.out.println(final_.content())
        );
    } else {
        System.out.println(response.content());
    }
});
```

`ToolConversionUtils` handles the conversion from `FunctionDefinition` to each provider's specific JSON schema format automatically.

---

## LLMAgent — Agents Based on LLM

`LLMAgent` (in `jentic-runtime`) extends `BaseAgent` with conversation history and context window management. **Extend `LLMAgent` instead of `BaseAgent`** whenever your agent needs to interact with an LLM.

If the agent already extends a domain superclass and cannot extend `LLMAgent`, implement `LLMMemoryAware` (in `dev.agenor.core.llm`) instead. The runtime detects this interface and injects a `LLMMemoryManager` automatically, exactly as it does for `LLMAgent`:

```java
@Agent("my-domain-agent")
public class MyDomainAgent extends DomainClass implements Agent, LLMMemoryAware {

    private LLMMemoryManager llmMemoryManager;

    public MyDomainAgent(AgentContext ctx) { ... }

    @Override
    public void setLLMMemoryManager(LLMMemoryManager manager) {
        this.llmMemoryManager = manager;
    }

    @AgenorMessageHandler("my.topic")
    public void handle(Message msg) {
        // llmMemoryManager is ready here
    }
}
```

`LLMAgent` itself implements `LLMMemoryAware` — no changes required for existing subclasses.

```java
@Agent("customer-support")
public class SupportAgent extends LLMAgent {

    private final LLMProvider provider;

    public SupportAgent() {
        this.provider = LLMProviderFactory.openai()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o")
            .build();
    }

    @Override
    protected void onStart() {
        // Configure context window budget
        setDefaultStrategy(ContextWindowStrategies.SLIDING);
        setDefaultConversationBudget(3000);
        setDefaultContextBudget(800);
        configureAutoSummarization(6000, 15);

        // Seed with a system message
        if (hasLLMMemory()) {
            addConversationMessage(
                LLMMessage.system("You are a customer support agent.")
            ).join();
        }
    }

    @AgenorMessageHandler("support.query")
    public void handleQuery(Message msg) {
        String question = msg.getContent(String.class);

        // Add the user turn
        addConversationMessage(LLMMessage.user(question)).join();

        // Build a prompt that fits the configured token budget
        List<LLMMessage> prompt = buildLLMPrompt(question, 2000).join();

        // Call the LLM
        String answer = provider.chat(
            LLMRequest.builder().messages(prompt).build()
        ).join().content();

        // Record the assistant turn
        addConversationMessage(LLMMessage.assistant(answer)).join();

        // Store a key fact from the conversation
        storeFact("last-query", question).join();

        messageService.send(Message.builder()
            .topic("support.response")
            .content(answer)
            .build());
    }
}
```

### Key LLMAgent methods

| Method | Description |
|--------|-------------|
| `addConversationMessage(LLMMessage)` | Append a message to conversation history |
| `addConversationMessages(List<LLMMessage>)` | Append multiple messages |
| `buildLLMPrompt(query, maxTokens)` | Build prompt list applying context window strategy |
| `storeFact(key, content)` | Store a fact in long-term memory |
| `retrieveFacts(query, maxTokens)` | Retrieve relevant long-term facts |
| `clearConversationHistory()` | Clear short-term history (long-term facts preserved) |
| `getConversationMessageCount()` | Current number of messages in history |
| `getConversationTokens()` | Estimated token count of the history |
| `hasLLMMemory()` | Guard: true only if `LLMMemoryManager` is injected |
| `setDefaultStrategy(strategy)` | Override context window strategy |
| `setDefaultConversationBudget(tokens)` | Token budget for conversation history |
| `setDefaultContextBudget(tokens)` | Token budget for retrieved facts |
| `configureAutoSummarization(threshold, batchSize)` | Auto-summarize when threshold exceeded |

---

## LLM Memory Management

### DefaultLLMMemoryManager

`DefaultLLMMemoryManager` is the `LLMMemoryManager` implementation provided by `jentic-runtime`. It wires together a `MemoryStore` (for persistence), a `TokenEstimator`, and an in-memory conversation list.

```java
MemoryStore store         = new InMemoryStore();
TokenEstimator estimator  = new SimpleTokenEstimator();
LLMMemoryManager memory   = new DefaultLLMMemoryManager(store, estimator, "my-agent");

// Add messages
memory.addMessage(LLMMessage.user("Hello!")).join();
memory.addMessage(LLMMessage.assistant("Hi! How can I help?")).join();

// Retrieve with token budget and strategy
List<LLMMessage> prompt = memory.getConversationHistory(
    2000, ContextWindowStrategies.SLIDING
).join();

// Store a long-term fact
memory.remember("user-name", "Alice", Map.of("category", "profile")).join();

// Retrieve relevant context
List<MemoryEntry> facts = memory.retrieveRelevantContext("user preferences", 500).join();
```

### Context Window Strategies

`ContextWindowStrategies` (in `jentic-runtime`) exposes three pre-built strategy constants:

| Constant | Algorithm | Requires LLM | Best For |
|----------|-----------|--------------|---------|
| `ContextWindowStrategies.FIXED` | Last N messages that fit in budget | No | Short conversations |
| `ContextWindowStrategies.SLIDING` | Recent + important older messages (scored) | No | Long conversations |
| `ContextWindowStrategies.SUMMARIZED` | Recent + LLM-generated summary of older messages | Yes | Very long conversations |

**SLIDING scoring** prioritises: system messages, messages with function calls, longer messages, user messages over assistant messages.

Lookup by name at runtime:

```java
ContextWindowStrategy strategy = ContextWindowStrategies.forName("sliding");
```

### Custom strategy

Implement the `ContextWindowStrategy` interface:

```java
public class ImportanceStrategy implements ContextWindowStrategy {
    @Override
    public List<LLMMessage> selectMessages(List<LLMMessage> all, int maxTokens, TokenEstimator est) {
        // custom selection logic
    }

    @Override
    public String getName() { return "importance"; }
}
```

### TokenBudgetManager and ModelTokenLimits

`ModelTokenLimits` provides a registry of known model context window sizes:

```java
int limit = ModelTokenLimits.getLimit("gpt-4o");    // 128_000
int limit = ModelTokenLimits.getLimit("claude-3-opus-20240229"); // 200_000
int limit = ModelTokenLimits.getLimit("unknown");    // DEFAULT_LIMIT (4096)

// Register a custom model
ModelTokenLimits.register("my-fine-tuned-model", 16_384);
```

`TokenBudgetManager` uses `ModelTokenLimits` and a `TokenEstimator` to compute safe allocation splits between conversation history and retrieved facts.

### Injecting LLMMemoryManager into the runtime

Register it via `JenticRuntime.builder()` so that it is automatically injected into `LLMAgent` instances:

```java
var memory = new DefaultLLMMemoryManager(
    new InMemoryStore(),
    new SimpleTokenEstimator(),
    "shared-memory"
);

var runtime = JenticRuntime.builder()
    .scanPackage("com.example.agents")
    .llmMemoryManager(memory)
    .build();
```

Alternatively, set it directly on a specific agent:

```java
mySupportAgent.setLLMMemoryManager(memory);
```

---

## Error Handling (LLMException)

All LLM errors extend `LLMException`. Use `getErrorType()` to branch on the cause:

```java
provider.chat(request).exceptionally(ex -> {
    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;

    if (cause instanceof LLMException llmEx) {
        switch (llmEx.getErrorType()) {
            case RATE_LIMIT -> {
                log.warn("Rate limit hit on {}", llmEx.getProvider());
                // Schedule retry with exponential backoff
            }
            case AUTHENTICATION -> {
                log.error("Invalid API key for provider: {}", llmEx.getProvider());
            }
            case CONTEXT_LENGTH_EXCEEDED -> {
                log.warn("Prompt too long, trimming history");
                // Reduce maxTokens or clear history
            }
            case NETWORK -> {
                log.warn("Network error, retrying...");
                // Use RetryBehavior or manual retry
            }
            case QUOTA_EXCEEDED -> {
                log.error("Quota exhausted for provider: {}", llmEx.getProvider());
            }
            default -> log.error("LLM error [{}]: {}", llmEx.getErrorType(), llmEx.getMessage());
        }
    }
    return null;
});
```

### Full ErrorType enum

`INVALID_REQUEST`, `AUTHENTICATION`, `RATE_LIMIT`, `QUOTA_EXCEEDED`, `MODEL_NOT_FOUND`, `NETWORK`, `PARSE_ERROR`, `SERVER_ERROR`, `CONTEXT_LENGTH_EXCEEDED`, `CONTENT_FILTERED`, `UNSUPPORTED_OPERATION`, `UNKNOWN`.

### Retry pattern

Pair `LLMException` with `RetryBehavior` for automatic back-off:

```java
Behavior retrying = RetryBehavior.builder(myLLMBehavior)
    .maxAttempts(3)
    .backoff(Duration.ofSeconds(2))
    .retryOn(ex -> ex.getCause() instanceof LLMException llmEx
                   && llmEx.getErrorType() == LLMException.ErrorType.RATE_LIMIT)
    .build();
addBehavior(retrying);
```

---

## Provider Configuration Reference

### OpenAI builder options

| Method | Default | Description |
|--------|---------|-------------|
| `apiKey(String)` | — | Required. `OPENAI_API_KEY` |
| `modelName(String)` | `"gpt-4o"` | Model identifier |
| `baseUrl(String)` | OpenAI default | Custom endpoint |
| `temperature(Double)` | `0.7` | 0.0–2.0 |
| `maxTokens(Integer)` | `2000` | Max output tokens |
| `timeout(Duration)` | `60s` | Request timeout |
| `logRequests(boolean)` | `false` | Log request payload |
| `logResponses(boolean)` | `false` | Log response payload |

Available models: `gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo`, `gpt-4`, `gpt-3.5-turbo`.

### Anthropic builder options

Same fields. Default model: `claude-3-5-sonnet-20241022`.

Available models: `claude-3-7-sonnet-20250219`, `claude-3-5-sonnet-20241022`, `claude-3-5-haiku-20241022`, `claude-3-opus-20240229`, `claude-3-sonnet-20240229`, `claude-3-haiku-20240307`.

### Ollama builder options

| Method | Default | Description |
|--------|---------|-------------|
| `baseUrl(String)` | `http://localhost:11434` | Ollama server URL |
| `modelName(String)` | — | Required. Local model name |
| `temperature(Double)` | `0.7` | |
| `timeout(Duration)` | `5m` | Longer for large local models |
| `logRequests/logResponses` | `false` | |

No API key required. Streaming is supported.

---

## Testing

Use a mock `LLMProvider` to test agent logic without real API calls:

```java
public class MockLLMProvider implements LLMProvider {

    private final String fixedResponse;

    public MockLLMProvider(String fixedResponse) {
        this.fixedResponse = fixedResponse;
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.completedFuture(
            LLMResponse.builder("mock-id", "test-model")
                .content(fixedResponse)
                .role(LLMMessage.Role.ASSISTANT)
                .build()
        );
    }

    @Override
    public CompletableFuture<Void> chatStream(LLMRequest req, Consumer<StreamingChunk> h) {
        h.accept(StreamingChunk.of("s", "test-model", fixedResponse, 0));
        h.accept(StreamingChunk.of("s", "test-model", "", "stop", 1));
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletableFuture<List<String>> getAvailableModels() {
        return CompletableFuture.completedFuture(List.of("test-model"));
    }
    @Override public String getProviderName() { return "Mock"; }
    @Override public boolean supportsFunctionCalling() { return false; }
    @Override public boolean supportsStreaming() { return true; }
}
```

```java
@Test
void agentRespondsToUserMessage() {
    LLMProvider mock = new MockLLMProvider("Paris is the capital of France.");

    LLMRequest request = LLMRequest.builder()
        .userMessage("What is the capital of France?")
        .build();

    LLMResponse response = mock.chat(request).join();

    assertEquals("Paris is the capital of France.", response.content());
}
```

---

## See Also

- [Agent Development Guide](agent-development.md) — LLMAgent lifecycle and behaviors
- [Architecture Guide](architecture.md) — module overview and concurrency model
- [Dialogue Protocol](dialog-protocol.md) — A2A and structured agent communication
- `jentic-examples/README.md` — runnable LLM examples
