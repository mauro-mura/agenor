# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.23.0] - 2026-05-22

### Added

- **`JdbcApprovalGate` — persistent HITL approval queue (ADR-024)**: new class in `dev.jentic.adapters.persistence.hitl` that persists approval requests in the `jentic_hitl_requests` table. Approval requests survive JVM restarts; pending requests are visible from any node via `getPendingRequests()`.

  New classes and migration:
  - `JdbcApprovalGate` — implements `ApprovalGate` and `AutoCloseable`. Maintains an in-memory `CompletableFuture` map per JVM (local-future constraint — see ADR-024). Timeout scheduler uses virtual threads.
  - `HitlSchemaManager` — Flyway wrapper for `classpath:db/migration/jentic-hitl`. Idempotent.
  - `PostgresNotificationListener` — optional cross-node decision propagation via Postgres `LISTEN/NOTIFY` on channel `jentic_hitl`. Activated automatically when the JDBC URL contains `postgresql`.
  - `V1__create_hitl_queue.sql` — Flyway migration creating `jentic_hitl_requests` table with status, decision, and audit columns.

  Startup recovery: call `gate.recoverExpired()` once after construction to mark rows with `expires_at <= NOW()` as `EXPIRED`.

  ```java
  new HitlSchemaManager(dataSource, "classpath:db/migration/jentic-hitl").migrate();
  var gate = new JdbcApprovalGate(dataSource, jdbcUrl);
  gate.recoverExpired();
  var runtime = JenticRuntime.builder().withDefaultConfig().approvalGate(gate).build();
  ```

- **`JenticRuntime.Builder.approvalGate(ApprovalGate)`** (since 0.23.0): injects a custom `ApprovalGate` into the runtime. Defaults to `InMemoryApprovalGate` when not set — no behavioral change for existing users.

- **Spring Boot auto-configuration — `jentic.hitl.provider=jdbc`**: new `JdbcHitlConfiguration` inner class in `JenticAutoConfiguration`. Activates when `dev.jentic.adapters.persistence.hitl.JdbcApprovalGate` is on the classpath and `jentic.hitl.provider=jdbc` is set. Exposes a `JdbcApprovalGate` bean and wires it into `JenticRuntime`. New `JenticProperties.Hitl` record with `provider` and `jdbc` sub-record.

  ```yaml
  jentic:
    hitl:
      provider: jdbc
      jdbc:
        url: jdbc:postgresql://localhost:5432/mydb  # fallback: directory.jdbc.url
        username: jentic
        password: ${DB_PASSWORD}
  ```

### Changed

- **Spring Boot starter — typed provider sub-sections replace generic `properties` map (breaking)**

  `jentic.messaging` and `jentic.directory` now use named, type-safe sub-sections instead of a flat `Map<String,String> properties`. This enables IDE auto-complete, native types (int, long), and Bean Validation.

  **Migrate `jentic.messaging.provider=redis`:**
  ```yaml
  # Before (0.22)
  jentic:
    messaging:
      provider: redis
      properties:
        uri: redis://localhost:6379
        read-block-timeout-ms: "2000"

  # After (0.23+)
  jentic:
    messaging:
      provider: redis
      redis:
        uri: redis://localhost:6379
        read-block-timeout-ms: 2000   # native long, no quotes
  ```

  **Migrate `jentic.directory.provider=jdbc`:**
  ```yaml
  # Before (0.22)
  jentic:
    directory:
      provider: jdbc
      properties:
        url: jdbc:postgresql://localhost:5432/jentic
        pool-size: "10"

  # After (0.23+)
  jentic:
    directory:
      provider: jdbc
      jdbc:
        url: jdbc:postgresql://localhost:5432/jentic
        pool-size: 10   # native int, no quotes
  ```

## [0.22.0] - 2026-05-19

### Added

- **`jentic-adapters-persistence` — JDBC agent directory (ADR-022, ADR-023)**: new dedicated Maven module providing durable, relational-database-backed implementations of `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` via plain JDBC (PostgreSQL, MySQL, H2).

  New classes in `dev.jentic.adapters.persistence.directory`:
  - `JdbcAgentDirectory` — factory and lifecycle holder. Owns the HikariCP connection pool, runs Flyway schema migrations on startup, and exposes the three JDBC capability implementations via `registry()`, `discovery()`, and `resolver()`. Implements `Closeable`.
  - `JdbcAgentRegistry` — implements `AgentRegistry`; upsert semantics on register (idempotent re-registration updates the endpoint and metadata).
  - `JdbcAgentDiscovery` — implements `AgentDiscovery`; supports `findById`, `findByCapability`, `findByType`, and paginated `findAgents(AgentQuery, PageRequest)`.
  - `JdbcAgentResolver` — implements `AgentResolver`; resolves `AgentEndpoint` by agent ID.
  - `JdbcDirectoryConfig` — immutable configuration record (`jdbcUrl`, `username`, `password`, `maximumPoolSize`, `migrationLocation`).
  - `DirectorySchemaManager` — thin Flyway wrapper; runs migrations from `classpath:db/migration/jentic-directory`.
  - `JdbcHelper` — centralised JDBC utility (connection acquisition, parameter binding, result-set mapping).

  Flyway migration `V1__create_agent_directory.sql` creates two tables:
  - `jentic_agents` — agent registration, status, endpoint, and metadata.
  - `jentic_agent_capabilities` — normalised capability set; FK cascade-deletes on agent removal.

  `AgentPresence` is intentionally **not** implemented by JDBC — heartbeat/liveness writes belong to purpose-built backends (Redis TTL keys, Consul session leases). The in-memory `AgentPresence` from `InMemoryAgentDirectory` is used as the default fourth capability when combining a JDBC directory with `JenticRuntime.Builder`.

  HikariCP and Flyway are declared as regular (`compile`) dependencies inside `jentic-adapters-persistence`. The persistence stack does not reach the default classpath of applications that only declare `jentic-runtime` — see ADR-022.

  ```java
  try (var dir = JdbcAgentDirectory.create(
          JdbcDirectoryConfig.of("jdbc:postgresql://localhost:5432/jentic", user, pass))) {
      var runtime = JenticRuntime.builder()
              .agentRegistry(dir.registry())
              .agentDiscovery(dir.discovery())
              .agentResolver(dir.resolver())
              .build();
      runtime.start().join();
  }
  ```

- **Spring Boot starter — JDBC directory auto-configuration**: `JenticAutoConfiguration` gains a new `JdbcDirectoryConfiguration` inner class, activated when `JdbcAgentDirectory` is on the classpath and `jentic.directory.provider=jdbc`.
  - `JdbcAgentDirectory` bean is lifecycle-managed (`destroyMethod="close"`).
  - `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` beans backed by the JDBC implementations.
  - `JenticRuntime` bean built with the three JDBC capabilities plus the in-memory presence fallback.
  - `jentic-adapters-persistence` declared as `optional=true` in the starter POM — no impact on applications that use `provider=local` or `provider=inmemory`.
  - Provider-specific properties go in the `jentic.directory.properties` map, consistent with the messaging provider pattern:
    ```yaml
    jentic:
      directory:
        provider: jdbc
        properties:
          url: jdbc:postgresql://localhost:5432/jentic
          username: jentic
          password: ${DB_PASSWORD}
          pool-size: "10"
    ```

