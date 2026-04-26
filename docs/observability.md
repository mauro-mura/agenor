# Observability

Jentic ships with a thin, dependency-free telemetry abstraction (`JenticTelemetry`) in
`jentic-core`. By default, every instrumented component uses the built-in **no-op
implementation**, which has zero overhead and introduces no external dependencies. The
real OpenTelemetry SDK integration lives in `jentic-adapters` and is entirely **opt-in**.

## Opting in to OpenTelemetry

### 1 — Add the OTel dependency

`jentic-adapters` declares `opentelemetry-sdk` as an **optional** dependency (per
`ADR-018`). You must
explicitly pull it in your own `pom.xml`:

```xml
<!-- Your application POM -->
<dependencies>
    <dependency>
        <groupId>dev.jentic</groupId>
        <artifactId>jentic-adapters</artifactId>
    </dependency>

    <!-- Opt-in: OTel SDK + OTLP exporter -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-sdk</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
</dependencies>
```

Consumers who do **not** add these dependencies compile and run cleanly — no
`ClassNotFoundException` at runtime.

### 2a — Spring Boot applications (auto-configuration)

When `io.opentelemetry.api.OpenTelemetry` is on the classpath, the Spring Boot starter
auto-configures `OtelJenticTelemetry` via `@ConditionalOnClass`. Add the following to
`application.yml`:

```yaml
jentic:
  telemetry:
    enabled: true
    service-name: my-agent-service
    exporter: otlp-http           # otlp-http | otlp-grpc | none
    endpoint: http://localhost:4318
```

### 2b — Manual wiring (no Spring)

```java
import dev.jentic.adapters.telemetry.OtelTelemetryFactory;
import dev.jentic.core.telemetry.JenticTelemetry;

JenticTelemetry telemetry = OtelTelemetryFactory.builder()
        .serviceName("my-agent-service")
        .otlpHttpExporter("http://localhost:4318")
        .build();

JenticRuntime runtime = JenticRuntime.builder()
        .telemetry(telemetry)
        .build();
```

---

## Span taxonomy

The table below lists every span emitted by Jentic components in `0.19.0`. Spans
deferred to later releases (messaging, directory — see ADR-019) are annotated with
the target release.

| Span name | Component | Key attributes |
|-----------|-----------|----------------|
| `llm.chat` | `InstrumentedLLMProvider` | `llm.provider`, `llm.model`, `llm.tokens.input`, `llm.tokens.output`, `llm.latency_ms` |
| `llm.chat.stream` | `InstrumentedLLMProvider` | same as `llm.chat` + `llm.stream.chunks` |
| `guardrail.evaluate` | `GuardrailChain` | `guardrail.name`, `guardrail.direction` (`input`\|`output`), `guardrail.decision` (`passed`\|`blocked`) |
| `hitl.approval` | `HumanCheckpointBehavior` | `hitl.request_id`, `hitl.action`, `hitl.decision`, `hitl.wait_ms` |
| `behavior.execute` | `SimpleBehaviorScheduler` | `behavior.id`, `behavior.type`, `agent.id`, `behavior.duration_ms` |
| `mcp.tool.call` | `JenticMcpClientAdapter` | `mcp.tool.name`, `mcp.transport` (`sse`\|`stdio`) |
| `reflection.iteration` | `ReflectionBehavior` | `reflection.iteration`, `reflection.score`, `reflection.accepted` |
| `message.send` _(0.20.0)_ | `InMemoryMessageDispatcher` | `message.topic` or `message.recipient`, `message.id`, `agent.sender` |
| `directory.resolve` _(0.20.0)_ | `AgentResolver` | `agent.id`, `endpoint.type` |

All spans use **`SpanStatus.OK`** on success and **`SpanStatus.ERROR`** on exception.
Exceptions are recorded via `Span.recordException(Throwable)`.

---

## Metrics reference

Metrics are emitted via the OTel Meter API when OTel is active. All metric names are
prefixed with `jentic.`.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `jentic.llm.tokens` | Counter | `provider`, `model`, `direction` (`input`\|`output`) | Total tokens consumed |
| `jentic.llm.requests` | Counter | `provider`, `model`, `outcome` (`success`\|`error`) | LLM call count |
| `jentic.llm.latency` | Histogram | `provider`, `model` | End-to-end LLM call duration (ms) |
| `jentic.guardrail.violations` | Counter | `guardrail_name` | Blocked guardrail evaluations |
| `jentic.hitl.pending` | UpDownCounter | — | Inflight human approval requests |
| `jentic.behavior.executions` | Counter | `behavior_type`, `outcome` (`success`\|`error`) | Behavior execution count |
| `jentic.directory.resolve.latency` _(0.20.0)_ | Histogram | — | Endpoint resolution time (ms) |

---

## Collector setup

### Local development (Jaeger all-in-one via Docker Compose)

The `jentic-examples` module ships a ready-made
`jentic-examples/src/main/resources/observability/docker-compose.yml`:

```bash
cd jentic-examples/src/main/resources/observability
docker compose up -d
```

This starts:
- **Jaeger** — traces UI at `http://localhost:16686`
- **Prometheus** — metrics at `http://localhost:9090`
- **OpenTelemetry Collector** — OTLP/HTTP receiver on port `4318`, OTLP/gRPC on `4317`

Point your application at `http://localhost:4318` (OTLP/HTTP) or `http://localhost:4317`
(OTLP/gRPC).

### Running the observability example

```bash
# Start the collector stack first
cd jentic-examples/src/main/resources/observability && docker compose up -d

# Run the example
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.observability.ObservabilityExample"
```

Open `http://localhost:16686` and search for service `jentic-observability-example` to
see the complete trace.

---

## Context propagation

OTel context propagation uses the standard `io.opentelemetry.context.Context`. Spans
created inside `CompletableFuture` chains on virtual threads inherit the parent context
correctly because `OtelJenticTelemetry` calls `Context.current()` at span-start time,
not at `spanBuilder()` time.

---

## Zero-cost no-op (default)

When OTel is absent (or `jentic.telemetry.enabled: false`), all instrumented components
use `NoopJenticTelemetry`:

- `spanBuilder(name)` returns the same singleton builder (no allocation).
- `startSpan()` returns the same singleton noop span (no allocation).
- All methods are no-ops that return `this` immediately.

This is verified by the `TelemetryClasspathIsolationTest` in `jentic-core` (which asserts
that `io.opentelemetry.api.OpenTelemetry` is **not** on the core classpath) and by the JMH
microbenchmark in `jentic-adapters/src/test/jmh/NopTelemetryBenchmark.java`.

---

## Verifying no OTel leakage in jentic-core

```bash
mvn dependency:analyze -pl jentic-core
```

The output must not list any `io.opentelemetry` artifact — confirmed by the CI quality
gate.
