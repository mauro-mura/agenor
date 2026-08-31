# Agenor Documentation

> **Multi-agent coordination for the JVM.** Autonomous agents that negotiate, delegate, and execute — with pluggable LLM reasoning when you need it.

Agenor lets you build autonomous, message-driven agents with minimal boilerplate — starting simple and evolving incrementally toward production-grade deployments. Built on Java 21+ virtual threads, with native support for MCP, A2A, Guardrails, and Human-in-the-Loop.

New to Agenor? Start with the **[Getting Started Guide](getting-started.md)**.

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
| [Agent State Persistence Guide](persistence.md) | `Stateful`, `FilePersistenceService`, `@PersistenceConfig` |
| [Messaging Guide](messaging.md) | MessageDispatcher, topics, direct messaging, subscriptions (since 0.20.0) |
| [Agent Directory Guide](directory.md) | AgentRegistry, AgentDiscovery, AgentResolver, AgentPresence (since 0.20.0) |
| [Dialogue Protocol](dialog-protocol.md) | A2A protocol, request/reply, CFP |
| [Message Filtering Guide](message-filtering.md) | Filters, rate limiting |
| [Observability Guide](observability.md) | OpenTelemetry tracing and span taxonomy |

## Behaviors

| Document | Type |
|----------|------|
| [Behaviors Overview](behaviors/README.md) | When work runs, and what is deliberately not a behavior |
| [OneShotBehavior](behaviors/OneShotBehavior.md) | Execute once, immediately or after a delay |
| [CyclicBehavior](behaviors/CyclicBehavior.md) | Repeat at fixed interval |
| [FSMBehavior](behaviors/FSMBehavior.md) | Finite State Machine |
| [SequentialBehavior](behaviors/SequentialBehavior.md) | Step-by-step execution |
| [Human-In-The-Loop](behaviors/hitl.md) | Human-in-the-Loop Checkpoint |

## Adapters

| Document | Description |
|----------|-------------|
| [MCP Adapter](adapters/mcp.md) | Integrate Model Context Protocol servers into Agenor agents |
| [Redis Messaging Adapter](adapters/redis.md) | At-least-once distributed messaging via Redis Streams (since 0.21.0) |
| [JDBC Directory Adapter](adapters/jdbc-directory.md) | Persistent, JDBC-backed agent directory (ADR-022, ADR-023) |
| [Persistent HITL](hitl-persistence.md) | JDBC-backed Human-in-the-Loop approval queue (ADR-022, ADR-024) |

## Integrations

| Document                                      | Description                                                  |
|-----------------------------------------------|--------------------------------------------------------------|
| [Spring Boot Starter](spring-boot-starter.md) | zero-configuration auto-wiring of `AgenorRuntime` into any Spring Boot 4.0.x application |

## Examples

Runnable examples with a structured learning path are in `agenor-examples/README.md`.
