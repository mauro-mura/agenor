# ADR-019: OpenTelemetry Instrumentation

**Status**: Accepted  
**Date**: 2026-04-23  
**Last Modified**: 2026-05-14  
**Authors**: Jentic Team  
**References**: ADR-018 (Optional Adapter Dependencies Pattern)

---

## Context

Jentic had zero distributed observability: no traces, no spans, no metrics. Enterprise adoption
was blocked by the first question from platform teams — "does it emit traces?" — because without
traces it is impossible to debug multi-agent interactions, measure LLM latency, or audit
Human-in-the-Loop approvals in production.

OpenTelemetry (OTel) is the industry standard for distributed tracing and metrics. However,
pulling the full OTel SDK into `jentic-core` would violate ADR-002 (zero external deps in core)
and ADR-018 (optional adapter deps). The challenge is adding observability with zero overhead for
consumers who don't want it and zero `ClassNotFoundException` risk when OTel is absent.

---

## Decision

Introduce a **two-layer telemetry architecture**:

1. **Core abstraction** (`jentic-core`) — zero-dependency interfaces (`JenticTelemetry`,
   `SpanBuilder`, `Span`, `SpanStatus`) plus a `NoopJenticTelemetry` singleton. Core code
   always codes against the abstraction, never against OTel.

2. **OTel adapter** (`jentic-adapters`) — `OtelJenticTelemetry`, `OtelTelemetryFactory`
   declared with `<optional>true</optional>`. Only materialised when OTel is on the classpath.

Runtime components accept `JenticTelemetry` via constructor injection (default: `noop()`).
The Spring Boot starter auto-configures a `JenticTelemetry` bean when
`io.opentelemetry.api.OpenTelemetry` is detected on the classpath.

---

## Rationale

### Pros

- **Zero overhead by default**: `NoopJenticTelemetry` returns shared singletons; no objects
  allocated on the hot path when telemetry is disabled.
- **Backward-compatible**: all existing constructors gain an overload that defaults to noop.
  No existing code needs to change.
- **Classpath-safe**: OTel classes are only referenced in `jentic-adapters`. A consumer that
  never adds OTel to their POM will never see `ClassNotFoundException`.
- **Automatic in Spring Boot**: `@ConditionalOnClass(name="dev.jentic.adapters.telemetry.OtelTelemetryFactory")`
  ensures the telemetry bean appears only when the adapter is present.
- **Standard signals**: spans follow OTel semantic conventions where applicable, so they work
  out-of-the-box with Jaeger, Tempo, Datadog, Honeycomb, and any OTLP-compatible backend.

### Cons

- Two-layer abstraction adds one dispatch per span operation (interface call → OTel SDK call).
  Acceptable because span creation is not on the request-critical path for most operations.
- Consumers using the `JenticTelemetry` abstraction cannot access OTel-specific APIs (baggage,
  metrics) without casting. Acceptable for the initial scope; metrics can be added later.

### Alternatives Considered

- **Micrometer Observation API**: more established in Spring ecosystem but heavier, adds
  Micrometer as a required dep in `jentic-core`, and is less ubiquitous outside Spring.
- **Direct OTel in core**: simpler but violates ADR-002 and ADR-018; rejected.
- **No telemetry abstraction (OTel directly in runtime)**: ties runtime to OTel, breaks
  classpath isolation; rejected.

---

## Implementation

### Package layout

```
jentic-core
  dev.jentic.core.telemetry
    Span              ← interface
    SpanScope         ← interface (AutoCloseable, returned by Span.makeCurrent())
    SpanBuilder       ← interface
    SpanStatus        ← enum (OK, ERROR, UNSET)
    JenticTelemetry   ← interface + noop() factory method
    NoopJenticTelemetry ← package-private singleton

jentic-adapters
  dev.jentic.adapters.telemetry
    OtelJenticTelemetry   ← wraps OpenTelemetry instance
    OtelTelemetryFactory  ← builder + fromEnvironment()
```

### Span inventory

