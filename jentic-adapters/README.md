# jentic-adapters

This module provides concrete implementations of interfaces defined in `jentic-core`. It bridges Jentic agents to external systems: LLM APIs and the A2A interoperability protocol.

---

## Contents

```
dev.jentic.adapters
├── llm/
│   ├── LLMProviderFactory.java      # Recommended entry point
│   ├── ToolConversionUtils.java     # FunctionDefinition → vendor JSON schema
│   ├── openai/
│   │   └── OpenAIProvider.java      # OpenAI chat + streaming + function calling
│   ├── anthropic/
│   │   └── AnthropicProvider.java   # Anthropic chat + streaming + function calling
│   └── ollama/
│       └── OllamaProvider.java      # Ollama (local) chat + streaming
└── a2a/
    ├── JenticA2AAdapter.java        # Smart router: internal vs external A2A
    ├── JenticA2AClient.java         # HTTP/JSON-RPC client for external A2A agents
    ├── JenticAgentExecutor.java     # Expose a Jentic agent as A2A server
    ├── A2AAdapterConfig.java        # Configuration + AgentCard builder
    └── DialogueA2AConverter.java    # DialogueMessage ↔ A2A Task/Message
```

---

## Maven dependency

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-adapters</artifactId>
    <version>${jentic.version}</version>
</dependency>
```

This module depends on `jentic-core` and brings in `langchain4j` (LLM transport) and the `io.a2a` Java SDK (A2A protocol).

---

## LLM Providers

### LLMProviderFactory — recommended entry point

```java
import dev.jentic.adapters.llm.LLMProviderFactory;
import dev.jentic.core.llm.LLMProvider;

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

// Ollama (local, no API key)
LLMProvider ollama = LLMProviderFactory.ollama()
    .baseUrl("http://localhost:11434")  // default
    .modelName("llama3.2")
    .timeout(Duration.ofMinutes(2))
    .build();
```

### Environment variables

| Provider | Variable | Notes |
|----------|----------|-------|
| OpenAI | `OPENAI_API_KEY` | Required |
| Anthropic | `ANTHROPIC_API_KEY` | Required |
| Ollama | — | Runs locally, no key |

### Provider capabilities

| Provider | Streaming | Function calling | Available models |
|----------|-----------|-----------------|-----------------|
| `OpenAIProvider` | ✅ | ✅ | gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-4, gpt-3.5-turbo |
| `AnthropicProvider` | ✅ | ✅ | claude-3-7-sonnet-20250219, claude-3-5-sonnet-20241022, claude-3-5-haiku-20241022, claude-3-opus-20240229, claude-3-haiku-20240307 |
| `OllamaProvider` | ✅ | ❌ | Any model installed locally |

### Builder options (all providers)

| Method | Default | Description |
|--------|---------|-------------|
| `apiKey(String)` | — | API key (required for OpenAI/Anthropic) |
| `modelName(String)` | see above | Model identifier |
| `baseUrl(String)` | provider default | Custom endpoint or Ollama server URL |
| `temperature(Double)` | `0.7` | Sampling temperature (0.0–2.0) |
| `maxTokens(Integer)` | `2000` (OpenAI/Ollama), `4096` (Anthropic) | Max output tokens |
| `timeout(Duration)` | `60s` | Request timeout |
| `logRequests(boolean)` | `false` | Log full request payload |
| `logResponses(boolean)` | `false` | Log full response payload |

### ToolConversionUtils

Converts Jentic `FunctionDefinition` objects to the vendor-specific JSON schema format required by each provider. Called internally by all three providers when `LLMRequest.hasFunctions()` is true. You do not normally need to use this class directly.

```java
// Used internally:
List<ToolSpecification> specs = ToolConversionUtils.convertFunctionsToToolSpecs(functions);
```

### Using LLMProvider directly

```java
LLMRequest request = LLMRequest.builder("gpt-4o")
    .systemMessage("You are a helpful assistant.")
    .userMessage("What is the capital of France?")
    .maxTokens(100)
    .build();

provider.chat(request).thenAccept(response -> {
    System.out.println(response.content());           // "Paris is the capital of France."
    System.out.println(response.usage().totalTokens()); // token count
});
```

For the full API — streaming, function calling, `LLMAgent`, memory management, error handling — see **[`docs/llm-integration.md`](../docs/llm-integration.md)**.

---

## A2A Adapter

The A2A adapter implements the [Google Agent-to-Agent (A2A) protocol](https://google.github.io/A2A/) using the official `io.a2a` Java SDK v0.3.2.Final.

### Architecture

```
External A2A Agent ←──────────────────────────────→ Jentic agent
(any framework)       JenticA2AAdapter               (BaseAgent subclass)
                        │
                        ├── isInternalAgent()?  → MessageService (direct)
                        └── isExternalA2AUrl()? → JenticA2AClient (HTTP/JSON-RPC)

