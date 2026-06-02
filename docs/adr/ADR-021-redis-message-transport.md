# ADR-021: Redis MessageTransport — Pub/Sub vs Streams

**Status**: Accepted  
**Date**: 2026-05-10  
**Authors**: Project Team  
**References**: ADR-001 (Virtual Threads), ADR-002 (Interface-First Architecture),
ADR-004 (Progressive Complexity), ADR-018 (Optional Adapter Dependencies Pattern),
ADR-020 (Core API Refactor for Distributed Backends)

---

## Context

With the Core API refactor (ADR-020) complete, the Redis adapter can implement a clean
capability subset without reinventing routing or inspecting message shapes. The adapter needs
to deliver two messaging primitives:

1. **Topic pub/sub** — `TopicPublisher` + `TopicSubscriber` — one-to-many fan-out per topic
2. **Point-to-point** — `MessageTransport` — direct delivery to a resolved `AgentEndpoint`

Two Redis mechanisms are candidates for both primitives: **Pub/Sub** and **Streams**. The
choice has significant consequences for delivery guarantees, back-pressure, and operational
complexity.

### Candidate A — Redis Pub/Sub

Redis Pub/Sub delivers messages to all currently-connected subscribers on a channel. It is
stateless: messages published when no subscriber is connected are lost, there is no persistence
or replay, and back-pressure is not supported. Consumer count is transparent to the publisher.

| Property | Pub/Sub |
|----------|---------|
| Delivery guarantee | At-most-once |
| Message durability | None (in-memory only) |
| Replay / catch-up | No |
| Back-pressure | No |
| Consumer groups | No |
| Message acknowledgement | No |
| Persistence on restart | No |

### Candidate B — Redis Streams

Redis Streams (`XADD`, `XREAD`, `XREADGROUP`, `XACK`) are an append-only log with consumer
groups. Unread messages are retained in the stream until explicitly trimmed. Consumers in
the same group receive each message exactly once; multiple groups on the same stream each
receive all messages (fan-out for topics). This maps cleanly to Agenor's semantics: topic
fan-out via multiple consumer groups (one per subscribing agent), point-to-point delivery via
a dedicated per-node stream with a single consumer group.

| Property | Streams |
|----------|---------|
| Delivery guarantee | At-least-once (with `XACK`) |
| Message durability | Persisted in the stream until trimmed |
| Replay / catch-up | Yes, from any offset |
| Back-pressure | Yes, via stream `MAXLEN` and consumer lag monitoring |
| Consumer groups | Yes |
| Message acknowledgement | Yes (`XACK`) |
| Persistence on restart | Yes (AOF / RDB) |

### Licence context — Redis vs Valkey

Redis OSS changed licence to SSPL from Redis 7.4 onwards. **Valkey** (Linux Foundation,
BSD-3-Clause, forked from Redis 7.2) is the primary target for this adapter. All commands
used by the adapter belong to the shared RESP (Redis Serialisation Protocol) subset that both
projects support identically; no vendor-specific extension is used. The adapter is tested
against Valkey 8.x, Redis OSS 7.2, and Redis commercial. Lettuce is the client library and
connects to any RESP-compatible server.

---

## Decision

**Use Redis Streams as the primary mechanism for all message delivery.**

Pub/Sub is unsuitable as a general messaging substrate for agent systems: a momentarily
disconnected agent losing messages is not acceptable for at-least-once scenarios (HITL
approvals, task delegation, request/response). Streams provide the right semantics at the
cost of slightly higher operational complexity (stream trimming, consumer group management),
which is bounded and predictable.

Pub/Sub is retained exclusively as the delivery mechanism for **directory heartbeat
notifications** (`AgentPresence.heartbeat`) when a future presence backend chooses it — a
best-effort, loss-tolerant channel that does not need replay. It is not used for
`TopicPublisher`, `TopicSubscriber`, or `MessageTransport`.

### Stream topology

| Stream | Key pattern | Consumer group | Usage |
|--------|------------|----------------|-------|
| Topic stream | `agenor:topic:<topicName>` | `agenor:cg:<agentId>` (one per subscriber) | Fan-out pub/sub |
| Node stream | `agenor:node:<nodeId>` | `agenor:cg:node` (single group per node) | Point-to-point delivery |
| Dead-letter | `<sourceStreamKey>:dlq` | — (written with `XADD`) | Messages that exceeded `maxDeliveryAttempts` |

