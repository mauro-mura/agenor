# Redis Messaging Adapter

Implements the full `MessageDispatcher` interface on top of **Redis Streams**
(`XADD` / `XREADGROUP` / `XACK`). Provides at-least-once delivery, message durability,
and fan-out pub/sub across multiple JVM nodes without requiring additional infrastructure
beyond a RESP-compatible server.

`RedisMessageDispatcher` is the primary entry point — it composes `RedisTopicPublisher`
(topic pub/sub) and `RedisMessageTransport` (inter-node point-to-point) into the single
`MessageDispatcher` interface that `JenticRuntime` expects. Lower-level components are
available via `RedisMessagingFactory` for cases where fine-grained control is needed.

Architectural rationale: ADR-021 — Redis MessageTransport (repository: `docs/adr/`)

---

## Prerequisites

Start a Valkey (or Redis-compatible) server before using this adapter:

```bash
# Valkey via Docker — recommended for local development
docker run -d -p 6379:6379 valkey/valkey:8

# or with the compose file in this repo (if present)
docker compose up valkey
```

---

## Maven dependency (opt-in)

`jentic-adapters` declares Lettuce as `optional=true` per ADR-018 (Optional Adapter Dependencies Pattern).
Consumers that want Redis messaging must add Lettuce explicitly:

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-adapters</artifactId>
    <version>${jentic.version}</version>
