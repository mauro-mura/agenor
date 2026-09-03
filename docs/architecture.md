# Architecture Guide

> **Multi-agent coordination for the JVM.** Autonomous agents that negotiate, delegate, and execute — with pluggable LLM reasoning when you need it.

This document describes Agenor's architecture: an interface-first, Java 21+ multi-agent framework with native support for MCP, A2A, Guardrails, and Human-in-the-Loop.

- **Audience**: developers evaluating or building on Agenor
- **Scope**: high-level structure, core abstractions, runtime behavior, and extension points

## 1. Architectural Overview

Agenor embraces an interface‑first, modular architecture. Core contracts live in agenor-core, while minimal, ready‑to‑use implementations live in agenor-runtime. Per ADR-027, LLM-aware pieces, extended behaviors/persistence, and classpath scanning were split out of agenor-runtime into agenor-runtime-llm, agenor-runtime-ext, and agenor-runtime-scanning respectively — each depends only on agenor-runtime, so a pure multi-agent-system consumer can depend on agenor-core + agenor-runtime alone. Adapters (LLM providers, A2A) live in agenor-adapters.

| Module | Holds | You name |
|--------|-------|----------|
| agenor-core | every contract, as an interface or a record | `Agent`, `Message`, `MessageDispatcher`, `directory.AgentDirectory` |
| agenor-runtime | the in-memory implementation of those contracts | `AgenorRuntime`, `BaseAgent` |
| agenor-runtime-llm | LLM-aware pieces (ADR-027) | `LLMAgent` |
| agenor-runtime-ext | extended pieces: store, HITL, composites (ADR-027) | `InMemoryStore`, `FSMBehavior` |
| agenor-runtime-scanning | classpath scanning, isolated for native-image (ADR-027) | — reached through `scanPackages` |
| agenor-adapters | LLM providers, MCP, A2A | `LLMProviderFactory` |

The right-hand column is the point: most of the framework is reached through something else, and
a type you never name is not a concept you had to learn.


Design goals:
- Start simple, scale smart (ADR-004)
- Interface‑first contracts (ADR-002)
- Modern Java 21, virtual threads (ADR-001)
- JSON record-based messages (ADR-005)
- Annotation-based configuration (ADR-006)

## 2. Modules

- agenor-bom: Bill of Materials for dependency version management across the framework.
- agenor-core: Pure interfaces, records, annotations, and exceptions. No heavy dependencies.
- agenor-runtime: Minimal, production‑ready in‑memory implementations to get started fast.
- agenor-runtime-llm (ADR-027): LLM-aware runtime pieces — `LLMAgent`, LLM memory, guardrails, reflection. Depends on agenor-runtime.
- agenor-runtime-ext (ADR-027): Extended runtime pieces — `InMemoryStore`, filters, persistence, composite behaviors, HITL, knowledge. Depends on agenor-runtime.
- agenor-runtime-scanning (ADR-027): Classpath scanning and DI-based agent discovery, isolated for GraalVM native-image friendliness. Depends on agenor-runtime.
- agenor-adapters: LLM providers and A2A adapter.
- agenor-adapters-persistence (ADR-022): JDBC-backed agent directory and persistent HITL approval queue.
- agenor-spring-boot-starter: Spring Boot 4.0.x auto-configuration for Agenor.
- agenor-examples: Demonstrates usage patterns and best practices.
- agenor-tools: Web console (Jetty) and CLI (PicoCLI) utilities.

## 3. Core Abstractions (agenor-core)

Five contracts carry the framework. Everything else in `agenor-core` supports one of them, and
is reached through it rather than named directly.

- **Agent**: Lifecycle contract for autonomous entities; exposes id, status, and context.
- **Behavior**: Unit of work owned by an Agent. Its type says when the work runs — `ONE_SHOT`,
  `CYCLIC`, `FSM` — and `BehaviorScheduler` drives it.
