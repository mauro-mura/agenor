# Agent State Persistence Guide

This guide covers Jentic's **agent state persistence** system: how to save and restore an agent's
internal business fields across JVM restarts.

> **This is not the memory system.** Key-value memory (`MemoryStore`, `rememberLong`, etc.) is a
> separate subsystem documented in [memory.md](memory.md). The two systems are independent and do
> not share storage.

The persistence subsystem spans two modules:
- **`jentic-core`** (`dev.jentic.core.persistence`) — interfaces and records
- **`jentic-runtime`** (`dev.jentic.runtime.persistence`) — implementations

---

## Package Overview

```
jentic-core / dev.jentic.core.persistence
├── Stateful.java             # Mixin interface: agents with persistent state
├── AgentState.java           # Serializable state snapshot (builder)
├── PersistenceService.java   # Save/load interface
└── PersistenceStrategy.java  # When to auto-save (enum)

jentic-runtime / dev.jentic.runtime.persistence
├── FilePersistenceService.java  # JSON file-based PersistenceService
└── PersistenceManager.java      # Auto-save orchestrator, wired by JenticRuntime
```

---

## Core concept

An agent opts into persistence by implementing `Stateful`. The agent then decides **which fields**
to save (`captureState`) and how to restore them (`restoreState`). The runtime calls these methods
automatically based on `@JenticPersistenceConfig`.

```
Agent fields ──captureState()──▶ AgentState ──FilePersistenceService──▶ <agentId>.json
Agent fields ◀──restoreState()── AgentState ◀──FilePersistenceService── <agentId>.json
```

---

## Stateful interface

```java
@JenticAgent("order-processor")
@JenticPersistenceConfig(
    strategy = PersistenceStrategy.ON_STOP,
    autoSnapshot = true,
    snapshotInterval = "1h",
    maxSnapshots = 24
)
public class OrderProcessorAgent extends BaseAgent implements Stateful {

    private int ordersProcessed = 0;
    private String currentOrderId;

    @Override
    public AgentState captureState() {
        return AgentState.builder(getAgentId())
            .agentName(getAgentName())
            .agentType("processor")
            .status(isRunning() ? AgentStatus.RUNNING : AgentStatus.STOPPED)
            .data("ordersProcessed", ordersProcessed)
            .data("currentOrderId", currentOrderId)
            .version(getStateVersion() + 1)
            .build();
    }

    @Override
    public void restoreState(AgentState state) {
        Integer saved = state.getData("ordersProcessed", Integer.class);
        ordersProcessed = saved != null ? saved : 0;
        currentOrderId  = state.getData("currentOrderId", String.class);
    }
}
```

Guidelines for `captureState()`:
- Include all mutable fields that must survive a restart.
- Produce a point-in-time snapshot; avoid holding locks across the call.
- Increment or preserve `version()` for optimistic locking.

Guidelines for `restoreState()`:
- Handle missing keys gracefully with defaults.
- Do not start behaviors or connect to external systems here; use `onStart()` for that.
- This method may be called before `start()`.

---

## @JenticPersist

`@JenticPersist` is a **field- and method-level** annotation that lets the runtime discover which
members to include in the persisted state automatically, without requiring a manual
`captureState()`/`restoreState()` implementation.

### Field usage

```java
@JenticAgent("order-processor")
public class OrderProcessorAgent extends BaseAgent implements Stateful {

    @JenticPersist
    private int ordersProcessed = 0;

    @JenticPersist("customer_id")       // explicit key — stable across field renames
    private String customerId;

    @JenticPersist(required = true)     // restoration fails if key is absent in the saved document
    private String sessionToken;

    @JenticPersist(encrypted = true)    // value is encrypted at rest
    private String apiKey;
}
```

### Method usage

When placed on a getter, the framework calls the getter on save and the matching setter on restore.

```java
@JenticPersist("order_state")
public OrderState getOrderState() { return orderState; }
```

