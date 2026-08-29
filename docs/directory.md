# Agent Directory

This document describes Agenor's agent directory API introduced in **0.20.0**. It replaces the monolithic `AgentDirectory` with four focused capability interfaces designed to work with both in-memory and distributed backends.

## Overview

The directory API is split into four interfaces, each representing a single responsibility:

| Interface | Capability | Key Methods |
|-----------|-----------|-------------|
| `AgentRegistry` | Register / unregister agents | `register`, `unregister`, `updateStatus` |
| `AgentResolver` | Translate agent ID → transport endpoint | `resolveEndpoint` |
| `AgentDiscovery` | Query and search agents | `findById`, `findByCapability`, `findByType`, `findAgents` |
| `AgentPresence` | Heartbeat and status | `heartbeat`, `getStatus` |

`dev.agenor.core.directory.AgentDirectory` is the composite interface:

```java
interface AgentDirectory extends AgentRegistry, AgentResolver, AgentDiscovery, AgentPresence {}
```

## Getting the Directory

### Via AgenorRuntime

```java
AgenorRuntime runtime = AgenorRuntime.builder().build();
runtime.

start().

join();

dev.agenor.core.directory.AgentDirectory directory = runtime.getAgentDirectory();
```

### Via Spring Boot

All capability interfaces are exposed as Spring beans:

```java
@Service
public class MyService {
    private final AgentDiscovery discovery;
    private final AgentRegistry registry;

    public MyService(AgentDiscovery discovery, AgentRegistry registry) {
        this.discovery = discovery;
        this.registry = registry;
    }
}
```

Use the most specific interface you need rather than the full `AgentDirectory`.

### Standalone

```java
InMemoryAgentDirectory directory = new InMemoryAgentDirectory("node-1");
```

## Registering Agents

```java
AgentDescriptor descriptor = AgentDescriptor.builder("my-agent")
    .agentName("My Agent")
    .agentType("worker")
    .status(AgentStatus.RUNNING)
    .capabilities(Set.of("data-processing", "reporting"))
    .build();

directory.register(descriptor).join();
```

If no `AgentEndpoint` is set on the descriptor, `InMemoryAgentDirectory` assigns a `local` endpoint automatically.

## Discovering Agents

### By ID

```java
Optional<AgentDescriptor> agent = directory.findById("my-agent").join();
agent.ifPresent(d -> log.info("Found: {} [{}]", d.agentName(), d.status()));
```

### By Capability

```java
List<AgentDescriptor> processors = directory.findByCapability("data-processing").join();
```

### By Type

```java
List<AgentDescriptor> workers = directory.findByType("worker").join();
```

### Paginated Query

Use `findAgents(AgentQuery, PageRequest)` for production workloads with many agents:

```java
AgentQuery query = AgentQuery.builder()
    .agentType("worker")
    .status(AgentStatus.RUNNING)
    .requiredCapabilities(Set.of("data-processing"))
    .build();

Page<AgentDescriptor> page = directory
    .findAgents(query, PageRequest.of(0, 20))
    .join();

log.info("Found {} of {} matching agents",
    page.content().size(), page.totalElements());

// Next page:
Page<AgentDescriptor> page2 = directory
    .findAgents(query, PageRequest.of(1, 20))
    .join();
```

`AgentQuery.all()` matches every registered agent:

```java
Page<AgentDescriptor> all = directory
    .findAgents(AgentQuery.all(), PageRequest.first(100))
    .join();
```

## Endpoint Resolution

The `AgentResolver` interface converts an agent ID to a `AgentEndpoint`, which the dispatcher uses for point-to-point routing:

```java
Optional<AgentEndpoint> endpoint = directory.resolveEndpoint("my-agent").join();
endpoint.ifPresent(e -> log.info("Transport: {}, Node: {}",
    e.transportType(), e.nodeId()));
```

For `InMemoryAgentDirectory`, all endpoints have `transportType="local"`. Future backends (Redis, gRPC) will return different transport types and the `InMemoryMessageDispatcher` will delegate to the appropriate transport.

## Presence and Heartbeats

```java
// Report that the agent is still alive — refreshes lastSeen, leaves status alone
directory.heartbeat("my-agent").join();

// Query current status
AgentStatus status = directory.getStatus("my-agent").join();
```

**A heartbeat is liveness, not progress.** It says the agent is still there; it does not
say the agent is working. An agent stuck in `STARTING` keeps reporting `STARTING` however
many heartbeats it sends. Use `updateStatus` to change status.