- **Message**: Transport-agnostic payload record (topic, headers, content, metadata).
- **MessageDispatcher** (since 0.20.0): Topic publish/subscribe and direct agent-to-agent
  messaging. Split into `TopicPublisher`, `TopicSubscriber`, `DirectMessenger` and
  `DirectReceiver` so a distributed backend can implement one capability at a time;
  `FilterableSubscriber` is a separate capability for predicate-based subscriptions.
- **directory.AgentDirectory** (since 0.20.0): Registration, resolution, discovery and presence,
  split the same way and for the same reason.

Annotations: `@Agent`, `@Behavior`, `@AgenorMessageHandler`, `@Persist`, `@PersistenceConfig`,
`@DialogueHandler`.

These are deliberately small to keep adapters swappable without breaking user code.

## 4. Runtime Implementations (agenor-runtime, agenor-runtime-llm, agenor-runtime-ext)

### Agent Base Classes

- **BaseAgent** (`agenor-runtime`): Convenience base class wiring message handling, behavior registration, services injection, and lifecycle hooks (`onStart()` / `onStop()`).
- **LLMAgent** (`agenor-runtime-llm`): Extends `BaseAgent` with conversation history management, context window budgeting, and long-term fact storage. Requires a `LLMMemoryManager` to be injected before start.

### Behaviors

A behavior's type answers when its work runs: `ONE_SHOT`, `CYCLIC`, and `FSM` for a state machine
that decides its own transitions. `SequentialBehavior` and `ParallelBehavior` compose children and
tell the scheduler how to drive them through `SchedulingHint` rather than through their type —
which is why that enum exists. See [Behaviors](behaviors/README.md), which also lists the concerns
that are deliberately not behavior types.

### Messaging

- **InMemoryMessageDispatcher** (since 0.20.0): Production implementation of `MessageDispatcher` and `FilterableSubscriber`. Delivers messages using virtual threads. Routes `sendTo` calls via `AgentResolver`; throws `AgentNotFoundException` for unknown agents. Emits `message.send` OTel spans. See [Messaging](messaging.md).

### Agent Directory and Scheduler

- **InMemoryAgentDirectory** (since 0.20.0): Implements `dev.agenor.core.directory.AgentDirectory` (all four capability interfaces). Assigns `AgentEndpoint.local(nodeId)` to newly registered agents automatically. Emits `directory.resolve` OTel spans. See [Agent Directory](directory.md).
- **SimpleBehaviorScheduler**: drives every scheduled behavior, honouring the initial delay a behavior declares before its first execution.
- **AgentScanner + AgentFactory** (`agenor-runtime-scanning`, optional): classpath scanning and DI-based construction for `createAgent(Class)`/`scanPackage(...)`.
- **AgentAnnotationProcessor** (`agenor-runtime`) + **ExtBehaviorAnnotationExtension** (`agenor-runtime-ext`, optional): wire `@Behavior`/`@AgenorMessageHandler` on any registered agent, independent of classpath scanning. Runs unconditionally; behavior types implemented in `agenor-runtime-ext` fail loudly if that module is absent.
- **AgenorRuntime**: Entry point to bootstrap, start, and stop the agent system.

### Memory

- **InMemoryStore** (`agenor-runtime-ext`): Thread-safe `MemoryStore` implementation backed by `ConcurrentHashMap`. Stores `MemoryEntry` objects with topic, scope (`SHORT_TERM` / `LONG_TERM`), content, and optional TTL. Does not persist to disk.
- **DefaultLLMMemoryManager** (`agenor-runtime-llm`): Bridges a `MemoryStore` and the LLM conversation history. Three context window strategies: `FixedWindow` keeps the N most recent messages, `SlidingWindow` keeps messages within a rolling token budget (the default), `Summarization` auto-summarizes older messages with an LLM call. See [Memory Management](memory.md).

### Filters (`agenor-runtime-ext`)

Filters select which messages a subscription receives — build them from `MessageFilter` in
`agenor-core` and pass them to `FilterableSubscriber.subscribeFiltered`. See
[Message Filtering](message-filtering.md).

### Dialogue

Package `dev.agenor.runtime.dialogue`:

