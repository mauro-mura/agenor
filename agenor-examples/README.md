# Agenor Examples

Runnable examples for the Agenor multi-agent framework, organized as a learning path
from the absolute basics to production-grade systems.

## Prerequisites

- Java 21+
- Maven 3.9+

```bash
mvn clean install -DskipTests
```

## Running an example

```bash
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.<ClassName>"
```

---

## Learning Path

### Level 0 — First steps (5 min each)

Start here. Every example fits in a single file and introduces one concept.

| Example | Main class | Concept |
|---------|-----------|---------|
| `PingPongExample` | `dev.agenor.examples.PingPongExample` | Two agents exchanging messages |
| `WeatherStationExample` | `dev.agenor.examples.WeatherStationExample` | Cyclic behavior + topic pub/sub |
| `TaskManagerExample` | `dev.agenor.examples.TaskManagerExample` | Agent state + task lifecycle |

---

### Level 1 — Core behaviors

One behavior type per example, all self-contained.

| Example | Main class | Behavior |
|---------|-----------|---------|
| `ThrottledExample` | `dev.agenor.examples.behaviors.ThrottledExample` | `@Behavior(type = THROTTLED)` rate limiting |
| `ConditionalBehaviorExample` | `dev.agenor.examples.behaviors.ConditionalBehaviorExample` | `CONDITIONAL` — runs only when a condition is true |
| `RetryExample` | `dev.agenor.examples.behaviors.RetryExample` | `RetryBehavior` with exponential/linear/jitter/fixed backoff |
| `CircuitBreakerExample` | `dev.agenor.examples.behaviors.CircuitBreakerExample` | `CIRCUIT_BREAKER` — open/half-open/closed state machine |
| `BatchProcessingExample` | `dev.agenor.examples.behaviors.BatchProcessingExample` | `BATCH` — buffer → drain → write |
| `ScheduledExample` | `dev.agenor.examples.behaviors.ScheduledExample` | `ScheduledBehavior` cron expressions + timezone |
| `PipelineExample` | `dev.agenor.examples.behaviors.PipelineExample` | `PIPELINE` — sequential processing stages |

---

### Level 2 — Messaging patterns

| Example | Main class | Pattern |
|---------|-----------|---------|
| `MessageFilterExample` | `dev.agenor.examples.filtering.MessageFilterExample` | Topic wildcards, predicate filters |
| `RequestProtocolExample` | `dev.agenor.examples.dialogue.RequestProtocolExample` | Request/reply |
| `QueryProtocolExample` | `dev.agenor.examples.dialogue.QueryProtocolExample` | Query/inform |
| `ContractNetExample` | `dev.agenor.examples.dialogue.ContractNetExample` | CFP → propose → accept |

---

### Level 3 — Agent discovery

| Example | Main class | Mechanism |
|---------|-----------|---------|
| `DiscoveryExample` | `dev.agenor.examples.DiscoveryExample` | `scanPackages` auto-discovery |
| `ChatAgentExample` | `dev.agenor.examples.agent.ChatAgentExample` | `AgentDirectory` queries + direct routing |

---

### Level 4 — LLM integration

These examples run for free by default against a **local Ollama** instance —
no signup, no API key, no env var needed. This holds even if you also have
`OPENAI_API_KEY`/`GROQ_API_KEY`/`ANTHROPIC_API_KEY` sitting in your shell:
local Ollama is always used unless you explicitly opt into a different
backend with `LLM_BACKEND`.