| Span name              | Emitted by                      | Key attributes |
|------------------------|---------------------------------|----------------|
| `llm.chat`             | `InstrumentedLLMProvider`       | `llm.provider`, `llm.model`, `llm.tokens.input`, `llm.tokens.output`, `llm.latency_ms` |
| `llm.chat.stream`      | `InstrumentedLLMProvider`       | + `llm.stream.chunks` |
| `guardrail.evaluate`   | `GuardrailChain`                | `guardrail.direction` (input/output), `guardrail.decision` (passed/blocked), `guardrail.name` |
| `hitl.approval`        | `HumanCheckpointBehavior`       | `hitl.request_id`, `hitl.action`, `hitl.decision`, `hitl.wait_ms` |
| `behavior.execute`     | `SimpleBehaviorScheduler`       | `behavior.id`, `behavior.type`, `agent.id`, `behavior.duration_ms` |
| `mcp.tool.call`        | `JenticMcpClientAdapter`        | `mcp.tool.name`, `mcp.transport` (sse/stdio) |
| `reflection.iteration` | `ReflectionBehavior`            | `reflection.iteration`, `reflection.score`, `reflection.accepted` |
| `message.send`         | `InMemoryMessageDispatcher`     | `message.topic` or `message.recipient`, `message.id`, `agent.sender` |
| `directory.resolve`    | `InMemoryAgentDirectory`, `JdbcAgentResolver` (**JDBC adapter**, @since 0.22.0) | `agent.id`, `endpoint.type` (`not-found` if missing) |
| `directory.register`   | `JdbcAgentRegistry` (**JDBC adapter**, @since 0.22.0) | `agent.id` |
| `directory.unregister` | `JdbcAgentRegistry` (**JDBC adapter**, @since 0.22.0) | `agent.id` |
| `directory.update_status` | `JdbcAgentRegistry` (**JDBC adapter**, @since 0.22.0) | `agent.id`, `agent.status` |
| `directory.find`       | `JdbcAgentDiscovery` (**JDBC adapter**, @since 0.22.0) | `directory.find.type` (`by_id`\|`by_capability`\|`by_type`\|`query`), `directory.find.result_count` |

### OTel context propagation

Parent-child relationships require two cooperating steps:

1. **`Span.makeCurrent()`** — the parent span must be made current before executing code
   that creates child spans. This writes the span into `io.opentelemetry.context.Context`
   for the duration of the `try-with-resources` block. The returned `SpanScope` is
   `AutoCloseable`; `close()` pops the span from the context without ending it.

2. **`OtelJenticTelemetry.spanBuilder()`** — captures `Context.current()` at call time and
   sets it as the parent. Any span started from that builder is automatically a child of
   whatever is currently in the context.

All non-async instrumentation points (`behavior.execute`, `reflection.iteration`,
`guardrail.evaluate`, `message.receive`) call `makeCurrent()`. Async spans (`llm.chat`,
`llm.chat.stream`) use `CompletableFuture.whenComplete` and cannot call `makeCurrent()`
across thread boundaries; their parent is captured at `spanBuilder()` call time instead.

### Wiring in JenticRuntime

```java
JenticRuntime runtime = JenticRuntime.builder()
    .telemetry(OtelTelemetryFactory.builder()
        .serviceName("my-agent")
        .exporter("otlp-http")
        .endpoint("http://otel-collector:4318")
        .build())
    .build();
```

### Spring Boot auto-configuration

```yaml
jentic:
  telemetry:
    enabled: true
    exporter: otlp-http
    endpoint: http://otel-collector:4318
    service-name: my-agent
```

Add to POM to activate:
```xml
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-sdk</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

---

## Consequences

### Positive

- Distributed traces across multi-agent systems visible in standard APM tools.
- LLM token usage auditable via span attributes.
- HITL approval wait times measurable and alertable.
- Behavior execution failures observable without log-scraping.

### Negative

- Consumers that don't add OTel get no traces (intended; they get noop, not a crash).
- Span parent propagation across `CompletableFuture` boundaries relies on `Context.current()`
  being correct at call time — callers must capture spans before async handoff.

### Neutral

- OTel metrics (counters, histograms) are deferred; the abstraction only covers tracing today.
  `JenticTelemetry` can be extended with a `meter()` accessor in a follow-up.

---

## Compliance

- `jentic-core` must never import `io.opentelemetry.*` — enforced by `maven-dependency-plugin:analyze`.
- Classpath isolation verified by a CI test that boots the runtime without OTel on the classpath.
- Span attribute naming must follow the inventory table above; deviations require an ADR update.
