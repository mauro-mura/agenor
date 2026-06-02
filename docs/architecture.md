# Architecture Guide

> **Java meets Agentic.** The enterprise-grade agent framework for the JVM.

This document describes Jentic's architecture: an interface-first, Java 21+ multi-agent framework with native support for MCP, A2A, Guardrails, and Human-in-the-Loop.

- **Audience**: developers evaluating or building on Jentic
- **Scope**: high-level structure, core abstractions, runtime behavior, and extension points

## 1. Architectural Overview

Jentic embraces an interface‑first, modular architecture. Core contracts live in jentic-core, while minimal, ready‑to‑use implementations live in jentic-runtime. Adapters (LLM providers, A2A) live in jentic-adapters.

| jentic-core (interfaces) | jentic-runtime (in-memory impls) | jentic-adapters (integrations) |
|--------------------------|----------------------------------|--------------------------------|
| Agent                    | BaseAgent                        | OpenAIProvider                 |
| Message                  | LLMAgent                         | AnthropicProvider              |
| MessageDispatcher        | InMemoryMessageDispatcher        | OllamaProvider                 |
| directory.AgentDirectory | InMemoryAgentDirectory           | LLMProviderFactory             |
| Behavior                 | SimpleBehaviorScheduler          | A2A Adapter                    |
| BehaviorScheduler        | Behaviors (Cyclic…)              | JenticA2AClient                |
| LLMProvider              | InMemoryStore                    | JenticAgentExecutor            |
| MemoryStore              | DefaultLLMMemoryManager          |                                |
| Condition                | Filters, RateLimiters            |                                |
|                          | Conditions, Dialogue             |                                |


Design goals:
- Start simple, scale smart (ADR-004)
- Interface‑first contracts (ADR-002)
- Modern Java 21, virtual threads (ADR-001)
- JSON record-based messages (ADR-005)
- Annotation-based configuration (ADR-006)

## 2. Modules

- jentic-core: Pure interfaces, records, annotations, and exceptions. No heavy dependencies.
- jentic-runtime: Minimal, production‑ready in‑memory implementations to get started fast.
- jentic-adapters: LLM providers and A2A adapter.
- jentic-examples: Demonstrates usage patterns and best practices.

## 3. Core Abstractions (jentic-core)

- Agent: Lifecycle contract for autonomous entities; exposes id, status, and context.
- Behavior: Unit of work associated with an Agent. Types include CYCLIC, ONE_SHOT, EVENT_DRIVEN, WAKER.
- Message: Transport‑agnostic payload record (topic, headers, content, metadata).
- **MessageDispatcher** (since 0.20.0): Composite interface for topic publish/subscribe and direct agent-to-agent messaging. Composed of `TopicPublisher`, `TopicSubscriber`, `DirectMessenger`, `DirectReceiver`. `FilterableSubscriber` is a separate capability for predicate-based subscriptions.
- **directory.AgentDirectory** (since 0.20.0): Composite directory interface. Composed of `AgentRegistry`, `AgentResolver`, `AgentDiscovery`, `AgentPresence`. Designed for distributed backends.
- `AgentEndpoint` / `Page<T>` / `PageRequest` (since 0.20.0): Records supporting transport routing and paginated discovery.
- BehaviorScheduler: Schedules and executes behaviors per their semantics and policy.
- LLMProvider: Provider-agnostic contract for LLM interaction (`chat`, `chatStream`, `getAvailableModels`).
- MemoryStore: Interface for agent memory (short-term and long-term entries).
- Condition: `Predicate<Agent>`-like interface used to gate behavior execution.
- Annotations: `@JenticAgent`, `@JenticBehavior`, `@JenticMessageHandler`, `@JenticPersist`, `@JenticPersistenceConfig`, `@DialogueHandler`.

These are deliberately small to keep adapters swappable without breaking user code.

## 4. Runtime Implementations (jentic-runtime)

### Agent Base Classes

- **BaseAgent**: Convenience base class wiring message handling, behavior registration, services injection, and lifecycle hooks (`onStart()` / `onStop()`).
- **LLMAgent**: Extends `BaseAgent` with conversation history management, context window budgeting, and long-term fact storage. Requires a `LLMMemoryManager` to be injected before start.

### Behaviors