| `LLM_BACKEND` | Requires | Cost | Notes |
|----------------|----------|------|-------|
| _(unset)_ / `ollama` | — | **Free, local** | Default. Install from [ollama.com](https://ollama.com), then `ollama pull llama3.2`. Override host/model with `OLLAMA_BASE_URL`/`OLLAMA_MODEL`. |
| `groq` | `GROQ_API_KEY` | **Free tier** | Cloud, OpenAI-compatible endpoint, fast, supports function calling. Get a key at [console.groq.com](https://console.groq.com/keys). |
| `openai` | `OPENAI_API_KEY` | Paid | |
| `anthropic` | `ANTHROPIC_API_KEY` | Paid | |

Example: `LLM_BACKEND=groq GROQ_API_KEY=... mvn exec:java -pl agenor-examples ...`

Optional model overrides: `OPENAI_MODEL`, `GROQ_MODEL`, `ANTHROPIC_MODEL`,
`OLLAMA_MODEL`. See `dev.agenor.examples.llm.ExampleLLMProvider` for the
selection logic used by every example below.

> **Function-calling caveat:** Agenor's Ollama adapter doesn't wire up tool
> calling, so examples that demonstrate `FunctionDefinition`/tool use
> (`AIAssistantExample`, the capability/fault-tolerance research-team
> examples, `McpExample`'s LLM round-trip) will run under the local Ollama
> default but won't actually invoke tools. Set `LLM_BACKEND=groq` (free) or
> `LLM_BACKEND=openai`/`anthropic` to see the full function-calling demo.

| Example | Main class | Pattern |
|---------|-----------|---------|
| `LLMProviderExample` | `dev.agenor.examples.llm.LLMProviderExample` | Raw `LLMProvider` API |
| `CustomerSupportExample` | `dev.agenor.examples.llm.CustomerSupportExample` | LLM-driven intent routing |
| `AIAssistantExample` | `dev.agenor.examples.llm.tools.AIAssistantExample` | Function calling / tool use |
| `LLMDirectMessagingExample` | `dev.agenor.examples.llm.LLMDirectMessagingExample` | Manual registration + point-to-point direct messaging |
| `LLMCapabilityDiscoveryExample` | `dev.agenor.examples.llm.capabilities.LLMCapabilityDiscoveryExample` | `scanPackages` + `AgentDirectory` capability queries |
| `LLMFaultToleranceExample` | `dev.agenor.examples.llm.dynamic_discovery.LLMFaultToleranceExample` | Dynamic discovery + fault tolerance (agent stops mid-run) |
| `ReflectionExample` | `dev.agenor.examples.behaviors.ReflectionExample` | ReflectionBehavior — Generate → Critique → Revise loop |

The three `LLM*` examples use the same research-team domain intentionally — comparing
them side by side shows how the same problem is solved with different discovery patterns.

---

### Level 5 — Production systems

End-to-end examples that combine multiple patterns.

| Example | Main class | Demonstrates |
|---------|-----------|-------------|
| `ECommerceApplication` | `dev.agenor.examples.ecommerce.ECommerceApplication` | FSM order lifecycle + parallel validators + sequential fulfillment |
| `SupportChatbotExample` | `dev.agenor.examples.support.SupportChatbotExample` | LLM + RAG + multi-agent synthesis + A2A protocol — [details](src/main/java/dev/agenor/examples/support/README.md) |
| `EvaluationFrameworkExample` | `dev.agenor.examples.eval.EvaluationFrameworkExample` | Agent evaluation harness |

---

### Level 6 — Tooling

| Example | Main class | Tool |
|---------|-----------|------|
| `CLIExample` | `dev.agenor.examples.cli.CLIExample` | Web console + CLI (`agenor list`, `agenor status`, `agenor logs -f`) |
| `WebConsoleExample` | `dev.agenor.examples.console.WebConsoleExample` | Embedded Jetty dashboard |
| `A2AIntegrationExample` | `dev.agenor.examples.a2a.A2AIntegrationExample` | Agent-to-Agent HTTP protocol |
| `UserPreferenceMemoryExample` | `dev.agenor.examples.memory.UserPreferenceMemoryExample` | Agent memory / persistence |

---

## Package map

```
dev.agenor.examples
├── PingPongExample            ← start here
├── WeatherStationExample
├── TaskManagerExample
├── DiscoveryExample
├── behaviors/
│   ├── ThrottledExample
│   ├── ConditionalBehaviorExample
│   ├── RetryExample
│   ├── CircuitBreakerExample
│   ├── BatchProcessingExample
│   ├── ScheduledExample
│   └── PipelineExample
├── agent/                     ChatAgentExample
├── filtering/                 MessageFilterExample
├── dialogue/                  ContractNet, Query, Request protocols
├── llm/                       LLMDirectMessagingExample, CustomerSupport, LLMProviderExample, AIAssistant
│   ├── capabilities/          LLMCapabilityDiscoveryExample
│   └── dynamic_discovery/     LLMFaultToleranceExample
├── ecommerce/                 ECommerceApplication
├── support/                   SupportChatbotExample  (see support/README.md)
├── eval/                      EvaluationFrameworkExample
├── cli/                       CLIExample
├── console/                   WebConsoleExample
├── a2a/                       A2AIntegrationExample
└── memory/                    UserPreferenceMemoryExample
```

## Adding a new example

1. Place the file under the appropriate package (or create a new one at the right level).
2. Use `registerAgent()` for self-contained examples; `scanPackages()` only when
   auto-discovery is the concept being demonstrated.
3. Agent classes that are only used by one example should be `public static` inner classes.
4. Add a row to the table in this README at the correct level.