- **DialogueCapability**: composable capability that adds full dialogue support to any `BaseAgent`. Provides `request()`, `query()`, `callForProposals()`, `reply()`, `agree()`, `refuse()`, `inform()`, `failure()`, `propose()`.
- **DefaultConversation**: tracks a single conversation's state and message history.
- **DefaultConversationManager**: manages all active conversations for an agent; implements the request/response, query, and Contract-Net flows.
- **DialogueHandlerRegistry**: scans an agent for `@DialogueHandler` annotations and dispatches incoming `DialogueMessage` objects to the correct handler by `Performative`.

### Lifecycle

Package `dev.agenor.runtime.lifecycle`:

- **LifecycleManager**: manages agent status transitions with timeout support (`startAgent`, `stopAgent`); notifies registered `LifecycleListener` implementations.
- **LifecycleListener**: functional interface receiving `(agentId, oldStatus, newStatus)`.
- **LoggingLifecycleListener**: built-in listener that logs every status change at INFO level via SLF4J.

## 5. Adapters (agenor-adapters)

The `agenor-adapters` module provides concrete implementations of core interfaces that integrate external services.

### LLM Providers

All three providers implement `LLMProvider` from `agenor-core`:

- **OpenAIProvider**: OpenAI REST API (GPT-4, GPT-3.5, etc.). Supports streaming and function calling.
- **AnthropicProvider**: Anthropic API (Claude 3 Opus, Sonnet, Haiku). Supports streaming.
- **OllamaProvider**: Local Ollama server. Supports any model available on the local instance.

**LLMProviderFactory** is the recommended entry point. It creates the correct provider from a name string and API key, avoiding direct dependency on implementation classes:

```java
LLMProvider openAI    = LLMProviderFactory.create("openai", System.getenv("OPENAI_API_KEY"));
LLMProvider anthropic = LLMProviderFactory.create("anthropic", System.getenv("ANTHROPIC_API_KEY"));
LLMProvider ollama    = LLMProviderFactory.create("ollama", null); // no key needed
```

**ToolConversionUtils**: converts Agenor `FunctionDefinition` objects to the vendor-specific JSON schemas required by each provider.

### A2A Adapter