- **ADR-022 — `jentic-adapters-persistence` module split**: documents the decision to use a dedicated Maven sub-module rather than `optional=true` inside `jentic-adapters` for the persistence stack (HikariCP, Flyway, JDBC drivers).

- **ADR-023 — Persistent agent directory with JDBC**: documents the schema design, upsert semantics, capability-split rationale, and the deliberate exclusion of `AgentPresence` from the JDBC backend.

- **`jentic-examples` — `JdbcDirectoryExample`**: runnable demo using H2 in-process (no external infrastructure). Demonstrates `JdbcAgentDirectory.create()`, `JenticRuntime.Builder` per-capability wiring, and capability-based discovery across two agents (`OrchestratorAgent`, `DataWorkerAgent`).

- **Docs — `docs/adapters/jdbc-directory.md`**: full adapter guide covering prerequisites, Maven dependency, schema, programmatic quick start, Spring Boot auto-configuration, configuration reference, mixed JDBC+in-memory backend design, multi-node scenario, and integration test commands.

- **`jentic-adapters-persistence` — OTel instrumentation for JDBC directory adapters**: all three JDBC capability implementations now emit `JenticTelemetry` spans, consistent with the rest of the Jentic adapter layer. Spans are emitted only when a non-noop `JenticTelemetry` is wired; the default remains zero-overhead noop.

  New spans and emitting classes:

  | Span name | Emitted by | Key attributes |
  |-----------|------------|----------------|
  | `directory.resolve` | `JdbcAgentResolver` | `agent.id`, `endpoint.type` (`not-found` if absent) |
  | `directory.register` | `JdbcAgentRegistry` | `agent.id` |
  | `directory.unregister` | `JdbcAgentRegistry` | `agent.id` |
  | `directory.update_status` | `JdbcAgentRegistry` | `agent.id`, `agent.status` |
  | `directory.find` | `JdbcAgentDiscovery` | `directory.find.type` (`by_id`\|`by_capability`\|`by_type`\|`query`), `directory.find.result_count` |

  All spans follow the async pattern (`spanBuilder()` before the `JdbcHelper` virtual-thread call, `whenComplete()` to set attributes and end the span) so the OTel parent context is captured on the calling thread before the JDBC I/O boundary.

  **`JdbcAgentDirectory.create(JdbcDirectoryConfig, JenticTelemetry)`** — new overload that forwards the telemetry instance to all three adapter constructors. The existing single-argument `create(JdbcDirectoryConfig)` is retained and delegates to the new overload with `JenticTelemetry.noop()`.

  **Spring Boot starter** — `JdbcDirectoryConfiguration.jdbcAgentDirectory()` now injects `ObjectProvider<JenticTelemetry>` and passes `telemetry.getIfAvailable(JenticTelemetry::noop)` to the factory, consistent with the `RedisMessagingConfiguration` wiring pattern.

  **ADR-019** and **`docs/observability.md`** span inventory tables extended with the five new JDBC spans (`@since 0.22.0`).

- **`jentic-adapters-persistence` — span emission tests**: `JdbcAgentDirectoryTest` gains a `RecordingTelemetry` inline test helper (zero new test dependencies) and 9 new test methods verifying span name, attributes, and `SpanStatus` for every instrumented operation (`directory.resolve`, `directory.register`, `directory.unregister`, `directory.update_status`, `directory.find` for all four find types).

### Changed

- **Spring Boot starter — Redis messaging YAML format** (**breaking** for 0.21.x users): the `jentic.messaging.redis.*` sub-section is replaced by the flat `jentic.messaging.properties.*` map, matching the pattern used by the new JDBC directory provider. The `JenticProperties.Messaging.Redis` sub-record is removed.

  ```yaml
  # Before (0.21.x)
  jentic:
    messaging:
      provider: redis
      redis:
        uri: redis://localhost:6379
        consumer-group-prefix: my-app

  # After
  jentic:
    messaging:
      provider: redis
      properties:
        uri: redis://localhost:6379
        consumer-group-prefix: my-app
  ```

  All other `redis.*` keys (`read-block-timeout-ms`, `max-stream-length`, `pending-entries-timeout-ms`, `max-delivery-attempts`) move to the same `properties` map as string values. Default values are unchanged.

### Removed

> These APIs were deprecated in **0.20.0** and are now removed.

- **`dev.jentic.core.MessageService`** — use `dev.jentic.core.messaging.MessageDispatcher`.
- **`dev.jentic.runtime.messaging.InMemoryMessageService`** — use `dev.jentic.runtime.messaging.InMemoryMessageDispatcher`.
- **`dev.jentic.runtime.directory.LocalAgentDirectory`** — use `dev.jentic.runtime.directory.InMemoryAgentDirectory`.
- **`JenticRuntime.getMessageService()`** — use `JenticRuntime.getMessageDispatcher()`.
- **`JenticRuntime.Builder.messageService()`** — use `Builder.messageDispatcher()`.
- **`JenticRuntime.Builder.agentDirectory()`** — use `Builder.agentRegistry()` / `agentResolver()` / `agentDiscovery()` / `agentPresence()`.
- **`AgentDescriptor(String, String, String, AgentStatus, Set, Map, Instant, Instant)` constructor** — use `AgentDescriptor.builder(agentId)` and call `.endpoint(AgentEndpoint.local(nodeId))`. The 8-argument constructor silently set `endpoint = null`, breaking `AgentResolver` logic in distributed deployments.
- **`AgentQuery.customFilter(Predicate<AgentDescriptor>)`** — remote backends cannot evaluate Java predicates server-side. Use structured criteria: `.agentType()`, `.status()`, `.requiredCapabilities()`.

### Deprecated

- **`dev.jentic.core.AgentDirectory`** — this pre-0.20.0 composite interface will be removed at 0.24.0 (Agenor rebranding). Use `dev.jentic.core.directory.AgentDirectory` instead. The runtime, starter, and all built-in implementations already implement the new interface; only the package path changes.

### Tests

- **`JdbcAgentDirectoryTest`** (unit): full `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` coverage against H2 in-process — runs as part of `mvn test` with no flags.
- **`JdbcAgentDirectoryIT`** (integration): Testcontainers PostgreSQL 16; gated by `-Dintegration.tests.enabled=true` to avoid Docker dependency in CI by default.

### Fixed

- **`Span.makeCurrent()` — OTel context propagation for non-async spans**: without this
  method, every Jentic span was silently emitted as a root span. `Context.current()` at
  `spanBuilder()` time never carried a parent because no span was ever made "current",
  so `behavior.execute`, `reflection.iteration`, `guardrail.evaluate`, and
  `message.receive` spans had no children even when they triggered LLM, MCP, or guardrail
  calls during execution.

  New public type `SpanScope` in `dev.jentic.core.telemetry` — an `AutoCloseable` whose
  `close()` never throws, returned by `Span.makeCurrent()`. The noop implementation
  returns a singleton (zero allocation). The OTel implementation delegates to
  `io.opentelemetry.api.trace.Span.makeCurrent()`.

  Call sites updated to `try (var scope = span.makeCurrent()) { ... }`:
  - `SimpleBehaviorScheduler.executeBehavior()`
  - `ConsumerLoop.processMessage()`
  - `ReflectionBehavior.action()` loop
  - `GuardrailChain.applyInput()` / `applyOutput()`

  Async spans (`llm.chat`, `llm.chat.stream`) use `CompletableFuture.whenComplete` and
  are unaffected — their parent is captured at `spanBuilder()` call time, which is
  correct as long as the caller already holds the right context.

