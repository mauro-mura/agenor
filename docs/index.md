# Jentic Documentation

> **Java meets Agentic.** The enterprise-grade agent framework for the JVM.

Jentic lets you build autonomous, message-driven agents with minimal boilerplate — starting simple and evolving incrementally toward production-grade deployments. Built on Java 21+ virtual threads, with native support for MCP, A2A, Guardrails, and Human-in-the-Loop.

New to Jentic? Start with the **[Getting Started Guide](getting-started.md)**.

---

## Core Guides

| Document | Description |
|----------|-------------|
| [Getting Started](getting-started.md) | Build and run your first agent in 5 minutes |
| [Architecture Guide](architecture.md) | Modules, abstractions, and design decisions |
| [Agent Development Guide](agent-development.md) | Lifecycle, annotations, patterns |
| [Configuration Guide](configuration.md) | YAML config, environment variables |
| [LLM Integration Guide](llm-integration.md) | OpenAI, Anthropic, Ollama providers |
| [Guardrails Guide](guardrails.md) | The Guardrails Layer |
| [Memory Guide](memory.md) | `MemoryStore`, `MemoryScope`, `InMemoryStore`, `BaseAgent` memory API |
| [Agent State Persistence Guide](persistence.md) | `Stateful`, `FilePersistenceService`, `@JenticPersistenceConfig` |
| [Messaging Guide](messaging.md) | MessageDispatcher, topics, direct messaging, subscriptions (since 0.20.0) |
| [Agent Directory Guide](directory.md) | AgentRegistry, AgentDiscovery, AgentResolver, AgentPresence (since 0.20.0) |
| [Dialogue Protocol](dialog-protocol.md) | A2A protocol, request/reply, CFP |
| [Message Filtering Guide](message-filtering.md) | Filters, rate limiting |
| [Observability Guide](observability.md) | OpenTelemetry tracing and span taxonomy |

## Behaviors

| Document | Type |
|----------|------|
| [Behaviors Overview](behaviors/README.md) | All behavior types at a glance |
| [CyclicBehavior](behaviors/CyclicBehavior.md) | Repeat at fixed interval |
| [OneShotBehavior](behaviors/OneShotBehavior.md) | Execute once and stop |
| [EventDrivenBehavior](behaviors/EventDrivenBehavior.md) | React to messages |
| [WakerBehavior](behaviors/WakerBehavior.md) | Wake on condition or time |
| [ScheduledBehavior](behaviors/ScheduledBehavior.md) | Cron-based scheduling |
| [SequentialBehavior](behaviors/SequentialBehavior.md) | Step-by-step execution |
| [ParallelBehavior](behaviors/ParallelBehavior.md) | Concurrent child behaviors |
| [FSMBehavior](behaviors/FSMBehavior.md) | Finite State Machine |
| [ConditionalBehavior](behaviors/ConditionalBehavior.md) | Gate on a condition |
| [ThrottledBehavior](behaviors/ThrottledBehavior.md) | Rate-limited execution |
| [RetryBehavior](behaviors/RetryBehavior.md) | Automatic retry with back-off |
| [BatchBehavior](behaviors/BatchBehavior.md) | Bulk item processing |
| [CircuitBreakerBehavior](behaviors/CircuitBreakerBehavior.md) | Fault-tolerance pattern |
| [PipelineBehavior](behaviors/PipelineBehavior.md) | Multi-stage transformation |
| [ReflectionBehavior](behaviors/ReflectionBehavior.md) | LLM output reflection loop |
| [Human-In-The-Loop](behaviors/hitl.md) | Human-in-the-Loop Checkpoint |

## Adapters

| Document | Description                                                  |
|----------|--------------------------------------------------------------|
| [MCP Adapter](adapters/mcp.md) | Integrate Model Context Protocol servers into Jentic agentss |

## Integrations

| Document                                      | Description                                                  |
|-----------------------------------------------|--------------------------------------------------------------|
| [Spring Boot Starter](spring-boot-starter.md) | zero-configuration auto-wiring of `JenticRuntime` into any Spring Boot 4.0.x application |

## Examples

Runnable examples with a structured learning path are in `jentic-examples/README.md`.
