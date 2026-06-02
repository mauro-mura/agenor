# ADR-020: Core API Refactor for Distributed Backends

**Status**: Accepted  
**Date**: 2026-04-26  
**Last Modified**: 2026-05-18  
**Authors**: Jentic Team  
**References**: ADR-002 (Interface-First Architecture), ADR-004 (Progressive Complexity),
ADR-018 (Optional Adapter Dependencies Pattern)

---

## Context

The `MessageService` and `AgentDirectory` interfaces were designed for single-JVM, in-memory
operation. As Jentic moves toward distributed backends (Redis, Kafka, JDBC, Consul), these
interfaces expose several assumptions that cannot be satisfied outside a single JVM without
unacceptable semantic compromises:

**`MessageService` problems:**
- `send(Message)` is polymorphic on message shape — it inspects `receiverId` vs `topic` at
  dispatch time. A remote backend cannot determine the correct wire operation without re-inspecting
  the payload, coupling transport semantics to message structure.
- `subscribe(Predicate<Message>, handler)` embeds a Java lambda in the contract. No remote backend
  can push this predicate server-side, forcing client-side read-then-filter that wastes bandwidth
  at scale.

**`AgentDirectory` problems:**
- No concept of *where* an agent can be reached. The descriptor carries capabilities and metadata
  but no transport endpoint, so point-to-point routing in a distributed system has no resolution
  function.
- `AgentQuery.customFilter(Predicate<AgentDescriptor>)` has the same leaky abstraction: a Java
  closure cannot be serialised to a remote store.
- `listAll()` is a `SELECT *` equivalent; it does not scale beyond in-memory.
- Discovery (capability lookup), presence (heartbeat/liveness), and endpoint resolution (routing)
  are mixed in a single interface despite having orthogonal access patterns, update frequencies,
  and backend fitness profiles — forcing one backend to compromise on all three.

---

## Decision

Decompose `MessageService` and `AgentDirectory` into **capability-sized interfaces**, introduce an
explicit `AgentEndpoint` concept in the descriptor, and separate logical routing from physical
transport. The in-memory implementations are updated in place; the old composite interfaces are
preserved as deprecated facades for one release cycle.

### `AgentEndpoint` in the descriptor

```java
public record AgentEndpoint(
    String nodeId,                       // UUID of the owning JVM
    String transportType,                // "local" | "redis" | "kafka" | "a2a" | ...
    Map<String, String> transportProps   // transport-specific (stream name, URL, ...)
) {}
```

Added to `AgentDescriptor` as a nullable field. `InMemoryAgentDirectory` populates it with
`transportType="local"` and a runtime-generated `nodeId`.

### `AgentDirectory` split

Four capability interfaces in `dev.agenor.core.directory`:

| Interface | Responsibility |
|-----------|---------------|
| `AgentRegistry` | `register`, `unregister`, `updateStatus` |
| `AgentResolver` | `resolveEndpoint(agentId)` — hot path, called per message |
| `AgentDiscovery` | `findById`, `findByCapability`, `findByType`, `findAgents(AgentQuery, PageRequest)` |
| `AgentPresence` | `heartbeat(agentId)`, `getStatus(agentId)` |

The composite facade `AgentDirectory extends AgentRegistry, AgentResolver, AgentDiscovery, AgentPresence`
retains the original name and is implemented by `InMemoryAgentDirectory`. Backends declare which
capabilities they implement; the runtime assembles mixed backends via `AgenorRuntime.Builder`.

`AgentQuery.customFilter(Predicate)` is removed immediately (see Migration below). `listAll()` is
deprecated with a default-method bridge.

### `MessageService` split

Six capability interfaces in `dev.agenor.core.messaging`:

| Interface | Responsibility |
|-----------|---------------|
| `TopicPublisher` | `publish(msg)` — routes on `msg.topic()` |
| `TopicSubscriber` | `subscribeTopic(topic, handler) → Subscription` |
| `DirectMessenger` | `sendTo(msg)` — routes on `msg.receiverId()` |
| `DirectReceiver` | `subscribeRecipient(localAgentId, handler) → Subscription` |
| `FilterableSubscriber` | `subscribeFiltered(Predicate<Message>, handler) → Subscription` |
| `MessageTransport` | `send(TransportEndpoint, msg)` + `subscribe(TransportEndpoint, handler)` — transport primitive |