## [0.21.0] - 2026-05-13

### Added

- **Redis Streams messaging adapter (ADR-021)**: distributed messaging over Redis Streams, backed by [Lettuce 7.5.1](https://lettuce.io/) (RESP-compatible with Valkey 8.x). Delivers at-least-once guarantees via consumer groups, virtual-thread consumer loops, and a dead-letter queue after configurable retry exhaustion.

  New classes in `dev.jentic.adapters.messaging.redis`:
  - `RedisMessageDispatcher` — implements the full `MessageDispatcher` interface; **primary entry point** for `JenticRuntime` integration. Routes `sendTo` via a local fast-path (same-JVM handler map) or a remote path (`AgentResolver` → `RedisMessageTransport`). Starts a shared node-stream consumer loop on first `subscribeRecipient` call.
  - `RedisTopicPublisher` — implements `TopicPublisher` + `TopicSubscriber` for fan-out via per-subscription consumer groups (`jentic:topic:<topic>` streams).
  - `RedisMessageTransport` — implements `MessageTransport` for point-to-point delivery via a shared node-scoped consumer group (`jentic:node:<nodeId>` streams).
  - `RedisMessagingFactory` — fluent builder that wires all components from a shared Lettuce `RedisClient`. Exposes `messageDispatcher()` and `messageDispatcher(Supplier<AgentResolver>)` as the recommended entry points. Implements `AutoCloseable`.
  - `RedisMessagingConfig` — immutable configuration record.
  - `RedisStreamClient` — thin Lettuce wrapper isolating sync/async API calls.
  - `ConsumerLoop` — virtual-thread consumer loop; creates the consumer group synchronously before starting, eliminating the subscribe/publish race condition.
  - `MessageCodec` — JSON serialization/deserialization of `Message` records to/from Redis stream entries.

  The Lettuce dependency is declared `optional=true` in `jentic-adapters/pom.xml` per ADR-018 — the adapter is only activated when the caller explicitly declares `io.lettuce:lettuce-core` on the classpath.

  Test coverage: 47 unit tests (Mockito + AssertJ) covering codec, config, transport, publisher, and dispatcher; Testcontainers integration tests gated by `-Dintegration.tests.enabled=true`.

- **Spring Boot starter — Redis messaging auto-configuration**: `JenticAutoConfiguration` gains a new `RedisMessagingConfiguration` inner class, activated when `io.lettuce.core.RedisClient` is on the classpath and `jentic.messaging.provider=redis`.
  - `RedisMessagingFactory` bean is lifecycle-managed (`destroyMethod="close"`).
  - `MessageDispatcher` bean (`redisMessageDispatcher`) backed by `RedisMessageDispatcher`; uses a lazy `ObjectProvider<AgentResolver>` to avoid circular dependency with `JenticRuntime`.
  - `JenticRuntime` bean (`jenticRuntime`) built with the Redis dispatcher as its messaging backend.
  - When `provider=redis` but Lettuce is absent, the starter falls back to the in-memory runtime — no startup failure.
  - New `JenticProperties.Messaging.Redis` nested record with URI-based connection configuration and sensible defaults.

  Minimal Spring Boot wiring:
  ```yaml
  jentic:
    messaging:
      provider: redis
      redis:
        uri: redis://localhost:6379
  ```

- **`jentic-examples` — `RedisMessagingExample`**: runnable demo illustrating `RedisMessageDispatcher` integrated with `JenticRuntime` — two agents (`OrderAgent` CYCLIC pub/sub producer, `FulfillmentAgent` `@JenticMessageHandler` consumer with direct reply) communicating over Redis Streams. Requires a local Redis/Valkey instance on `localhost:6379`.

- **`docs/adapters/redis.md`**: MkDocs-compatible adapter guide covering setup, `RedisMessagingFactory` builder API, Spring Boot wiring, at-least-once delivery semantics, dead-letter queue, and Testcontainers integration test recipe.

- **ADR-021 — Redis-based `MessageTransport`**: documents the choice of Redis Streams over Redis Pub/Sub and Kafka for the first distributed `MessageTransport` implementation, covering the at-least-once delivery model, consumer group strategy, dead-letter queue design, and Lettuce dependency placement.

### Tests

- **Spring Boot starter — Redis dispatcher wiring verified**: added `lettuce-core` as a `test`-scope dependency to `jentic-spring-boot-starter` (version aligned with `jentic-adapters`) and introduced two new tests in `JenticRedisMessagingAutoConfigurationTest`:
  - `redisMessageDispatcherRegisteredWhenLettucePresent` — asserts that the `MessageDispatcher` bean is an instance of `RedisMessageDispatcher` when `provider=redis` and Lettuce is on the classpath.
  - `jenticRuntimeIsWiredWithRedisDispatcherWhenLettucePresent` — asserts that `JenticRuntime.getMessageDispatcher()` returns the Redis dispatcher (not the in-memory default), proving that Spring's `@ConditionalOnMissingBean` ordering resolves `jenticRuntimeWithRedis` before `jenticRuntime`.

  Both tests use a mock `RedisMessagingFactory` supplied via `ApplicationContextRunner.withBean()` — no Redis connection required.

### Changed

- **`TopicPublisher.publish` — redundant `topic` parameter removed** (**breaking**): signature changes from `publish(String topic, Message msg)` to `publish(Message msg)`. Routing now reads `msg.topic()` directly. `IllegalArgumentException` is thrown if `msg.topic()` is `null` or blank. All callers must set `.topic(...)` on the `Message` before publishing.

- **`DirectMessenger.sendTo` — redundant `recipientAgentId` parameter removed** (**breaking**): signature changes from `sendTo(String recipientAgentId, Message msg)` to `sendTo(Message msg)`. Routing now reads `msg.receiverId()` directly. `IllegalArgumentException` is thrown if `msg.receiverId()` is `null` or blank. All callers must set `.receiverId(...)` on the `Message` before sending.

- **`MessageFilter` — dependency on deprecated `MessageService` replaced with `FilterableSubscriber`**: `MessageFilter` now accepts the focused `FilterableSubscriber` capability interface. Source-compatible for callers that pass a `MessageDispatcher` (which extends `FilterableSubscriber`).

- **Built-in agent response messaging — `sendTo` replaced with `publish`**: several built-in agents were using `sendTo` for topic-based responses. Corrected to use `publish`, aligning with pub-sub semantics.

### Migration Guide (publish / sendTo signature change)

```java
// Before (0.20.x)
dispatcher.publish("orders.created", msg);
dispatcher.sendTo("inventory-agent", msg);

// After — set topic / receiverId on the message, then drop the first argument
dispatcher.publish(Message.builder().topic("orders.created").content(data).build());
dispatcher.sendTo(Message.builder().receiverId("inventory-agent").content(data).build());

// If the message already carries topic / receiverId, simply drop the first argument:
dispatcher.publish(msg);   // routes on msg.topic()
dispatcher.sendTo(msg);    // routes on msg.receiverId()
```

## [0.20.0] - 2026-05-03

### Added

- **Core API Refactor — Capability-Sized Interfaces (ADR-020)**: the monolithic `MessageService` and `AgentDirectory` interfaces have been decomposed into focused capability interfaces that compose cleanly and work with distributed backends (Redis, JDBC, Kafka, etc.) without semantic compromise.

  **Messaging** — new interfaces in `dev.jentic.core.messaging`:
  - `TopicPublisher` — `publish(topic, msg)`
  - `TopicSubscriber` — `subscribeTopic(topic, handler)` → `Subscription`
  - `DirectMessenger` — `sendTo(agentId, msg)` with `AgentNotFoundException` on unknown agents
  - `DirectReceiver` — `subscribeRecipient(localAgentId, handler)` → `Subscription`
  - `FilterableSubscriber` — `subscribeFiltered(Predicate<Message>, handler)` → `Subscription`
  - `MessageDispatcher` — composite of the four core messaging interfaces
  - `Subscription` — replaces raw `String` subscription IDs; call `subscription.unsubscribe()` to cancel
  - `MessageTransport` — low-level transport abstraction for future remote backends

  **Directory** — new interfaces in `dev.jentic.core.directory`:
  - `AgentRegistry` — `register`, `unregister`, `updateStatus`
  - `AgentResolver` — `resolveEndpoint(agentId)` → `Optional<AgentEndpoint>`
  - `AgentDiscovery` — `findById`, `findByCapability`, `findByType`, `findAgents(AgentQuery, PageRequest)`
  - `AgentPresence` — `heartbeat`, `getStatus`
  - `AgentDirectory` — composite of all four directory interfaces

  **New value types** in `dev.jentic.core`:
  - `AgentEndpoint(nodeId, transportType, transportProps)` — transport routing record; `AgentEndpoint.local(nodeId)` factory
  - `TransportEndpoint(transportType, address, properties)` — low-level transport address record
  - `Page<T>(content, totalElements, pageNumber, pageSize)` — paginated result record
  - `PageRequest(page, size)` — pagination parameters; `PageRequest.of(page, size)` and `PageRequest.first(size)` factories
  - `AgentQuery.all()` — factory matching every registered agent
  - `AgentQuery.customFilter` deprecated; not evaluated by `InMemoryAgentDirectory` (documented in ADR-020)

  **New exception**: `AgentNotFoundException` in `dev.jentic.core.exceptions` — thrown by `sendTo` when the recipient agent is not registered.

  **`jentic-runtime` — new default implementations**:
  - `InMemoryMessageDispatcher` — replaces `InMemoryMessageService` as the default dispatcher. Delivers messages via virtual threads. Routes `sendTo` via `AgentResolver`; throws `AgentNotFoundException` for unknown recipients. Emits `message.send` OTel spans.
  - `InMemoryAgentDirectory` — replaces `LocalAgentDirectory` as the default directory. Assigns `AgentEndpoint.local(nodeId)` to newly registered agents automatically. Emits `directory.resolve` OTel spans.

  **`JenticRuntime.Builder`** — new capability setter methods (`messageDispatcher`, `agentRegistry`, `agentResolver`, `agentDiscovery`, `agentPresence`) for per-capability overrides without replacing the entire directory.

  **Spring Boot starter** — new capability beans: `jenticMessageDispatcher`, `jenticAgentDirectory`, `jenticAgentRegistry`, `jenticAgentResolver`, `jenticAgentDiscovery`, `jenticAgentPresence`. Each is `@ConditionalOnMissingBean`, so user-provided implementations always win.

  **`Agent` interface** — `getMessageDispatcher()` is now an abstract method on the core `Agent` interface. Implementations that extend `BaseAgent` are unaffected (inherited automatically). Direct `Agent` implementors must add `@Override public MessageDispatcher getMessageDispatcher()` returning their dispatcher instance.

  **Docs**: `docs/messaging.md` and `docs/directory.md` — full API reference with migration tables, Spring Boot wiring, and custom backend extension points.

- **ADR-020 — Core API Refactor for Distributed Backends**: documents the decomposition rationale, backward-compat strategy, removal timeline (0.22.0), and the decision not to evaluate `customFilter` in the paginated `findAgents` path.

### Deprecated

> These APIs will be removed in **0.22.0**. Migrate at your own pace — all deprecated code continues to compile and run unchanged.

- `dev.jentic.core.MessageService` — use `dev.jentic.core.messaging.MessageDispatcher` (and `FilterableSubscriber` for predicate subscriptions).
- `dev.jentic.core.AgentDirectory` — use `dev.jentic.core.directory.AgentDirectory`.
- `dev.jentic.runtime.messaging.InMemoryMessageService` — use `dev.jentic.runtime.messaging.InMemoryMessageDispatcher`.
- `dev.jentic.runtime.directory.LocalAgentDirectory` — use `dev.jentic.runtime.directory.InMemoryAgentDirectory`.
- `JenticRuntime.getMessageService()` — use `JenticRuntime.getMessageDispatcher()`.
- `JenticRuntime.Builder.messageService()` — use `Builder.messageDispatcher()`.
- `JenticRuntime.Builder.agentDirectory()` — use `Builder.agentRegistry()` / `agentResolver()` / `agentDiscovery()` / `agentPresence()`.
- `AgentDirectory.listAll()` — use `AgentDiscovery.findAgents(AgentQuery.all(), PageRequest.first(n))`.
- `Agent.getMessageService()` — use `Agent.getMessageDispatcher()`. The method is now a default bridge that delegates to `getMessageDispatcher()`; it throws `UnsupportedOperationException` if the returned dispatcher does not implement `MessageService`.
- `AgentDiscovery.findAgents(AgentQuery)` (non-paginated) — use `findAgents(AgentQuery, PageRequest)`.
- `AgentQuery.customFilter` field and builder method — not evaluated by the paginated path; use `AgentQuery` structured fields instead.
- `AgentDescriptor(8-arg constructor)` — use `AgentDescriptor.builder(agentId)...build()`.

### Migration Guide (0.19.x → 0.20.0)

```java
// Before (0.19.x)
MessageService svc = runtime.getMessageService();
svc.send(msg);
String id = svc.subscribe("my.topic", handler);
svc.unsubscribe(id);

// After (0.20.0)
MessageDispatcher dispatcher = runtime.getMessageDispatcher();
dispatcher.publish("my.topic", msg);
Subscription sub = dispatcher.subscribeTopic("my.topic", handler);
sub.unsubscribe();
```

```java
// Before (0.19.x)
List<AgentDescriptor> all = directory.listAll().join();

// After (0.20.0)
Page<AgentDescriptor> page = directory
    .findAgents(AgentQuery.all(), PageRequest.first(100)).join();
List<AgentDescriptor> all = page.content();
```

## [0.19.0] - 2026-04-26

### Added

- **OpenTelemetry integration (ADR-019)** — distributed tracing is now a first-class feature of the Jentic framework:
  - **`jentic-core` — observability SPI** (`JenticTelemetry`, `Span`, `SpanBuilder`, `SpanStatus`, `NoopJenticTelemetry`): a thin, dependency-free interface layer so `jentic-core` remains free of third-party imports (ADR-002). `NoopJenticTelemetry` is the zero-allocation default used whenever no OTel SDK is present.
  - **`jentic-adapters` — OTel SDK adapter** (`OtelJenticTelemetry`, `OtelTelemetryFactory`): backed by `opentelemetry-sdk` and `opentelemetry-exporter-otlp`, both declared `optional=true` per ADR-018. `OtelTelemetryFactory` provides a fluent builder and a `fromEnvironment()` factory respecting the standard `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, and `OTEL_EXPORTER_TYPE` environment variables. Supports `otlp-http`, `otlp-grpc`, and `none` exporter types.
  - **`jentic-runtime` — `InstrumentedLLMProvider`**: decorator that wraps any `LLMProvider` and emits `llm.chat` / `llm.chat.stream` spans with `llm.provider`, `llm.model`, `llm.tokens.input`, `llm.tokens.output`, and `llm.latency_ms` attributes.
  - **Instrumentation points** — the following components now emit spans when a non-noop `JenticTelemetry` is installed:

    | Component | Span name | Key attributes |
    |-----------|-----------|----------------|
    | `InstrumentedLLMProvider` | `llm.chat`, `llm.chat.stream` | `llm.provider`, `llm.model`, `llm.tokens.input/output`, `llm.latency_ms` |
    | `GuardrailChain` | `guardrail.evaluate` | `guardrail.name`, `guardrail.decision` |
    | `HumanCheckpointBehavior` | `hitl.approval` | `hitl.request_id`, `hitl.decision`, `hitl.wait_ms` |
    | `SimpleBehaviorScheduler` | `behavior.execute` | `behavior.id`, `behavior.type`, `agent.id` |
    | `JenticMcpClientAdapter` | `mcp.tool.call` | `mcp.tool.name`, `mcp.transport` |
    | `ReflectionBehavior` | `reflection.iteration` | `reflection.iteration`, `reflection.accepted` |

  - **`JenticRuntime.Builder.telemetry(JenticTelemetry)`**: wires a telemetry instance into the runtime; all components receive it automatically. Falls back to `NoopJenticTelemetry` when not set.
  - **`LLMAgent.installTelemetry(JenticTelemetry)`**: wraps the agent's `LLMProvider` in `InstrumentedLLMProvider`; called automatically by `JenticRuntime.registerAgent()`.
  - **Spring Boot starter — `TelemetryConfiguration`** (`@ConditionalOnClass(OpenTelemetry.class)`): auto-configures `OtelJenticTelemetry` when the OTel SDK is on the classpath. New YAML properties: `jentic.telemetry.enabled`, `jentic.telemetry.exporter` (`otlp-http` | `otlp-grpc` | `none`), `jentic.telemetry.endpoint`, `jentic.telemetry.service-name`. Falls back to `NoopJenticTelemetry` when OTel is absent or `jentic.telemetry.enabled=false`.
  - **`jentic-examples` — `ObservabilityExample`** and companion `docker-compose.yml` (Jaeger all-in-one): a runnable example demonstrating opt-in OTel activation via `OtelTelemetryFactory`, LLM calls with guardrails, and trace visualisation in Jaeger at `http://localhost:16686`.
  - **`docs/observability.md`**: span taxonomy, metrics reference, OTel Collector setup guide, and step-by-step opt-in instructions for both programmatic and Spring Boot wiring.
- **ADR-018 — Optional adapter dependencies pattern**: codifies when to use `optional=true` inside `jentic-adapters` vs a dedicated Maven sub-module. Prevents per-adapter re-debate as new backends (Redis, JDBC, Kafka) are added. ADR-003 updated to cross-reference ADR-018; `jentic-adapters/README.md` extended with the opt-in contract and a minimum consumer POM snippet.
- **ADR-019 — OpenTelemetry instrumentation strategy**: documents the no-op abstraction layer, the `optional=true` dependency placement, the instrumentation points, context propagation via `ScopedValue` on virtual threads, and the classpath-isolation guarantee.

### Changed

- **`OtelJenticTelemetry` now implements `AutoCloseable`**: retains a reference to the `OpenTelemetrySdk` instance (previously discarded after construction, making the SDK eligible for GC along with its `BatchSpanProcessor`). `close()` calls `OpenTelemetrySdk.close()`, which blocks until the `BatchSpanProcessor` has exported all buffered spans.
- **`JenticRuntime.stop()` flushes telemetry on shutdown**: after stopping all agents and the behavior scheduler, `stop()` calls `close()` on the `JenticTelemetry` instance if it implements `AutoCloseable`. This ensures the OTel `BatchSpanProcessor` exports its buffer before the process exits.

### Fixed

- **`OtelJenticTelemetry` — spans silently discarded on process exit**: the `OpenTelemetrySdk` instance created by `OtelTelemetryFactory.build()` was not retained by `OtelJenticTelemetry`. Only the `Tracer` was stored; the SDK (including its `BatchSpanProcessor` and export queue) became eligible for GC immediately after `build()` returned. Combined with the missing `stop()` integration, all buffered spans were dropped on shutdown and never reached the collector. Fixed by retaining the SDK reference and calling `sdk.close()` from the new `AutoCloseable.close()` implementation.
- **`OtelTelemetryFactory` — HTTP exporter returned HTTP 404 from Jaeger**: `OtlpHttpSpanExporter.setEndpoint()` takes the full URL including the signal path — it does **not** auto-append `/v1/traces`. Passing a bare base URL (e.g. `http://localhost:4318`, as produced by the standard `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable) caused Jaeger to return HTTP 404. The factory now appends `/v1/traces` when the supplied endpoint does not already contain a `/v1/` path segment. The internal `DEFAULT_ENDPOINT_HTTP` constant is updated to `http://localhost:4318/v1/traces` accordingly.

## [0.18.0] - 2026-04-15

### Changed
- **`jentic-spring-boot-starter` — migrated to Spring Boot 4.0.5** (from 3.5.13): Spring Boot 3.x reaches end of open-source support in June 2026. Spring Boot 4.0.5 requires Spring Framework 7 and Jakarta EE 11.
  - Spring Boot BOM version updated to `4.0.5`; redundant SnakeYAML version pin removed (managed by Boot BOM).
  - `SmartLifecycle` implementation now overrides `isPauseable()` returning `false` — Spring Framework 7 introduced context-pausing support; the Jentic runtime has no pause/resume semantics.
  - **Breaking change for actuator users**: Spring Boot 4.0 renamed the actuator health package from `org.springframework.boot.actuate.health` to `org.springframework.boot.health.contributor`. `JenticHealthIndicator` and the `@ConditionalOnClass` guard in `JenticAutoConfiguration.ActuatorConfiguration` updated accordingly. Applications using a custom `HealthIndicator` bean that overrides Jentic's must update their import.
- **ADR-016** updated to reflect the completed Spring Boot 4.0.x migration and document the actuator package rename.

## [0.17.0] - 2026-04-14

### Fixed
- **`JenticRuntime` — `LLMMemoryManager` not injected into non-`BaseAgent` `LLMMemoryAware` agents**: the injection block was nested inside `if (agent instanceof BaseAgent)`, so any plain `Agent` implementor that implemented `LLMMemoryAware` never received an `LLMMemoryManager`. The check is now independent of the `BaseAgent` guard.
- **`JenticRuntime` — `LLMMemoryManager` injection gated on `memoryStore != null`**: when a custom `llmMemoryManagerFactory` was provided without an explicit `MemoryStore`, the factory was silently skipped because the injection was nested inside the `if (memoryStore != null)` block. The two checks are now independent.

### Changed
- **`AgentFactory` — removed redundant service injection**: `configureBaseAgent()` was duplicating the service injection already performed by `JenticRuntime.registerAgent()`. `AgentFactory.createAgent()` now only handles instantiation and descriptor creation; all service injection (including `LLMMemoryManager`) is the sole responsibility of `registerAgent()`.

### Tests
- **`JenticRuntimeTest` — added coverage for `LLMMemoryManager` injection**: four new tests verify the scenarios addressed by the fix: injection into a `BaseAgent`+`LLMMemoryAware` agent, injection without a `MemoryStore`, injection into a plain `Agent` implementor that is `LLMMemoryAware`, and absence of injection (no NPE) when no factory is configured.
- **Docs — split memory and persistence guides**: extracted the Agent State Persistence section from `docs/memory.md` into a dedicated `docs/persistence.md`. The memory guide now covers the key-value memory system only (`MemoryStore`, `MemoryScope`, `InMemoryStore`); persistence concepts (`Stateful`, `AgentState`, `FilePersistenceService`, `PersistenceManager`, `@JenticPersistenceConfig`) are documented in the new guide. Cross-links and the `mkdocs.yml` nav updated accordingly.

## [0.16.0] - 2026-04-12

### Added
- **ADR-017 — `LLMRequest.model` optional with provider fallback**: the `model` field on `LLMRequest` is now optional. Providers resolve the effective model using this precedence: `request.model()` (explicit per-request override) → provider's configured `modelName` → `LLMException("No model specified")`.
  - New `LLMRequest.builder()` no-arg factory — preferred entry point when no per-request override is needed.
  - New `LLMRequest.Builder.model(String)` setter for explicit per-request overrides.
  - `LLMProvider.validateRequest()` no longer rejects a null model; resolution happens at execution time inside each adapter.
  - `OpenAIProvider`, `AnthropicProvider`, and `OllamaProvider` each implement a private `resolveModel(LLMRequest)` method that applies the precedence rule.

### Fixed
- **`MessageHistoryService` race condition in `store()`**: concurrent calls could corrupt the size counter and evict extra messages due to a TOCTOU gap between `addFirst`, `incrementAndGet`, and `pollLast`. The add-increment-evict sequence is now protected by a `ReentrantLock`, making writes fully atomic. `clear()` acquires the same lock to prevent interleaving with concurrent stores.

### Changed
- **`LLMRequest.builder(String model)` deprecated** (`since = "0.16.0"`, `forRemoval = true`): replaced by `LLMRequest.builder()` + optional `.model(String)` call. Existing callers compile with a deprecation warning and behave identically.
- **`DefaultReflectionStrategy`**: removed the hard-coded `"critique"` placeholder model from its internal `LLMRequest`; the injected provider's configured model is now used automatically.

## [0.15.0] - 2026-04-09

### Added
- **.editorconfig**: added project-wide coding style configuration file.
- **New models support (2026-04 update)**: updated LLM providers with current state-of-the-art models:
  - OpenAI: GPT-4.1 family, o3/o4-mini.
  - Anthropic: Claude 4.x series.
  - Ollama: Llama 3.x, Qwen 2.5, DeepSeek-R1.

### Changed
- **Model Enums Implementation**: replaced static maps with provider-specific enums (`OpenAIModel`, `AnthropicModel`, `OllamaModel`) for better type safety and maintainability.
- **`ModelTokenLimits` Decentralization (BREAKING CHANGE)**: moved token limit ownership from `jentic-runtime` to individual adapters (`jentic-adapters`).
  - `ModelTokenLimits` is now a generic registry in `jentic-core` (`dev.jentic.core.memory.llm`).
  - Adapters now register their own models and context sizes on class load.
  - Dependency inversion fix: adapters no longer depend on runtime for model registration.
- **Dependency updates**: bumped `langchain4j` to version `1.12.2`.

### Fixed
- **`WebhookApprovalNotifier`**: now correctly restores the interrupt flag on HTTP client timeout, ensuring retry loops are not interrupted prematurely.
- **Dual source of truth for models**: synchronized `getAvailableModels()` and `ModelTokenLimits` in all providers to prevent divergence.
- **Documentation**: updated `configuration.md` to remove outdated "future" references.

## [0.14.1] - 2026-03-30

### Fixed

- `SequentialBehavior` (repeating mode): `ONE_SHOT` child behaviors were silently
  skipped on the second and subsequent cycles because `isActive()` returned `false`
  after their first execution. The index wrap-around now calls `activate()` on all
  children (via `instanceof BaseBehavior` cast) to re-arm them for the next cycle.

## [0.14.0] - 2026-03-30

### Added
- **Spring Boot Starter (ADR-016)**: Introduced `jentic-spring-boot-starter` for seamless integration with Spring Boot applications.
  - Auto-configuration for `JenticRuntime` based on classpath scanning and configuration properties.
  - Support for `JenticProperties` to configure agent packages, LLM providers, and memory settings.
  - Dedicated documentation guide for Spring Boot Starter in `docs/spring-boot-starter.md`.
- **`SchedulingHint` enum** (`jentic-core`, `dev.jentic.core.composite`): declares how a `CompositeBehavior` wants to be driven by the scheduler (`ONCE`, `CYCLIC`, `ON_DEMAND`). Eliminates the need to wrap workflow composites in a `CyclicBehavior` driver.
- **`CompositeBehavior.getSchedulingHint()`**: new method (default `ON_DEMAND`) that workflow composites override to express their scheduling intent.
- **`SimpleBehaviorScheduler.scheduleComposite()`**: new private method that reads `SchedulingHint` and dispatches `SEQUENTIAL`/`PARALLEL` to `scheduleOneShot()` or `scheduleCyclic()` automatically. `FSM`, `RETRY`, `CIRCUIT_BREAKER`, and `PIPELINE` remain `ON_DEMAND`.
- **`SequentialBehavior.withStepTimeout(Duration)`**: fluent method to set a per-step timeout on both one-shot and repeating instances without requiring a dedicated constructor.

### Changed
- **`SequentialBehavior` — auto-scheduling (BREAKING CHANGE)**: `addBehavior()` is now sufficient to start a `SequentialBehavior`; no manual `execute()` call required.
  - One-shot mode (`SchedulingHint.ONCE`): all steps run once, then `active=false`. `getCurrentStep()` returns the total step count on completion.
  - Repeating mode (`SchedulingHint.CYCLIC`): each scheduler tick advances one step and wraps around immediately after the last step.
  - **Constructor API simplified**: removed `boolean repeatSequence` parameter and the 3-arg `(String, boolean, Duration)` / 4-arg constructors. Mode is now implicit: `new SequentialBehavior(id)` → one-shot; `new SequentialBehavior(id, interval)` → repeating.
  - `isRepeatSequence()` removed; replaced by `isRepeating()` (derived from `interval != null`).
- **`ParallelBehavior` — auto-scheduling**: `addBehavior()` is now sufficient; the behavior fires all children immediately upon registration (`SchedulingHint.ONCE`).
  - Fixed double-increment bug in `executeNOfM()`: `completedCount` was incremented twice per successful child (once in `executeChild()`, once in `executeNOfM()`), causing the N-of-M future to complete prematurely.
  - `addChild()` references in documentation corrected to `addChildBehavior()`.
- **`@JenticBehavior` annotation**: removed `repeatSequence()` attribute. Repeating sequential behaviors are now expressed via the existing `interval()` attribute, consistent with `CYCLIC` behavior.
- **`AnnotationProcessor.createSequentialBehavior()`**: updated to use new `SequentialBehavior` constructor API and `withStepTimeout()`.
- **`CompositeBehavior`**: added `protected Duration interval` field and improved Javadoc (class-level sections for scheduling, child management, thread safety, and implementation guide).
- **Documentation**: Standardized documentation headers and formatting across core and runtime packages for better consistency.
- **Project Structure**: Updated parent `pom.xml` and module-specific configurations to include the new Spring Boot Starter module.

### Fixed
- **`SequentialBehavior` one-shot**: `getCurrentStep()` now correctly returns `size()` (total steps) after completion instead of resetting to `0`.
- **`SequentialBehavior` repeating**: `currentIndex` now wraps to `0` immediately after the last step, not deferred to the next `execute()` call.
- **`SimpleBehaviorScheduler`**: `SEQUENTIAL`/`PARALLEL` behaviors registered via `addBehavior()` were silently ignored (treated as on-demand). Now dispatched correctly via `scheduleComposite()`.
- **`ParallelBehavior.executeNOfM()`**: fixed premature completion caused by double-counting `completedCount`.

## [0.13.0] - 2026-03-25

### Added
- **Human-in-the-Loop Checkpoint (ADR-015)**: Introduced a mechanism to pause agent execution and wait for human approval before proceeding.
  - Core abstractions in `jentic-core`: `ApprovalRequest`, `ApprovalDecision`, `ApprovalGate`, `ApprovalNotifier`, and `ApprovalTimeoutException`.
  - `@RequiresApproval` annotation for declarative wiring of human checkpoints to agents.
  - Implementation in `jentic-runtime`: `ApprovalService` to manage the lifecycle of approval requests.
  - Built-in `ApprovalGate` implementations: `InMemoryApprovalGate` for local testing.
  - Built-in `ApprovalNotifier` implementations: `WebhookApprovalNotifier` for remote notifications and `LoggingApprovalNotifier`.
  - New dedicated guide for Human-in-the-Loop and `HumanCheckpointBehaviorTest` in `jentic-runtime`.
- **Guardrails Layer (ADR-014)**: Introduced a declarative interceptor chain for `LLMAgent` to validate and transform inputs and outputs.
  - Core abstractions in `jentic-core`: `GuardrailResult` (sealed interface), `InputGuardrail`, `OutputGuardrail`, and `GuardrailContext`.
  - `GuardrailChain` in `jentic-runtime` for sequential execution and short-circuiting on violations.
  - `@WithGuardrails` annotation for declarative wiring of guardrails to agents.
  - Built-in implementations: `PiiRedactionGuardrail`, `ContentPolicyGuardrail`, `MaxTokensInputGuardrail`, and `JsonSchemaOutputGuardrail`.
  - New dedicated guide for Guardrails and `GuardrailsExample` in `jentic-examples`.
- **Model Context Protocol (MCP) Integration (ADR-013)**: Support for official MCP SDK to connect external tools to LLM workflows.
  - `JenticMcpClientAdapter` and `McpClientFactory` for synchronous to asynchronous SDK bridging.
  - `McpToolRegistry` with TTL support for efficient tool caching and discovery.
  - `McpFunctionAdapter` to map MCP tools to Jentic function-calling framework.
  - Core abstractions: `McpClient`, `McpTool`, and `McpToolResult` in `jentic-core`.
- **MCP Documentation**: Detailed guide for MCP adapter and architecture overview in `docs/adapters/mcp.md`.
- **MCP Example**: `McpExample` demonstrating Docker-based STDIO transport for MCP servers in `jentic-examples`.
- **Branding Assets**: Added official Jentic logo and wordmark to `docs/assets`.

### Changed
- **Documentation**: Significant refactoring of the documentation structure, including updated ADRs (ADR-002, ADR-004, ADR-015) and a simplified `README.md` with removed outdated roadmap.
- **Project Structure**: Updated ADR documentation with ADR-013, ADR-014, ADR-015 and expanded `mkdocs.yml` navigation for MCP, Guardrails and HITL support.

### Fixed
- **Documentation**: Fixed version annotation in `ReflectionBehavior.md`.

## [0.12.0] - 2026-03-14

### Added
- **Reflection Pattern (ADR-012)**: Introduced `ReflectionStrategy` and `ReflectionBehavior` for the Generate → Critique → Revise loop.
  - `ReflectionStrategy`, `CritiqueResult`, and `ReflectionConfig` added to `jentic-core` as core abstractions.
  - `DefaultReflectionStrategy` and `ReflectionBehavior` added to `jentic-runtime` for LLM-backed self-critique.
- **Reflection Example**: Added `ReflectionExample` demonstrating the self-correction loop in `jentic-examples`.
- **Documentation**: New dedicated guide for `ReflectionBehavior` and updated `mkdocs.yml` navigation.

### Changed
- **Project Structure**: Expanded ADR documentation with ADR-012 and updated README with Support and Development sections.

## [0.11.0] - 2026-03-11

### Added
- **Configuration-driven package scanning**: `JenticRuntime` now uses `getAllScanPackages()` from configuration for agent discovery.

### Changed
- **Configuration Guide**: Clarified builder method behavior and unchecked `ConfigurationException` handling in documentation.
- **Exception Hierarchy (BREAKING CHANGE)**: Restructured all core exceptions (LLM, Persistence, Memory, Embedding) to inherit from `JenticException` (a `RuntimeException`) and moved them to their respective functional packages (e.g., `dev.jentic.core.persistence`).
- **Configuration Loading**: Simplified `ConfigurationLoader` API by removing explicit checked `ConfigurationException` from `loadFromFile`.
- **Validation Logic**: Improved configuration validation in `JenticRuntime.Builder`, ensuring invalid configurations are caught early.

### Fixed
- **Documentation Workflows**: Fixed table formatting in ADR index and link formatting in documentation deployment workflows.

## [0.10.0] - 2026-03-07

### Added
- **`LLMMemoryAware` interface** in `dev.jentic.core.llm`: marker interface that allows any `Agent` implementor (including those that cannot extend `LLMAgent`) to receive an injected `LLMMemoryManager` from the runtime. `LLMAgent` now implements this interface; `JenticRuntime` injects via `LLMMemoryAware` instead of `instanceof LLMAgent`.
- **`AgentContext` support** for plain `Agent` implementations and improved runtime agent creation.
- **LLM-based summarization** in `SummarizationStrategy` for context window management.
- Promotion of `KnowledgeStore` and `EmbeddingProvider` from adapters to core/runtime for broader availability.

### Fixed
- Increased timing thresholds in `ParallelBehaviorTest` and `SequentialBehaviorTest` for CI reliability.
- Use of dedicated `CachedThreadPool` in test behaviors to prevent ForkJoinPool starvation on CI.

### Changed
- Update of LLM integration guide with new summarization and knowledge store features.
- Updated Logback to version `1.5.32`.
- Updated AssertJ to version `3.27.7`.

## [0.9.0] - 2026-03-04

### Added
- "Getting Started" guide and documentation index.
- GitHub Actions workflow for automatic documentation deployment.
- Test coverage for `jentic-adapters` module.
- Support for detailed Javadoc annotations and usage examples in Jentic annotations.

### Fixed
- Synchronization in `ratelimit` to prevent limit overruns in concurrency scenarios.
- Broken links in README.md file.
- Path normalization in documentation deployment workflow.

### Changed
- Optimization of Maven Javadoc configuration.
- Standardization of link formatting throughout documentation.

## [0.8.0] - 2026-02-28

### Added
- Complete documentation for all behavior types.
- README "Learning Path" for `jentic-examples` module.

### Changed
- Refactoring of examples for a more linear structure and pattern-oriented naming.
- Replacement of `ConfigurationLoader` class with a cleaner interface.
- Improvement of `SimpleBehaviorScheduler` to handle additional behavior types.

### Fixed
- Correction of thresholds in system conditions (CPU usage).
- Simplification of agent registration in `BatchProcessing` example.

## [0.7.1] - 2026-02-24

### Fixed
- Improvement of `STOPPING` state validation in `LifecycleManagerTest`.
- More robust handling of asynchronous operations in the agent lifecycle.

## [0.7.0] - 2026-02-22

### Added
- **Bill of Materials (BOM)** module for centralized version management.
- Support for **A2A (Agent-to-Agent)** protocol with Jetty/HTTP-based implementation.
- LLM integration pattern: **Orchestrator-Workers**.
- **Support Chatbot** example with RAG (Retrieval-Augmented Generation) and TF-IDF semantic search.
- Support for **Automatic-Module-Name** (JPMS) in all modules.
- Extended test framework with JaCoCo coverage and new unit tests for core, runtime, and adapters.
- Code of Conduct and Security Policy.

### Fixed
- Handling of NaN/negative values in system metrics.
- Race condition in agent registration during startup.
- Various fixes in timing tests (ScheduledBehavior, WakerBehavior).

## [0.6.0] - 2026-02-14

### Added
- **LLM Memory Management** system with automatic context injection.
- Strategies for Context Window management in AI agents.
- `AIAgent` base class to facilitate development of agents with LLM support.
- Integration of `MemoryStore` into `JenticRuntime`.

### Changed
- Moved `LLMMemoryManager` responsibility directly into `LLMAgent`.

## [0.5.0] - 2026-02-07

### Added
- **Agent Evaluation Framework** for agent testing and validation.
- Full implementation of dialogue protocols: **ContractNet, Query, Request**.
- Support for utilities to convert dialogues into A2A messages.
- A2A integration example.

### Changed
- Refactoring of `ContractNet` example to use `JenticRuntime`.

## [0.4.0] - 2025-11-20

### Added
- **Jentic Web Console**: web interface for agent monitoring and management.
- Support for message history storage with dedicated REST API.
- CLI tools for message monitoring and watching.
- `MessageSnifferAgent` for passive traffic monitoring.
- `AIAssistantAgent` example with LLM-based tool execution.

### Fixed
- Reactivation of behaviors after agent restart.
- Resolution of classpath in `AgentScanner` for CLI execution.
- Uptime calculation in `RestAPIHandler`.

## [0.3.0] - 2025-11-04

### Added
- Integration with LLM providers: **OpenAI, Anthropic, and Ollama**.
- Support for streaming, function calling, and LLM request/response logging.
- `ResearchTeam` example with agent collaboration and dynamic discovery.
- `baseUrl` configuration for LLM providers (support for proxies and local LLMs).

### Fixed
- Metadata loss in `AgentDescriptor`.
- NPE in handling null content in `OpenAIProvider`.

## [0.2.0] - 2025-10-27

### Added
- Support for **YAML Configuration**.
- New Behavior types:
  - `BatchBehavior`: batch processing by size or time.
  - `RetryBehavior`: retry strategies with backoff.
  - `CircuitBreakerBehavior`: resilience patterns.
  - `PipelineBehavior`: staged processing.
  - `ScheduledBehavior`: cron-like scheduling.
  - `ThrottledBehavior`: rate limiting (Token Bucket, Sliding Window).
  - `CompositeBehavior`: sequential, parallel, and FSM.
  - `ConditionalBehavior`.
- Support for file-based persistence and lifecycle hooks.
- Advanced message filtering and direct messaging.

## [0.1.1] - 2025-10-18

### Fixed
- Minor documentation fixes (README).

## [0.1.0] - 2025-10-17

### Added
- Initial release of Jentic framework.
- Core abstractions for Agents and Behaviors.
- `JenticRuntime` for agent lifecycle management.
- `LifecycleManager` for agent state monitoring.
- Support for agent discovery via annotations.
- ADR-based architecture (Architectural Decision Records).
- Architecture guide and initial documentation.

[Unreleased]: https://github.com/mauro-mura/jentic/compare/v0.23.0...HEAD
[0.23.0]: https://github.com/mauro-mura/jentic/compare/v0.22.0...v0.23.0
[0.22.0]: https://github.com/mauro-mura/jentic/compare/v0.21.0...v0.22.0
[0.21.0]: https://github.com/mauro-mura/jentic/compare/v0.20.0...v0.21.0
[0.20.0]: https://github.com/mauro-mura/jentic/compare/v0.19.0...v0.20.0
[0.19.0]: https://github.com/mauro-mura/jentic/compare/v0.18.0...v0.19.0
[0.18.0]: https://github.com/mauro-mura/jentic/compare/v0.17.0...v0.18.0
[0.17.0]: https://github.com/mauro-mura/jentic/compare/v0.16.0...v0.17.0
[0.16.0]: https://github.com/mauro-mura/jentic/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/mauro-mura/jentic/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/mauro-mura/jentic/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/mauro-mura/jentic/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/mauro-mura/jentic/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/mauro-mura/jentic/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/mauro-mura/jentic/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/mauro-mura/jentic/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/mauro-mura/jentic/compare/v0.7.1...v0.8.0
[0.7.1]: https://github.com/mauro-mura/jentic/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/mauro-mura/jentic/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/mauro-mura/jentic/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/mauro-mura/jentic/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/mauro-mura/jentic/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/mauro-mura/jentic/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/mauro-mura/jentic/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/mauro-mura/jentic/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/mauro-mura/jentic/releases/tag/v0.1.0