- CyclicBehavior: executes at fixed intervals
- OneShotBehavior: runs once and completes
- EventDrivenBehavior: reacts to incoming messages/events
- WakerBehavior: runs after a delay or when a Condition becomes true
- Sequential, Parallel, FSMBehavior: composite execution patterns
- ConditionalBehavior: gates execution on a `Condition`
- ThrottledBehavior: wraps any behavior with a `RateLimiter`
- BatchBehavior, RetryBehavior, CircuitBreakerBehavior, PipelineBehavior, ScheduledBehavior

### Messaging

- **InMemoryMessageDispatcher** (since 0.20.0): Production implementation of `MessageDispatcher` and `FilterableSubscriber`. Delivers messages using virtual threads. Routes `sendTo` calls via `AgentResolver`; throws `AgentNotFoundException` for unknown agents. Emits `message.send` OTel spans. See [Messaging](messaging.md).
- **InMemoryMessageService** (removed at 0.22.0): Use `InMemoryMessageDispatcher`.

### Agent Directory and Scheduler

- **InMemoryAgentDirectory** (since 0.20.0): Implements `dev.agenor.core.directory.AgentDirectory` (all four capability interfaces). Assigns `AgentEndpoint.local(nodeId)` to newly registered agents automatically. Emits `directory.resolve` OTel spans. See [Agent Directory](directory.md).
- **SimpleBehaviorScheduler**: Virtual‑thread friendly scheduler.
- **AgentScanner + AnnotationProcessor**: Scans packages for annotated agents/handlers and wires runtime.
- **JenticRuntime**: Entry point to bootstrap, start, and stop the agent system.

### Memory

- **InMemoryStore**: Thread-safe `MemoryStore` implementation backed by `ConcurrentHashMap`. Stores `MemoryEntry` objects with topic, scope (`SHORT_TERM` / `LONG_TERM`), content, and optional TTL. Does not persist to disk.
- **DefaultLLMMemoryManager**: Bridges `InMemoryStore` and the LLM conversation history. Supports three context window strategies:
  - `FixedWindow` — keeps the N most recent messages
  - `SlidingWindow` — keeps messages within a rolling token budget (default)
  - `Summarization` — auto-summarizes old messages using an LLM call
- **TokenBudgetManager** and **ModelTokenLimits**: Helpers for token estimation and model-specific limits.

### Message Filters

All filters implement `MessageFilter` (package `dev.agenor.runtime.filter`). Used with `FilterableSubscriber.subscribeFiltered`:

- **TopicFilter**: `exact`, `startsWith`, `endsWith`, `wildcard`, `regex`
- **HeaderFilter**: `exists`, `equals`, `matches`, `in`, `startsWith`
- **ContentFilter**: `ofType`, `notNull`, `matching`
- **PredicateFilter**: arbitrary `Predicate<Message>` with optional description
- **CompositeFilter**: `and`, `or`, `not` combinators

### Rate Limiters

Package `dev.agenor.runtime.ratelimit`, both implement `RateLimiter`:

- **SlidingWindowRateLimiter**: tracks request timestamps in a rolling time window
- **TokenBucketRateLimiter**: classic token-bucket with configurable refill rate

### Conditions

Package `dev.agenor.runtime.condition`, all produce `Condition` instances:

- **AgentCondition**: `isRunning`, `hasStatus`, `idMatches`, `nameContains`
- **SystemCondition**: `cpuBelow/Above`, `memoryBelow/Above`, `availableMemoryAbove`, `threadsBelow`, `systemHealthy`, `systemUnderLoad` — reads `SystemMetrics.current()`
- **TimeCondition**: `businessHours`, `weekday`, `weekend`, `afterHour`, `beforeHour`
- **ConditionEvaluator**: evaluates a `Condition` against an `Agent` with error containment

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

## 5. Adapters (jentic-adapters)

The `jentic-adapters` module provides concrete implementations of core interfaces that integrate external services.

### LLM Providers

All three providers implement `LLMProvider` from `jentic-core`:

- **OpenAIProvider**: OpenAI REST API (GPT-4, GPT-3.5, etc.). Supports streaming and function calling.
- **AnthropicProvider**: Anthropic API (Claude 3 Opus, Sonnet, Haiku). Supports streaming.
- **OllamaProvider**: Local Ollama server. Supports any model available on the local instance.

**LLMProviderFactory** is the recommended entry point. It creates the correct provider from a name string and API key, avoiding direct dependency on implementation classes:

```java
LLMProvider openAI    = LLMProviderFactory.create("openai", System.getenv("OPENAI_API_KEY"));
LLMProvider anthropic = LLMProviderFactory.create("anthropic", System.getenv("ANTHROPIC_API_KEY"));
LLMProvider ollama    = LLMProviderFactory.create("ollama", null); // no key needed
```