`nodeId` is a UUID generated once at `AgenorRuntime` startup and stored in each
`AgentEndpoint` as `transportProps.get("nodeId")`. The resolver translates `agentId →
AgentEndpoint → nodeId → agenor:node:<nodeId>`.

### Delivery flow — topic publish

```
producer
  → XADD agenor:topic:<topic> * [fields]
  → each subscriber's consumer group reads via XREADGROUP GROUP agenor:cg:<agentId> ...
  → handler invoked → XACK on success
```

### Delivery flow — point-to-point

```
sender
  → RedisMessageDispatcher.sendTo(msg)
  → AgentResolver.resolveEndpoint(recipientId)  →  AgentEndpoint{nodeId, "redis", props}
  → RedisMessageTransport.send(TransportEndpoint(nodeId), msg)
  → XADD agenor:node:<nodeId> * [fields]
  → recipient node: consumer loop reads via XREADGROUP GROUP agenor:cg:node ...
  → RedisMessageDispatcher routes locally: agentId → handler (internal map)
  → XACK on handler success
```

`subscribeRecipient(agentId, handler)` registers the handler in an internal
`agentId → MessageHandler` map and ensures the node-stream consumer loop is running.
When a message arrives on `agenor:node:<nodeId>`, the dispatcher reads `receiverId`
from the envelope and invokes the matching handler. No `InMemoryMessageDispatcher`
is involved in the point-to-point path.

### Message encoding

Messages are serialised as JSON (Jackson, already a `agenor-core` dependency) and stored as
a single `payload` field in the stream entry. The envelope fields (`id`, `topic`,
`receiverId`, `senderId`, `correlationId`, `timestamp`) are stored as separate stream fields
for server-side inspection without deserialising the payload.

### Consumer loops

All `XREADGROUP` / `XREAD` blocking loops run on **virtual threads** (ADR-001). Each
subscriber spawns one virtual thread; the thread blocks on `XREADGROUP BLOCK <timeout>`.
On handler failure the message is not `XACK`'d and will be redelivered after the
`pending-entries-timeout` (configurable, default 30 s). Three consecutive delivery failures
park the message in the dead-letter stream `<sourceStreamKey>:dlq` (e.g. `agenor:topic:orders.created:dlq`).

### Stream trimming

Streams are trimmed with `MAXLEN ~ <N>` (approximate, O(1)) on every `XADD`. The default
cap is 100 000 entries per stream, configurable via `RedisMessagingConfig.maxStreamLength`.

### FilterableSubscriber — not implemented

`FilterableSubscriber` is intentionally omitted. Redis Streams do not support server-side
predicate evaluation; implementing it via client-side read-then-filter would defeat the
purpose of the capability split in ADR-020. Code that depends on `FilterableSubscriber` must
either use the in-memory dispatcher or apply filtering in the message handler.

---

## Implementation plan

### Classes

| Class | Interface(s) | Location |
|-------|--------------|----------|
| `RedisMessageDispatcher` | `MessageDispatcher` | `agenor-adapters` |
| `RedisTopicPublisher` | `TopicPublisher`, `TopicSubscriber` | `agenor-adapters` |
| `RedisMessageTransport` | `MessageTransport` | `agenor-adapters` |
| `RedisMessagingConfig` | — (record) | `agenor-adapters` |
| `RedisMessagingFactory` | — (builder, exposes `messageDispatcher()`) | `agenor-adapters` |
| `RedisStreamClient` | — (internal helper) | `agenor-adapters` |

`RedisMessageDispatcher` is the primary entry point for application code and the Spring Boot
starter. It composes `RedisTopicPublisher` (topic pub/sub), `RedisMessageTransport`
(inter-node delivery), and `AgentResolver` (agentId → endpoint translation) into the single
`MessageDispatcher` interface expected by `AgenorRuntime.Builder.messageDispatcher(...)`.
`RedisMessagingFactory.messageDispatcher()` constructs and returns it; the individual
`topicPublisher()` and `messageTransport()` accessors remain available for advanced use.

Package root: `dev.agenor.adapters.messaging.redis`

### Dependency — Lettuce (`optional=true`, per ADR-018)

```xml
<!-- agenor-adapters/pom.xml -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <optional>true</optional>
</dependency>
```

Consumers that want Redis messaging declare `lettuce-core` explicitly in their own POM.
Consumers that do not declare it see only the in-memory dispatcher at runtime.

### Spring Boot auto-configuration

```java
@ConditionalOnClass(io.lettuce.core.RedisClient.class)
@ConditionalOnProperty(prefix = "agenor.messaging", name = "provider", havingValue = "redis")
class RedisMessagingConfiguration { ... }
```