</dependency>
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>7.5.1.RELEASE</version>
</dependency>
```

Consumers that declare only `jentic-adapters` continue to use the in-memory dispatcher —
no `ClassNotFoundException`, no configuration required.

### Dependency convergence note

Lettuce 7.5.1 pulls `reactor-core:3.6.x`. If your project also depends on MCP (which requires
`reactor-core:3.7.0`), pin the upper bound in your `dependencyManagement`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
            <version>3.7.0</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## Quick start — with JenticRuntime (recommended)

```java
try (var factory = RedisMessagingFactory.builder()
        .uri("redis://localhost:6379")
        .consumerGroupPrefix("my-app")
        .build()) {

    // messageDispatcher() returns a RedisMessageDispatcher — the full MessageDispatcher
    // implementation that JenticRuntime.Builder.messageDispatcher() accepts.
    JenticRuntime runtime = JenticRuntime.builder()
            .messageDispatcher(factory.messageDispatcher())
            .build();

    runtime.registerAgent(new MyAgent());
    runtime.start().join();

    // ... run for a while ...

    runtime.stop().join();
} // factory.close() stops all consumer loops and closes the Lettuce connection
```

Agent code is identical to the in-memory case — swap the dispatcher, keep the agents.

### Direct API (advanced)

Use `factory.topicPublisher()` and `factory.messageTransport()` directly only when you
need fine-grained control outside of a `JenticRuntime` context:

```java
try (var factory = RedisMessagingFactory.builder()
        .uri("redis://localhost:6379")
        .build()) {

    var publisher = factory.topicPublisher();  // TopicPublisher + TopicSubscriber

    // Fan-out subscribe
    publisher.subscribeTopic("orders.created", msg -> {
        System.out.println("Received: " + msg.content());
        return CompletableFuture.completedFuture(null);
    });

    // Publish
    publisher.publish(Message.builder()
            .topic("orders.created")
            .senderId("checkout-service")
            .content("{\"orderId\":\"ORD-001\"}")
            .build()).join();
}
```

---

## Stream topology

| Stream | Redis key pattern | Consumer group | Usage |
|--------|------------------|----------------|-------|
| Topic stream | `<prefix>:topic:<topicName>` | `<prefix>:cg:<subscriptionId>` | Fan-out pub/sub (one group per subscription) |
| Node stream | `<prefix>:node:<nodeId>` | `<prefix>:cg:node` | Point-to-point delivery |
| Dead-letter | `<sourceStreamKey>:dlq` | — (written with `XADD`) | Messages that exceeded `maxDeliveryAttempts` |

`<prefix>` defaults to `jentic`, configurable via `consumerGroupPrefix`.

`nodeId` is a UUID generated once at `RedisMessagingFactory.build()`. In a multi-node
deployment each JVM instance gets a different `nodeId` and therefore a different node stream.

### Fan-out mechanics

Every call to `subscribeTopic(topic, handler)` creates a **new consumer group** with a
unique subscription ID. All groups on the same topic stream each receive every message
independently — this is how Redis Streams achieves fan-out. Two subscribers on the same
topic each receive a copy even within the same JVM.

### Point-to-point mechanics

`RedisMessageDispatcher.sendTo(msg)` routes on `msg.receiverId()`:

1. **Local fast-path** — if the recipient called `subscribeRecipient(agentId, handler)` on
   the same dispatcher instance (same JVM), the message is delivered directly to the handler
   with no Redis hop.
2. **Remote path** — if an `AgentResolver` is configured, the dispatcher resolves
   `receiverId → AgentEndpoint → nodeId` and calls `RedisMessageTransport.send()`, which
   writes to `jentic:node:<nodeId>`. The target node's consumer loop picks up the message
   and routes it to the matching local handler via `receiverId`.

`subscribeRecipient` also starts a node-stream consumer loop on the first call (lazy,
thread-safe double-checked locking). Subsequent registrations share the same loop.

---

## Delivery guarantees

| Property | Behaviour |
|----------|-----------|
| Guarantee | **At-least-once** — messages are redelivered until `XACK`'d |
| Durability | Persisted in the Redis stream; survives broker restart with AOF/RDB |
| Order | Per-stream FIFO within a consumer group |
| Fan-out | Each subscription receives every message exactly once (within that subscription) |
| Handler contract | Handlers **must be idempotent** — the same message may be delivered more than once after a crash or timeout |

---

## Failure modes and recovery

### Unacknowledged messages (handler exception)

If a handler throws or its returned `CompletableFuture` completes exceptionally, the
consumer loop does **not** call `XACK`. The message enters the Pending Entries List (PEL) and
is redelivered after `pendingEntriesTimeoutMs` (default 30 s) by the same consumer loop
on the next claim pass.

### Maximum delivery attempts

After `maxDeliveryAttempts` consecutive failures (default 3) the message is moved to the
dead-letter stream (`<sourceStreamKey>:dlq`, e.g. `jentic:topic:orders.created:dlq`) and acknowledged from the source stream.
No further delivery is attempted.

### Dead-letter stream

The DLQ is a plain Redis stream. Monitor it with:

```bash
xlen jentic:topic:orders.created:dlq   # entry count
xrange jentic:topic:orders.created:dlq - +  # inspect entries
```

To replay, copy entries back to the source stream or re-publish them via `publisher.publish()`.
There is no built-in replay API; the DLQ is intentionally kept simple.

### Consumer loop crash / JVM restart

Pending entries older than `pendingEntriesTimeoutMs` are reclaimed by the next consumer
loop startup. A restarted JVM will find its own PEL and retry delivery automatically.

### Network partition

Consumer loops use `XREADGROUP BLOCK <readBlockTimeoutMs>`. On reconnect (Lettuce
auto-reconnect), the loop resumes reading from the last acknowledged ID. No messages are
lost; pending entries are claimed on the next iteration.

---

## RESP compatibility matrix

| Server | Version | Status | Notes |
|--------|---------|--------|-------|
| **Valkey** | 8.x | Tested | Primary target. `CLIENT MAINT_NOTIFICATIONS` logged as warning by Lettuce — non-fatal, Valkey does not support this Redis commercial command. |
| Valkey | 7.2 | Compatible | Same command surface |
| Redis OSS | 7.2 | Compatible | Last Apache-licensed release |
| Redis OSS | 7.4+ | Compatible | SSPL licence; commands unchanged |
| Redis Enterprise | 7.x | Compatible | No vendor-specific commands used |
| KeyDB | — | Untested | RESP3-compatible; likely works |

All commands used by this adapter (`XADD`, `XREADGROUP`, `XACK`, `XAUTOCLAIM`,
`XGROUP CREATE`, `XLEN`) are part of the standard RESP3 subset supported by all
implementations listed above.

---

## Configuration reference

All properties are set via `RedisMessagingFactory.builder()` (standalone) or
the `jentic.messaging.redis.*` sub-section (Spring Boot).

| Builder method | Spring Boot key | Default | Description |
|----------------|-----------------|---------|-------------|
| `uri(String)` | `jentic.messaging.redis.uri` | `redis://localhost:6379` | Redis connection URI. Supports `redis://`, `rediss://` (TLS), `redis-sentinel://` |
| `consumerGroupPrefix(String)` | `jentic.messaging.redis.consumer-group-prefix` | `jentic` | Prefix for all stream keys and consumer group names |
| `readBlockTimeoutMs(long)` | `jentic.messaging.redis.read-block-timeout-ms` | `2000` | How long `XREADGROUP BLOCK` waits before returning empty (ms) |
| `maxStreamLength(int)` | `jentic.messaging.redis.max-stream-length` | `100000` | Approximate maximum entries per stream before trimming |
| `pendingEntriesTimeoutMs(long)` | `jentic.messaging.redis.pending-entries-timeout-ms` | `30000` | Idle time before an unacknowledged pending entry is redelivered (ms) |
| `maxDeliveryAttempts(int)` | `jentic.messaging.redis.max-delivery-attempts` | `3` | Delivery failures before the message is moved to the DLQ |

### URI schemes

| Scheme | Usage |
|--------|-------|
| `redis://host:port` | Standalone (default) |
| `rediss://host:port` | TLS/SSL |
| `redis-sentinel://password@host:port,host:port/masterId` | Sentinel (HA) |

---

## Spring Boot auto-configuration

Add the starter and Lettuce to your POM:

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-spring-boot-starter</artifactId>
    <version>${jentic.version}</version>
</dependency>
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>7.5.1.RELEASE</version>
</dependency>
```

Then set the provider in `application.yml`:

```yaml
jentic:
  messaging:
    provider: redis
    redis:
      uri: redis://localhost:6379
      consumer-group-prefix: my-app
      read-block-timeout-ms: 2000
      max-stream-length: 100000
      pending-entries-timeout-ms: 30000
      max-delivery-attempts: 3