**ToolConversionUtils**: converts Jentic `FunctionDefinition` objects to the vendor-specific JSON schemas required by each provider.

### A2A Adapter

Implements the [Agent-to-Agent (A2A) protocol](https://google.github.io/A2A):

- **JenticA2AAdapter**: exposes a Jentic agent as an A2A server, built from `A2AAdapterConfig`.
- **JenticA2AClient**: sends A2A messages to remote agents.
- **JenticAgentExecutor**: handles incoming A2A tasks and routes them to a local agent.

For the full A2A guide see [`docs/dialog-protocol.md`](dialog-protocol.md).

### Redis Messaging Adapter (since 0.21.0)

Implements `TopicPublisher`, `TopicSubscriber`, and `MessageTransport` on top of Redis Streams,
providing at-least-once delivery and fan-out pub/sub across JVM nodes. Requires `lettuce-core`
on the classpath per ADR-018 (opt-in). Activated via `jentic.messaging.provider=redis` in Spring Boot,
or directly via `RedisMessagingFactory`.

Key classes in `dev.agenor.adapters.messaging.redis`:

- **RedisMessagingFactory**: builder; manages the shared Lettuce connection and lifecycle.
- **RedisTopicPublisher**: implements `TopicPublisher` + `TopicSubscriber`; fan-out via per-subscription consumer groups.
- **RedisMessageTransport**: implements `MessageTransport`; point-to-point via node-scoped streams.
- **ConsumerLoop**: virtual-thread blocking `XREADGROUP` loop with DLQ after `maxDeliveryAttempts`.

For the full guide see [`docs/adapters/redis.md`](adapters/redis.md).

### Extension Points

All core contracts are interfaces. Custom implementations can be plugged in
without changing agent code:

- `MessageDispatcher` → Redis Streams, Kafka, JMS, or any custom transport
- `dev.agenor.core.directory.AgentDirectory` → JDBC, Consul, etcd, or any registry
- `BehaviorScheduler` → Quartz, cron, or any scheduler
- `MemoryStore` → any SQL or NoSQL backend

Community adapters are welcome. See `CONTRIBUTING.md`.

## 6. Concurrency Model

- Jentic targets Java 21 virtual threads (Project Loom) for lightweight concurrency.
- Behaviors are executed in virtual threads by the scheduler when appropriate.
- Blocking operations in behaviors do not monopolize platform threads, simplifying the programming model.
- Message handlers should remain responsive; long‑running work can be delegated to behaviors or separate virtual threads.

## 7. Messaging Flow

1. An Agent publishes a `Message` via `MessageDispatcher.publish(msg)` (topic broadcast, routing on `msg.topic()`) or `sendTo(msg)` (point-to-point, routing on `msg.receiverId()`).
2. For point-to-point, the dispatcher calls `AgentResolver.resolveEndpoint(agentId)` to obtain the target `AgentEndpoint`.
3. Agents subscribe via `subscribeTopic(topic, handler)` or `subscribeRecipient(agentId, handler)`, both returning a `Subscription`.
4. `@JenticMessageHandler(topic)` annotations are also supported; the runtime registers the handler automatically.
5. Optional predicate filtering is available via `FilterableSubscriber.subscribeFiltered(filter, handler)` (in-memory only).
6. The in-memory implementation delivers messages on virtual threads within the JVM; custom backends (Redis, Kafka) plug in without changing agent code.

See [Messaging Guide](messaging.md) for the complete API reference.

## 8. Discovery & Lifecycle

- AgentDirectory registers agents at startup and maintains status (STARTING, RUNNING, STOPPED, etc.).
- Agents may query other agents via AgentDirectory using AgentQuery.
- JenticRuntime orchestrates:
  - scanning configured base packages
  - constructing agents via AgentFactory
  - registering agents in the directory
  - scheduling declared behaviors
  - wiring message handlers to the MessageDispatcher

## 9. Configuration

- Minimal configuration via code (builder) and/or YAML. Example keys:
  - jentic.runtime.name
  - jentic.agents.auto-discovery
  - jentic.agents.base-package
  - jentic.messaging.provider (in-memory)
  - jentic.directory.provider (local)

Implementations are selected by configuration while code depends only on core interfaces.

## 10. Extensibility Points

To integrate enterprise technologies, implement core contracts:
- `MessageDispatcher` (or individual `TopicPublisher` / `DirectMessenger` capabilities): swap transport (Redis Streams, Kafka, JMS)
- `dev.agenor.core.directory.AgentDirectory` (or individual `AgentRegistry` / `AgentDiscovery` / `AgentResolver` / `AgentPresence` capabilities): swap discovery (JDBC, Consul, etcd)
- `BehaviorScheduler`: advanced scheduling (Quartz, cron, priority queues)
- `LLMProvider`: add new model providers (implement the interface, register with factory)

Guidelines:
- Keep adapters dependency‑isolated within jentic-adapters submodules.
- Avoid leaking implementation types into user code; rely on core interfaces.

## 11. Error Handling & Observability

- Exceptions derive from JenticException hierarchy (AgentException, MessageException, LLMException).
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

### Core types (`jentic-core` / `dev.agenor.core.guardrail`)

| Type | Role |
|------|------|
| `GuardrailResult` | Sealed interface: `Passed \| Modified(newContent) \| Blocked(reason)` |
| `InputGuardrail` | `@FunctionalInterface` — validates/transforms user input |
| `OutputGuardrail` | `@FunctionalInterface` — validates/transforms LLM output |
| `GuardrailContext` | Immutable record: `agentId`, `topic`, `metadata` |
| `GuardrailViolationException` | Unchecked, extends `JenticException` |
| `@WithGuardrails` | Annotation for declarative chain wiring |

### Implementations (`jentic-runtime` / `dev.agenor.runtime.guardrail`)

| Class | Type |
|-------|------|
| `GuardrailChain` | Fluent builder + sequential execution with short-circuit |
| `PiiRedactionGuardrail` | Input + Output |
| `ContentPolicyGuardrail` | Input + Output (YAML blocklist) |
| `JsonSchemaOutputGuardrail` | Output (Jackson, re-prompt support) |
| `MaxTokensInputGuardrail` | Input (3 truncation strategies) |
| `GuardrailAnnotationProcessor` | Reads `@WithGuardrails`, injects chain at registration |

### Wiring

`JenticRuntime.registerAgent()` calls `GuardrailAnnotationProcessor.process(agent)` for every
`LLMAgent` instance. The processor reads `@WithGuardrails`, instantiates the listed guardrail
classes via their no-arg constructors, and injects the resulting `GuardrailChain`. Programmatic
chains set before registration are merged (annotation chain prepended).

### Design decisions (ADR-014)

- `GuardrailResult` as a **sealed interface** (Java 21) enforces exhaustive `switch` at
  compile time, making silent mishandling of `Blocked` impossible.
- `LLMAgent` exposes `applyInputGuardrails` / `applyOutputGuardrails` as `protected` hooks;
  subclasses call them around their own `llmProvider.chat()` invocation.
- `GuardrailViolationException` is unchecked, consistent with the `JenticException` hierarchy.

See `docs/guardrails.md` for the full developer guide.

## 13. Human-in-the-Loop Checkpoint

Suspends agent behavior execution pending human approval.

```
Agent → HumanCheckpointBehavior → ApprovalGate (virtual thread parks)
→ ApprovalNotifier (fire-and-forget)
← ApprovalService.submit()  (from external system)
→ resumes with ApprovalDecision (Approved | Rejected | Modified)
```

Core types (jentic-core / dev.agenor.core.hitl):
ApprovalRequest, ApprovalDecision (sealed), ApprovalGate, ApprovalNotifier,
ApprovalTimeoutException, @RequiresApproval

Implementations (jentic-runtime / dev.agenor.runtime.hitl):
InMemoryApprovalGate, ApprovalService, HumanCheckpointBehavior,
LoggingApprovalNotifier, WebhookApprovalNotifier, HitlAnnotationProcessor

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
        var runtime = JenticRuntime.builder()
            .scanPackage("dev.agenor.examples")
            .build();

        runtime.start();
    }
}
```

Agents are discovered, registered, and their behaviors scheduled automatically.

## 16. Glossary

- Agent: Autonomous unit of computation and coordination.
- Behavior: Scheduled unit of work owned by an Agent.
- Message: Topic‑addressed payload exchanged between agents.
- Directory: Registry that enables discovery and status tracking of agents.
- Scheduler: Component responsible for behavior execution policy.
- Condition: Predicate evaluated at runtime to gate behavior execution.
- DialogueCapability: Composable component adding structured conversation support to an agent.
- LLMMemoryManager: Component managing conversation history and context window budgeting for LLM agents.