The auto-configuration creates a `RedisMessagingFactory` bean, calls
`factory.messageDispatcher()` to obtain a `RedisMessageDispatcher`, and exposes it as the
`MessageDispatcher` bean. The `agenorRuntime` bean accepts this via `ObjectProvider<MessageDispatcher>`
and injects it into `AgenorRuntime.Builder.messageDispatcher(...)`. No changes to
`InMemoryMessageDispatcher` or the runtime internals are required.

YAML keys:

```yaml
agenor:
  messaging:
    provider: inmemory        # "inmemory" (default) | "redis"
    redis:
      uri: redis://localhost:6379
      consumer-group-prefix: agenor
      read-block-timeout-ms: 2000
      max-stream-length: 100000
      pending-entries-timeout-ms: 30000
      max-delivery-attempts: 3
```

### Observability

OTel spans emitted via `AgenorTelemetry` (ADR-019) for every:

- `TopicPublisher.publish` → span `message.publish` (attr: `message.topic`, `message.id`)
- `MessageTransport.send` → span `transport.send` (attr: `transport.type=redis`, `transport.endpoint`)
- Consumer handler invocation → span `message.receive` linked to the publish span via `correlationId`

### Testing

| Test type | Target | Tool |
|-----------|--------|------|
| Unit | `RedisTopicPublisher`, `RedisMessageTransport` | Lettuce mocks (Lettuce in `test` scope) |
| Integration | Full at-least-once delivery, consumer group rebalancing, broker restart | Testcontainers Valkey 8.x |
| Contract | Reuse `TopicPublisher`, `TopicSubscriber`, `MessageTransport` contract suites from ADR-020 | — |
| Classpath | Consumer without `lettuce-core` builds and uses in-memory dispatcher | Maven Invoker plugin |
| RESP compat | Same integration test suite against Redis OSS 7.2 and Redis 7.4 commercial | Testcontainers |

---

## Consequences

**Positive:**
- At-least-once delivery and replay satisfy agent messaging requirements without extra
  infrastructure beyond a RESP-compatible server.
- Stream-per-node topology scales with node count, not agent count — a 1 000-agent node
  still uses one inbound stream.
- Consumer groups provide natural load-balancing when multiple instances of the same node
  type are deployed.
- Virtual-thread-based consumer loops require no thread pool sizing.
- RESP compatibility with Valkey avoids licence dependency on Redis SSPL.

**Negative / trade-offs:**
- At-least-once semantics require idempotent message handlers; this is documented as a
  consumer contract, not enforced by the framework.
- Stream trimming (`MAXLEN`) requires tuning for high-throughput topics; the default is
  conservative.
- Dead-letter stream adds operational surface (monitoring, replay tooling) that did not
  exist with in-memory messaging; addressed in `docs/adapters/redis.md`.

---

## Alternatives Considered

**Use Pub/Sub for topics, Streams for point-to-point.**  
Rejected: mixing two delivery mechanisms for what is logically one concept (message delivery)
complicates the implementation and gives topics weaker guarantees than direct messages.
Operationally confusing — a topic subscriber would experience message loss that a direct
receiver would not.

**Use Streams for topics, Pub/Sub for point-to-point.**  
Same asymmetry problem as above, inverted. Rejected for the same reason.

**Kafka instead of Redis/Valkey.**  
Kafka offers stronger ordering and retention guarantees but introduces ZooKeeper/KRaft,
a different protocol (not RESP), and a substantially larger operational footprint. Deferred
to the Enterprise tier (`agenor-enterprise-distributed`). The capability interfaces from
ADR-020 ensure a Kafka adapter can slot in without code changes in the rest of the framework.

**Use Redis Lists (`LPUSH`/`BRPOP`) instead of Streams.**  
Lists lack consumer groups, so fan-out (topics) requires N copies of every message — one
per subscriber. Rejected: write amplification is unbounded as subscriber count grows.

---

## Related ADRs

- ADR-001: Virtual threads — blocking `XREADGROUP` loops run on virtual threads
- ADR-002: Interface-first architecture — adapter implements only the capability subset it supports
- ADR-004: Progressive complexity — in-memory dispatcher remains the default; Redis is opt-in
- ADR-018: Optional adapter dependencies pattern — Lettuce declared `optional=true`
- ADR-020: Core API refactor — `MessageTransport`, `TopicPublisher`, `TopicSubscriber` are the
  interfaces this adapter implements; `FilterableSubscriber` is deliberately omitted