Implements the [Agent-to-Agent (A2A) protocol](https://google.github.io/A2A):

- **AgenorA2AAdapter**: exposes a Agenor agent as an A2A server, built from `A2AAdapterConfig`.
- **AgenorA2AClient**: sends A2A messages to remote agents.
- **AgenorAgentExecutor**: handles incoming A2A tasks and routes them to a local agent.

For the full A2A guide see [`docs/dialog-protocol.md`](dialog-protocol.md).

### Redis Messaging Adapter (since 0.21.0)

Implements `TopicPublisher`, `TopicSubscriber`, and `MessageTransport` on top of Redis Streams,
providing at-least-once delivery and fan-out pub/sub across JVM nodes. Requires `lettuce-core`
on the classpath per ADR-018 (opt-in). Activated via `agenor.messaging.provider=redis` in Spring Boot,
or directly via `RedisMessagingFactory`.

Key classes in `dev.agenor.adapters.messaging.redis`:

- **RedisMessagingFactory**: builder; manages the shared Lettuce connection and lifecycle.
- **RedisTopicPublisher**: implements `TopicPublisher` + `TopicSubscriber`; fan-out via per-subscription consumer groups.
- **RedisMessageTransport**: implements `MessageTransport`; point-to-point via node-scoped streams.
- **ConsumerLoop**: virtual-thread blocking `XREADGROUP` loop with DLQ after `maxDeliveryAttempts`.

For the full guide see [`docs/adapters/redis.md`](adapters/redis.md).

### JDBC Persistence Adapter (agenor-adapters-persistence)

Implements `dev.agenor.core.directory.AgentDirectory` and the HITL `ApprovalService` on top of a
relational database via plain JDBC, for deployments that need a durable, queryable agent registry
and approval queue (ADR-022).

- **JdbcAgentDirectory**: persistent implementation of `AgentDirectory` (ADR-023).
- **JdbcApprovalGate**: persistent HITL approval queue (ADR-024).

For the full guides see [JDBC Directory Adapter](adapters/jdbc-directory.md) and
[Persistent HITL](hitl-persistence.md).

### Extension Points

All core contracts are interfaces. Custom implementations can be plugged in
without changing agent code:

- `MessageDispatcher` → Redis Streams, Kafka, JMS, or any custom transport
- `dev.agenor.core.directory.AgentDirectory` → JDBC, Consul, etcd, or any registry
- `BehaviorScheduler` → Quartz, cron, or any scheduler
- `MemoryStore` → any SQL or NoSQL backend

Community adapters are welcome. See `CONTRIBUTING.md`.

## 6. Concurrency Model

- Agenor targets Java 21 virtual threads (Project Loom) for lightweight concurrency.
- Behaviors are executed in virtual threads by the scheduler when appropriate.
- Blocking operations in behaviors do not monopolize platform threads, simplifying the programming model.
- Message handlers should remain responsive; long‑running work can be delegated to behaviors or separate virtual threads.

## 7. Messaging Flow

1. An Agent publishes a `Message` via `MessageDispatcher.publish(msg)` (topic broadcast, routing on `msg.topic()`) or `sendTo(msg)` (point-to-point, routing on `msg.receiverId()`).
2. For point-to-point, the dispatcher calls `AgentResolver.resolveEndpoint(agentId)` to obtain the target `AgentEndpoint`.
3. Agents subscribe via `subscribeTopic(topic, handler)` or `subscribeRecipient(agentId, handler)`, both returning a `Subscription`.
4. `@AgenorMessageHandler(topic)` annotations are also supported; the runtime registers the handler automatically.
5. Optional predicate filtering is available via `FilterableSubscriber.subscribeFiltered(filter, handler)` (in-memory only).
6. The in-memory implementation delivers messages on virtual threads within the JVM; custom backends (Redis, Kafka) plug in without changing agent code.

See [Messaging Guide](messaging.md) for the complete API reference.

## 8. Discovery & Lifecycle

- AgentDirectory registers agents at startup and maintains status (STARTING, RUNNING, STOPPED, etc.).
- Agents may query other agents via AgentDirectory using AgentQuery.
- AgenorRuntime orchestrates:
  - scanning configured base packages
  - constructing agents via AgentFactory
  - registering agents in the directory
  - scheduling declared behaviors
  - wiring message handlers to the MessageDispatcher

## 9. Configuration

- Minimal configuration via code (builder) and/or YAML. Example keys:
  - agenor.runtime.name
  - agenor.agents.auto-discovery
  - agenor.agents.base-package
  - agenor.messaging.provider (in-memory)
  - agenor.directory.provider (local)

Implementations are selected by configuration while code depends only on core interfaces.

## 10. Extensibility Points

To integrate enterprise technologies, implement core contracts:
- `MessageDispatcher` (or individual `TopicPublisher` / `DirectMessenger` capabilities): swap transport (Redis Streams, Kafka, JMS)
- `dev.agenor.core.directory.AgentDirectory` (or individual `AgentRegistry` / `AgentDiscovery` / `AgentResolver` / `AgentPresence` capabilities): swap discovery (JDBC, Consul, etcd)
- `BehaviorScheduler`: advanced scheduling (Quartz, cron, priority queues)
- `LLMProvider`: add new model providers (implement the interface, register with factory)

Guidelines:
- Keep adapters dependency‑isolated within agenor-adapters submodules.
- Avoid leaking implementation types into user code; rely on core interfaces.

## 11. Error Handling & Observability

- Exceptions derive from AgenorException hierarchy (AgentException, LLMException).
- Logging via SLF4J with pluggable backend (logback in tests/examples).
- Planned: metrics for behavior execution, message throughput, and directory health.

## 12. Guardrails Pipeline

Added in 0.13.0 (ADR-014). The Guardrails Layer intercepts content at two hook points
inside every `LLMAgent` subclass:

```
User input
  → InputGuardrailChain   applyInputGuardrails(input, ctx)
  → LLMProvider.chat()
  → OutputGuardrailChain  applyOutputGuardrails(output, ctx)
  → Consumer
```

### Core types (`agenor-core` / `dev.agenor.core.guardrail`)

| Type | Role |
|------|------|
| `GuardrailResult` | Sealed interface: `Passed \| Modified(newContent) \| Blocked(reason)` |
| `InputGuardrail` | `@FunctionalInterface` — validates/transforms user input |
| `OutputGuardrail` | `@FunctionalInterface` — validates/transforms LLM output |
| `GuardrailContext` | Immutable record: `agentId`, `topic`, `metadata` |
| `GuardrailViolationException` | Unchecked, extends `AgenorException` |
| `@WithGuardrails` | Annotation for declarative chain wiring |

### Implementations (`agenor-runtime-llm` / `dev.agenor.runtime.guardrail`, split out of `agenor-runtime` per ADR-027)

| Class | Type |
|-------|------|
| `GuardrailChain` | Fluent builder + sequential execution with short-circuit |
| `PiiRedactionGuardrail` | Input + Output |
| `ContentPolicyGuardrail` | Input + Output (YAML blocklist) |
| `MaxTokensInputGuardrail` | Input (3 truncation strategies) |
| `GuardrailAnnotationProcessor` | Reads `@WithGuardrails`, injects chain at registration |

### Wiring

`AgenorRuntime.registerAgent()` calls `GuardrailAnnotationProcessor.process(agent)` for every
`LLMAgent` instance. The processor reads `@WithGuardrails`, instantiates the listed guardrail
classes via their no-arg constructors, and injects the resulting `GuardrailChain`. Programmatic
chains set before registration are merged (annotation chain prepended).

### Design decisions (ADR-014)

- `GuardrailResult` as a **sealed interface** (Java 21) enforces exhaustive `switch` at
  compile time, making silent mishandling of `Blocked` impossible.
- `LLMAgent` exposes `applyInputGuardrails` / `applyOutputGuardrails` as `protected` hooks;
  subclasses call them around their own `llmProvider.chat()` invocation.
- `GuardrailViolationException` is unchecked, consistent with the `AgenorException` hierarchy.

See `docs/guardrails.md` for the full developer guide.

## 13. Human-in-the-Loop Checkpoint

Suspends agent behavior execution pending human approval.

```
Agent → HumanCheckpointBehavior → ApprovalGate (virtual thread parks)
→ ApprovalNotifier (fire-and-forget)
← ApprovalService.submit()  (from external system)
→ resumes with ApprovalDecision (Approved | Rejected | Modified)
```

Core types (agenor-core / dev.agenor.core.hitl):
ApprovalRequest, ApprovalDecision (sealed), ApprovalGate, ApprovalNotifier,
ApprovalTimeoutException, @RequiresApproval

Implementations (agenor-runtime-ext / dev.agenor.runtime.hitl, split out of agenor-runtime per ADR-027):
InMemoryApprovalGate, ApprovalService, HumanCheckpointBehavior, HitlAnnotationProcessor

Access via: runtime.getApprovalService()
See [Human-In-The-Loop guide](behaviors/hitl.md) for the full developer guide.

## 14. Evolution Path

- MVP: in‑memory runtime for simple single‑JVM systems.
- See `CONTRIBUTING.md` for how to build and share custom adapters.

See the ADRs in `docs/adr/` (repository only) for rationale and decisions.

## 15. Example Bootstrapping

```java
public class Main {
    public static void main(String[] args) {
        var runtime = AgenorRuntime.builder()
            .scanPackage("dev.agenor.examples")
            .build();

        runtime.start();
    }
}
```

Agents are discovered, registered, and their behaviors scheduled automatically.

## 16. Glossary

- Agent: Autonomous unit of computation and coordination.
- Behavior: Unit of work owned by an Agent; its type says when the work runs.
- Message: Topic‑addressed payload exchanged between agents.
- Mailbox: The single inbound path an agent's messages arrive on (ADR-032).
- Directory: Registry that enables discovery and status tracking of agents.
- Scheduler: Component responsible for driving behaviors.
- DialogueCapability: Composable component adding structured conversation support to an agent.