Incoming A2A request ──→ JenticAgentExecutor ──→ MessageService ──→ Jentic agent
                         (A2A server side)
```

### JenticA2AAdapter — smart router

`JenticA2AAdapter` routes outgoing messages: if the `receiverId` matches a registered agent in `AgentDirectory` it sends internally via `MessageService`; if it looks like an HTTP/HTTPS URL it sends externally via `JenticA2AClient`.

```java
var adapter = new JenticA2AAdapter(
    messageService,
    agentDirectory,
    "my-agent-id",
    Duration.ofMinutes(5)
);

// Route to internal agent (auto-detected via AgentDirectory)
DialogueMessage response = adapter.send(DialogueMessage.builder()
    .receiverId("internal-agent")
    .performative(Performative.REQUEST)
    .content("Hello")
    .build()
).join();

// Route to external A2A agent
DialogueMessage response = adapter.send(DialogueMessage.builder()
    .receiverId("https://external-agent.example.com")
    .performative(Performative.QUERY)
    .content("What is the status?")
    .build()
).join();

// Streaming for long-running tasks
adapter.sendWithStreaming(message, (state, msg) ->
    log.info("Task state: {} — {}", state, msg)
).join();

// Fetch remote AgentCard (cached 10 min)
AgentCard card = adapter.getExternalAgentCard("https://external-agent.example.com").join();

// Validate connectivity to external agent
boolean reachable = adapter.validateExternalAgent("https://external-agent.example.com").join();
```

### JenticA2AClient — outbound HTTP client

`JenticA2AClient` handles raw HTTP/JSON-RPC communication with external A2A agents. Normally you use `JenticA2AAdapter` which wraps it; use `JenticA2AClient` directly only when you need fine-grained control.

```java
var client = new JenticA2AClient(Duration.ofMinutes(5));

// Send and receive
DialogueMessage response = client.send(
    "https://external-agent.example.com",
    outgoingMessage,
    "my-local-agent-id"
).join();

// Fetch AgentCard
AgentCard card = client.getAgentCard("https://external-agent.example.com").join();

// Check if URL is a valid A2A agent
boolean isA2A = client.isA2AAgent("https://some-service.example.com").join();
```

### JenticAgentExecutor — expose Jentic agent as A2A server

`JenticAgentExecutor` implements the A2A SDK `AgentExecutor` interface. It receives incoming A2A tasks, converts them to Jentic `DialogueMessage` objects, dispatches them to the target agent via `MessageService`, and maps the reply back to an A2A task result.

```java
// Standalone usage
AgentExecutor executor = new JenticAgentExecutor(
    "target-agent-id",   // internal Jentic agent to route requests to
    messageService,
    Duration.ofMinutes(5)
);
```

With CDI / Quarkus:

```java
@ApplicationScoped
public class MyExecutorProducer {

    @Inject
    MessageService messageService;

    @Produces
    public AgentExecutor createExecutor() {
        return new JenticAgentExecutor("my-agent", messageService, Duration.ofMinutes(5));
    }
}
```

Task states managed automatically: `SUBMITTED → WORKING → COMPLETED` (or `FAILED` if the Jentic agent replies with `Performative.FAILURE`/`REFUSE`). Cancellation is forwarded as `Performative.CANCEL`.

### A2AAdapterConfig — build an AgentCard

`A2AAdapterConfig` is a fluent configuration class that also produces an A2A `AgentCard` for service discovery.

```java
A2AAdapterConfig config = A2AAdapterConfig.create()
    .agentName("Order Processor")
    .agentDescription("Processes customer orders via A2A protocol")
    .baseUrl("https://my-service.example.com/a2a")
    .version("1.2.0")
    .streamingEnabled(true)
    .timeout(Duration.ofMinutes(10))
    .addSkill(new A2AAdapterConfig.SkillConfig(
        "process-order",
        "Process Order",
        "Processes a customer order end-to-end"
    ))
    .inputModes(List.of("text", "application/json"))
    .outputModes(List.of("text"));

AgentCard card = config.toAgentCard();
```

For a detailed guide to the A2A protocol and dialogue patterns see **[`docs/dialog-protocol.md`](../docs/dialog-protocol.md)**.

---

## See Also

- [`docs/llm-integration.md`](../docs/llm-integration.md) — complete LLM integration guide
- [`docs/dialog-protocol.md`](../docs/dialog-protocol.md) — dialogue protocol and A2A details
- [`docs/architecture.md`](../docs/architecture.md) — module overview
- [`jentic-examples/README.md`](../jentic-examples/README.md) — runnable examples