```

The auto-configuration activates only when **both** conditions are true:
- `io.lettuce.core.RedisClient` is on the classpath, and
- `jentic.messaging.provider=redis` is set.

If either condition is false the in-memory dispatcher remains active — no error is thrown.

### Beans registered

| Bean type | Bean name | Description |
|-----------|-----------|-------------|
| `RedisMessagingFactory` | `redisMessagingFactory` | Lifecycle-managed factory; `close()` called on context shutdown |
| `MessageDispatcher` | `redisMessageDispatcher` | `RedisMessageDispatcher` — full `MessageDispatcher` wired with lazy `AgentResolver` |
| `JenticRuntime` | `jenticRuntime` | Runtime built with the Redis dispatcher as its messaging backend |

All beans are conditional on `@ConditionalOnMissingBean`, so you can override any of them
by declaring your own bean of the same type.

The `AgentResolver` is injected lazily via `ObjectProvider` to avoid a circular dependency:
the resolver (backed by `AgentDirectory`) is fetched only at `sendTo()` call time, after
the runtime has fully started.

---

## FilterableSubscriber — not supported

`FilterableSubscriber` (predicate-based subscriptions) is intentionally omitted. Redis
Streams do not support server-side predicate evaluation; implementing it client-side via
read-then-filter would defeat the purpose of the capability split in ADR-020. Apply
filtering logic inside the message handler, or use the in-memory dispatcher for
single-node deployments that need `subscribeFiltered`.

---

## Running the example

`RedisMessagingExample` in `jentic-examples` demonstrates both messaging patterns with two
real `JenticRuntime` agents:

- **`OrderAgent`** (CYCLIC, 4 s) — publishes orders to `orders.created` and logs fulfillment ACKs received via `onDirectMessage`.
- **`FulfillmentAgent`** (`@JenticMessageHandler("orders.created")`) — processes each order and replies directly to the sender via `sendTo(msg.reply(...))`.

Requires a running Valkey or Redis server on `localhost:6379` (or set `REDIS_URI`):

```bash
docker run -d -p 6379:6379 valkey/valkey:8

mvn exec:java -pl jentic-examples \
    -Dexec.mainClass="dev.jentic.examples.redis.RedisMessagingExample"

# custom Redis URI
REDIS_URI=redis://my-host:6379 mvn exec:java -pl jentic-examples \
    -Dexec.mainClass="dev.jentic.examples.redis.RedisMessagingExample"
```

Expected output (abridged):

```
=== Redis Agent Messaging Example ===
Connecting to redis://localhost:6379
Runtime started — 2 agent(s) running
[OrderAgent] Publishing order #1
[FulfillmentAgent] Processing order: {"orderId":"ORD-1","amount":99.95} (seq=1)
[OrderAgent] Fulfillment ACK — correlationId=... content=fulfillment queued by Fulfillment Agent
[OrderAgent] Publishing order #2
...
Stopping runtime...
[OrderAgent] Stopped after 5 orders published
[FulfillmentAgent] Stopped after 5 orders processed
=== Example completed ===
```

---

## Component overview

| Class | Interfaces | Responsibility |
|-------|-----------|---------------|
| `RedisMessagingFactory` | `AutoCloseable` | Builder; creates and wires all components; manages shared Lettuce connection. Entry point via `messageDispatcher()` or `messageDispatcher(Supplier<AgentResolver>)` |
| `RedisMessageDispatcher` | `MessageDispatcher` | **Primary entry point.** Composes topic pub/sub and point-to-point into the single interface `JenticRuntime` expects. Local fast-path for same-JVM agents; remote path via `AgentResolver` + `RedisMessageTransport` |
| `RedisTopicPublisher` | `TopicPublisher`, `TopicSubscriber` | Publishes to topic streams; creates per-subscription consumer groups |
| `RedisMessageTransport` | `MessageTransport` | Sends to node streams; subscribes with a node-scoped consumer group |
| `RedisStreamClient` | — (internal) | `XADD`, `ensureConsumerGroup`, creates consumer connections |
| `ConsumerLoop` | — (internal) | Blocking `XREADGROUP` loop on a virtual thread; DLQ after `maxDeliveryAttempts` |
| `RedisMessagingConfig` | — (record) | All configuration parameters; key/group name generation |
| `MessageCodec` | — (internal) | `Message` ↔ Redis stream field map (Jackson) |

Package: `dev.jentic.adapters.messaging.redis`

---

## See also

- [Messaging guide](../messaging.md) — complete messaging API reference
- [Spring Boot starter](../spring-boot-starter.md) — auto-configuration reference
- ADR-021 — Streams vs Pub/Sub, topology, delivery guarantees (`docs/adr/`)
- ADR-018 — Optional adapter dependencies pattern, why Lettuce is `optional=true` (`docs/adr/`)
- ADR-020 — `TopicPublisher`, `TopicSubscriber`, `MessageTransport` interface contracts (`docs/adr/`)