The composite facade `MessageDispatcher extends TopicPublisher, TopicSubscriber, DirectMessenger, DirectReceiver`
is the primary interface for agents. `MessageService extends MessageDispatcher, FilterableSubscriber` is the
deprecated back-compat alias.

`MessageDispatcher.sendTo(msg)` reads the recipient from `msg.receiverId()`, passes it to
`AgentResolver.resolveEndpoint`, and dispatches via `MessageTransport`. For `local` transport,
the in-memory path short-circuits without going through a transport object. An
`IllegalArgumentException` is thrown immediately if `msg.receiverId()` is null or blank.

Similarly, `publish(msg)` reads the topic from `msg.topic()` and throws `IllegalArgumentException`
if it is null or blank. This removes the previous redundancy where callers had to repeat routing
information that the message already carried.

### Routing via `AgentResolver`

```
Agent → MessageDispatcher.sendTo(msg)           // msg.receiverId() = "inventory-agent"
     → AgentResolver.resolveEndpoint("inventory-agent") → AgentEndpoint
     → if "local": dispatch directly to subscriber map
     → else: MessageTransport.send(TransportEndpoint, msg)
```

An agent no longer needs to know whether the recipient is local, on another node, or exposed via
A2A — the resolver decides.

---

## Consequences

**Positive:**
- Every future backend implements only the capability subset it actually supports — no semantic
  compromise on the others.
- `FilterableSubscriber` is naturally absent from remote backends; code that depends on it fails
  at wiring time (startup), not at runtime with degraded throughput.
- `AgentEndpoint` on the descriptor enables direct routing without a second lookup.
- Pagination via `Page<T>` / `PageRequest` makes `AgentDiscovery` usable against large remote
  directories.
- Item 2 is the strategic prerequisite for Items 3 (Redis), 4 (JDBC directory), and 5 (persistent
  HITL): after 0.20.0 each new backend implements exactly the right slice.

**Negative / trade-offs:**
- One release of deprecated bridges increases API surface temporarily.
- `AgentQuery.customFilter` removal is a hard break (no bridge possible). Mitigated by the fact
  that no known consumer outside the in-memory implementation uses it.

---

## Migration

### Removed in 0.20.0 (no bridge)

| Removed | Reason | Replacement |
|---------|--------|-------------|
| `AgentQuery.customFilter(Predicate)` | Cannot be serialised to any remote store; a bridge would silently degrade | Use structured query criteria (`agentType`, `requiredCapabilities`, `status`) |

### Deprecated in 0.20.0, removed in 0.22.0

| Deprecated | Replacement |
|------------|-------------|
| `MessageService` interface | `MessageDispatcher` (or specific capability interface) |
| `MessageService.send(Message)` | `publish(msg)` or `sendTo(msg)` |
| `MessageService.subscribeToReceiver(id, h)` | `subscribeRecipient(id, h)` |
| `MessageService.subscribe(topic, h)` | `subscribeTopic(topic, h)` |
| `MessageService.subscribe(Predicate, h)` | `subscribeFiltered(Predicate, h)` (in-memory only) |
| `MessageService.unsubscribe(id)` | `Subscription.unsubscribe()` on the returned object |
| `MessageService.sendAndWait(msg, timeout)` | Remains on `MessageService` until 0.22.0 |
| `AgentDirectory.listAll()` | `findAgents(AgentQuery.all(), PageRequest.of(0, Integer.MAX_VALUE))` |
| `AgentDirectory.findAgents(AgentQuery)` (no PageRequest) | `findAgents(AgentQuery, PageRequest)` |
| `LocalAgentDirectory` class | `InMemoryAgentDirectory` |
| `InMemoryMessageService` class | `InMemoryMessageDispatcher` |