### Attribute reference

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String` | `""` (member name) | Key used in the persisted document. Set explicitly to keep the schema stable across renames. |
| `required` | `boolean` | `false` | When `true`, restoration throws an exception if the key is missing. Use for fields that are essential to resume correct operation. |
| `encrypted` | `boolean` | `false` | When `true`, the value is encrypted before writing and decrypted transparently on restore. Suitable for secrets (API keys, tokens). |

### @JenticPersist vs manual captureState / restoreState

| | `@JenticPersist` | Manual `captureState` / `restoreState` |
|---|---|---|
| Boilerplate | Minimal — annotate fields | Explicit builder calls for each field |
| Control | Framework-driven | Full control over snapshot shape |
| Schema stability | Use `value` to pin keys | Manage keys manually |
| Conditional logic | Not supported | Any logic in `restoreState` |
| Encrypted fields | `encrypted = true` | Manual |

Use `@JenticPersist` for straightforward persistence of individual fields. Implement
`captureState` / `restoreState` manually when you need custom logic, derived values, or
transformations during save/restore.

---

## @JenticPersistenceConfig

Sets the automatic-save policy at class level. When absent, the strategy defaults to `MANUAL`.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `strategy` | `MANUAL` | When to save automatically |
| `interval` | `"60s"` | Used by `PERIODIC` and `DEBOUNCED` |
| `autoSnapshot` | `false` | Enable periodic snapshots |
| `snapshotInterval` | `"1h"` | How often to create a snapshot |
| `maxSnapshots` | `10` | Max snapshots to retain (oldest purged) |

### PersistenceStrategy values

| Value | Description |
|-------|-------------|
| `MANUAL` | Agent must call `persistState()` explicitly |
| `IMMEDIATE` | Save on every state change |
| `PERIODIC` | Save at fixed intervals (`interval` attribute) |
| `ON_STOP` | Save when agent stops |
| `DEBOUNCED` | Save after changes with a debounce window |
| `SNAPSHOT` | Create periodic snapshots |

---

## AgentState fields

| Field | Type | Notes |
|-------|------|-------|
| `agentId` | `String` | Required |
| `agentName` | `String?` | Display name |
| `agentType` | `String` | Defaults to `"unknown"` |
| `status` | `AgentStatus` | Defaults to `UNKNOWN` |
| `data` | `Map<String,Object>` | Business state; any JSON-serializable value |
| `metadata` | `Map<String,String>` | System/config state; String values only |
| `version` | `long` | Optimistic locking counter |
| `savedAt` | `Instant` | Auto-set when saving |

```java
// Type-safe data access
int count  = state.getData("ordersProcessed", Integer.class);
String id  = state.getData("currentOrderId",  String.class);
```

---

## FilePersistenceService

The default `PersistenceService` implementation. Writes agent state as JSON (Jackson) to
`<dataDirectory>/<agentId>.json` using an atomic `REPLACE_EXISTING` move. Snapshots go to
`<dataDirectory>/snapshots/<agentId>/<snapshotId>.json`.

```java
// Default directory: data/persistence
FilePersistenceService service = new FilePersistenceService();

// Custom directory
FilePersistenceService service = new FilePersistenceService(Path.of("var/agent-states"));
```

### Available operations

```java
service.saveState(agentId, state).join();
Optional<AgentState> loaded = service.loadState(agentId).join();
boolean exists = service.existsState(agentId).join();
service.deleteState(agentId).join();                          // also removes all snapshots

String snapshotId = service.createSnapshot(agentId, null).join();           // auto-generated ID
String snapshotId = service.createSnapshot(agentId, "before-migration").join();
Optional<AgentState> snap = service.restoreSnapshot(agentId, snapshotId).join();
List<String> snapshots = service.listSnapshots(agentId).join();
```

---

## PersistenceManager

`PersistenceManager` wires `FilePersistenceService` to the runtime. It automatically registers
agents that implement `Stateful`, schedules periodic saves or snapshots, and flushes all states
on shutdown.

### Integration with JenticRuntime (recommended)

```java
var persistence = new FilePersistenceService(Path.of("data/agents"));
var manager     = new PersistenceManager(persistence);

JenticRuntime runtime = JenticRuntime.builder()
    .scanPackage("com.example.agents")
    .service(PersistenceManager.class, manager)
    .build();

runtime.start();
// PersistenceManager.registerAgent() called automatically for every Stateful agent
```

### Manual usage

```java
PersistenceManager manager = new PersistenceManager(new FilePersistenceService());
manager.start().join();
manager.registerAgent(myAgent);
manager.saveAgent(myAgent).join();   // manual save
manager.stop().join();               // cancels scheduled tasks, saves all agents
```

---

## Decision Guide

| Scenario | Recommended approach |
|----------|---------------------|
| Agent business state (counters, queues, current IDs) | `Stateful` + `FilePersistenceService` |
| Save on restart/stop only | `@JenticPersistenceConfig(strategy=ON_STOP)` |
| Scheduled auto-save | `@JenticPersistenceConfig(strategy=PERIODIC)` |
| Point-in-time rollback | `FilePersistenceService.createSnapshot` + `restoreSnapshot` |
| Searchable/sharable knowledge base | Use `MemoryStore` instead — see [memory.md](memory.md) |

---

## See Also

- [Memory Guide](memory.md) — `MemoryStore`, `rememberLong`, `InMemoryStore`
- [Agent Development Guide](agent-development.md) — `@JenticPersist`, `@JenticPersistenceConfig` annotations
- [Architecture Guide](architecture.md) — module overview