Before 0.26.0 `heartbeat` promoted the agent to `RUNNING`, which meant a single heartbeat
could report an agent as running before it had finished starting. If you relied on that,
call `updateStatus(agentId, RUNNING)` explicitly (ADR-028).

**Something has to send the heartbeats.** Nothing does by default. Set a heartbeat interval
and the runtime starts a driver that beats for every agent it is running:

```java
AgenorRuntime.builder()
    .heartbeatInterval(Duration.ofSeconds(30))   // off unless set
    .build();
```

```yaml
agenor:
  directory:
    heartbeat-interval: 30s
```

The driver uses a single daemon thread, separate from the behavior scheduler, and never
blocks on the backend.

**Staleness is backend-specific.** `getStatus` answers `UNKNOWN` for an agent not seen
within the backend's staleness window. `InMemoryAgentDirectory` has no window — a status
never expires — so on the default runtime this makes no difference. Backends that do expire,
such as [JDBC presence](adapters/jdbc-directory.md#presence-and-heartbeats), need the driver
above running, or every agent reads `UNKNOWN` one window after start-up.

## AgentDescriptor

`AgentDescriptor` is an immutable record describing a registered agent. Use the builder:

```java
AgentDescriptor d = AgentDescriptor.builder("agent-id")
    .agentName("Human-readable name")
    .agentType("coordinator")
    .status(AgentStatus.RUNNING)
    .capabilities(Set.of("routing", "orchestration"))
    .metadata(Map.of("version", "1.0"))
    .endpoint(AgentEndpoint.local("node-abc"))
    .build();
```

Fields:

| Field | Type | Description |
|-------|------|-------------|
| `agentId` | `String` | Unique identifier |
| `agentName` | `String` | Human-readable name |
| `agentType` | `String` | Logical role (e.g., "worker", "coordinator") |
| `status` | `AgentStatus` | `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `ERROR`, `UNKNOWN` |
| `capabilities` | `Set<String>` | Named capabilities for discovery |
| `metadata` | `Map<String,String>` | Arbitrary key-value metadata |
| `endpoint` | `AgentEndpoint` | Transport routing info (since 0.20.0) |
| `registeredAt` | `Instant` | Registration timestamp |
| `lastSeen` | `Instant` | Last heartbeat or status update |

## Observability

`resolveEndpoint` creates an OpenTelemetry span named `directory.resolve`:

| Attribute | Value |
|-----------|-------|
| `agent.id` | the queried agent ID |
| `endpoint.type` | resolved transport type, or `"not-found"` |

## Migration from AgentDirectory (0.19.x → 0.28.0)

`dev.agenor.core.AgentDirectory` **was removed in 0.28.0**, having been deprecated at 0.22.0. The
replacement carries the same simple name in a different package, so for most code the migration is
the import line and nothing else.

| Old API | New API |
|---------|---------|
| `dev.agenor.core.AgentDirectory` | `dev.agenor.core.directory.AgentDirectory` |
| `directory.listAll()` | `directory.findAgents(AgentQuery.all(), PageRequest.first(n))` |
| `directory.findAgents(query)` | `directory.findAgents(query, PageRequest.first(n))` |

If you reach the directory through `AgenorRuntime.getAgentDirectory()`, it now returns the new type
and there is nothing to change.

One behavioural note. The removed interface supplied `heartbeat` as a `default` that read the
descriptor and wrote its status back, which is not atomic — its own Javadoc said so. The
implementations do the touch under a single compare-and-set, so removing the facade removed the
race rather than leaving it to you (ADR-028).

## Custom Backends

To plug in a custom directory backend (Redis, JDBC, etc.):

1. Implement `dev.agenor.core.directory.AgentDirectory` (or individual capability interfaces).
2. Register as a Spring bean or pass to `AgenorRuntime.Builder`:

```java
// Spring Boot
@Bean
public AgentRegistry redisAgentRegistry(RedisTemplate<String, AgentDescriptor> template) {
    return new RedisAgentRegistry(template);
}

// Programmatic
AgenorRuntime runtime = AgenorRuntime.builder()
    .agentRegistry(new RedisAgentRegistry(template))
    .agentResolver(new RedisAgentRegistry(template))
    .agentDiscovery(new RedisAgentRegistry(template))
    .agentPresence(new RedisAgentRegistry(template))
    .build();
```

The runtime will use your implementation instead of the default `InMemoryAgentDirectory`.