The Spring Boot starter continues to publish both the deprecated composite beans and the new
capability beans, so existing YAML configurations work unchanged through 0.21.0.

---

## Alternatives Considered

**Keep the monolithic interfaces and add optional methods via `default`.**  
Rejected: `default` methods on `MessageService.send` cannot correctly dispatch without
inspecting the message payload, perpetuating the leaky abstraction. Remote backends would
implement `send` as dead code.

**Introduce a router layer instead of splitting interfaces.**  
Rejected: adds indirection without removing the predicate problem. The router would still
need to materialise filtered subscriptions, which remote stores cannot support.

**Move to an event-bus abstraction (CloudEvents, MimeType-based).**  
Deferred to Enterprise tier. The OSS split is the smallest change that unblocks distributed
backends while keeping the API learnable.

---

## Related ADRs

- ADR-002: Interface-first architecture — the split follows the same principle
- ADR-004: Progressive complexity — in-memory default unchanged; remote backends are opt-in
- ADR-008: WebConsole — uses `MessageService`; migrated to `MessageDispatcher` in this item
- ADR-009: Agent dialogue protocol — uses `MessageService`; migrated to `DirectMessenger` + `DirectReceiver`
- ADR-018: Optional adapter dependencies pattern — Redis and JDBC adapters built on these interfaces

---

## Amendment — 2026-05-07: Redundant routing parameters removed from `publish` and `sendTo`

**Supersedes**: the original signatures in the `MessageService` split table above.

### Context

The initial split gave `TopicPublisher.publish(String topic, Message msg)` and
`DirectMessenger.sendTo(String recipientAgentId, Message msg)` explicit routing parameters.
In practice every call site set the same value on both the parameter and the matching message
field (`msg.topic()` / `msg.receiverId()`), forcing callers to repeat information the message
already carried. No call site ever passed a topic or recipient that differed from the message
field — the extra parameter provided no override capability and introduced a silent error class
(mismatched parameter vs. field silently routing to the wrong destination).

### Decision

Remove the redundant parameters (breaking change, requires a major API version bump):

```java
// Before (0.20.x)
TopicPublisher:  publish(String topic, Message msg)
DirectMessenger: sendTo(String recipientAgentId, Message msg)

// After (0.21.x+)
TopicPublisher:  publish(Message msg)          // reads msg.topic()
DirectMessenger: sendTo(Message msg)           // reads msg.receiverId()
```

Both methods throw `IllegalArgumentException` immediately if the relevant field is null or blank,
surfacing mis-configured messages at the call site rather than silently dropping them.

### Migration

```java
// Before
dispatcher.publish("orders.created", msg);
dispatcher.sendTo("inventory-agent", msg);

// After — if message already carries the field, just drop the parameter
dispatcher.publish(msg);
dispatcher.sendTo(msg);

// After — building a new message
dispatcher.publish(Message.builder().topic("orders.created").content(data).build());
dispatcher.sendTo(Message.builder().receiverId("inventory-agent").content(data).build());
```

The deprecated `MessageService` bridge (`publish(Message)`, `sendTo(Message)`) is aligned with
the new signatures; no additional migration is needed for code still on the legacy path.

### Consequences

- All ~55 call sites across `agenor-core`, `agenor-runtime`, `agenor-adapters`, and
  `agenor-examples` updated in the same commit.
- `CHANGELOG.md` `[Unreleased]` section documents this as a breaking change with migration guide.
- No behavioural change — routing was already done on message fields in the implementation.

---

## Amendment — 2026-05-18: 0.22.0 removals completed

All APIs listed in **Deprecated in 0.20.0, removed in 0.22.0** have been deleted from the
codebase: `MessageService`, `InMemoryMessageService`, `LocalAgentDirectory`,
`AgenorRuntime.Builder.agentDirectory()`, `AgentDescriptor` 8-arg constructor, and
`AgentQuery.customFilter`. `dev.agenor.core.AgentDirectory` is now deprecated at 0.22.0
(`@Deprecated(since="0.22.0", forRemoval=true)`) for removal in a future release.
