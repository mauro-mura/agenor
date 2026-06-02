# Memory Guide

This guide covers Jentic's **key-value memory system**: `MemoryStore`, `MemoryScope`, `MemoryEntry`,
the `BaseAgent` helper API, shared memory for multi-agent coordination, and the `InMemoryStore`
implementation.

> **Looking for agent state persistence?** (`Stateful`, `captureState`, `@JenticPersistenceConfig`,
> `FilePersistenceService`) — see the dedicated **[Agent State Persistence Guide](persistence.md)**.
> The two systems are independent; this guide covers key-value memory only.

The memory subsystem spans two modules:
- **`jentic-core`** (`dev.agenor.core.memory`) — interfaces and records
- **`jentic-runtime`** (`dev.agenor.runtime.memory`) — implementations

For LLM-specific memory (conversation history, context window strategies) see
[LLM Integration](llm-integration.md).

---

## Package Overview

```
jentic-core / dev.agenor.core.memory
├── MemoryStore.java       # Core storage interface
├── MemoryEntry.java       # Immutable entry record (builder)
├── MemoryQuery.java       # Search query record (builder)
├── MemoryScope.java       # SHORT_TERM / LONG_TERM enum
├── MemoryStats.java       # Stats record
└── MemoryException.java   # Typed error hierarchy

jentic-runtime / dev.agenor.runtime.memory
└── InMemoryStore.java     # Default MemoryStore implementation
```

Agent state persistence classes (`dev.agenor.core.persistence`, `dev.agenor.runtime.persistence`)
are documented in [persistence.md](persistence.md).

---

## Memory Scopes

`MemoryScope` is a *hint* that controls how `MemoryStore` implementations should treat an entry.
Actual durability is implementation-defined: `InMemoryStore` is volatile for **both** scopes.

| Scope | Lifetime | Use cases |
|-------|----------|-----------|
| `SHORT_TERM` | In-process; cleared on process exit or explicit `clearMemory()` | Session context, task state, cached computations |
| `LONG_TERM` | Intended to survive restarts; **requires a durable `MemoryStore`** | Learned facts, user profiles, historical patterns |

> **`SHORT_TERM` without TTL**: entries stored via `rememberShort(key, value, null)` have no expiry
> and live until the process exits or `forget()` / `clearMemory()` is called. They are **not**
> cleared on agent stop/restart if the JVM stays alive.

```java
MemoryScope.SHORT_TERM.isShortTerm();  // true
MemoryScope.LONG_TERM.isLongTerm();    // true
```

---

## MemoryEntry

`MemoryEntry` is an immutable record holding a single piece of stored information.

```java
MemoryEntry entry = MemoryEntry.builder("Order #12345 processed successfully")
    .ownerId("order-processor-agent")
    .metadata("orderId", "12345")
    .metadata("status", "completed")
    .expiresAt(Instant.now().plus(Duration.ofDays(30)))
    .sharedWith("shipping-agent", "billing-agent")
    .tokenCount(12)  // for LLM context budgeting
    .build();
```

### Entry fields

| Field | Type | Description |
|-------|------|-------------|
| `content` | `String` | Required. The actual memory text |
| `ownerId` | `String` | Agent that created this entry |
| `metadata` | `Map<String,Object>` | Key-value attributes for filtering |
| `createdAt` | `Instant` | Auto-set to `Instant.now()` |
| `expiresAt` | `Instant?` | Optional TTL; `null` = never expires |
| `sharedWith` | `Set<String>` | Agent IDs with access (shared memory) |
| `tokenCount` | `int` | Estimated token count (for LLM use) |

### Entry access helpers

```java
// Check access
entry.isOwnedBy("order-processor-agent");    // true
entry.isSharedWith("shipping-agent");        // true
entry.canAccess("billing-agent");            // true (owned or shared)
entry.isExpired();                           // false (still within TTL)

// Typed metadata
String status = entry.getMetadata("status", String.class);  // "completed"
```

---

## MemoryQuery

`MemoryQuery` is a builder-based record for searching entries in a `MemoryStore`.

```java
MemoryQuery query = MemoryQuery.builder()
    .text("order")              // substring/full-text search on content
    .scope(MemoryScope.LONG_TERM)
    .ownerId("order-processor-agent")
    .filter("status", "completed")
    .limit(10)                  // 1–1000 (required, default 10)
    .build();
```

All filters are optional. `scope` defaults to `SHORT_TERM`. `limit` must be positive and ≤ 1000.

---

## MemoryStore Interface

```java
public interface MemoryStore {
    // Store (upsert by key+scope)
    CompletableFuture<Void> store(String key, MemoryEntry entry, MemoryScope scope);

    // Retrieve — expired entries return Optional.empty()
    CompletableFuture<Optional<MemoryEntry>> retrieve(String key, MemoryScope scope);

    // Search with MemoryQuery
    CompletableFuture<List<MemoryEntry>> search(MemoryQuery query);

    // Delete — idempotent
    CompletableFuture<Void> delete(String key, MemoryScope scope);

    // Clear all in scope — cannot be undone
    CompletableFuture<Void> clear(MemoryScope scope);

    // List all keys in scope
    CompletableFuture<List<String>> listKeys(MemoryScope scope);

    // Existence check (more efficient than retrieve when you only need boolean)
    default CompletableFuture<Boolean> exists(String key, MemoryScope scope);

    // Stats (may be cached)
    default MemoryStats getStats();

    default String getStoreName();
}
```

All operations are non-blocking and return `CompletableFuture`. Implementations must be thread-safe.

### Direct usage example

```java
MemoryStore store = new InMemoryStore();

// Store
MemoryEntry entry = MemoryEntry.builder("User prefers dark mode")
    .ownerId("pref-agent")
    .metadata("category", "ui")
    .build();

store.store("pref:user:theme", entry, MemoryScope.LONG_TERM).join();

// Retrieve
Optional<MemoryEntry> found = store.retrieve("pref:user:theme", MemoryScope.LONG_TERM).join();
found.ifPresent(e -> System.out.println(e.content()));

// Search
List<MemoryEntry> results = store.search(
    MemoryQuery.builder()
        .text("user")
        .scope(MemoryScope.LONG_TERM)
        .limit(5)
        .build()
).join();

// Existence check
boolean exists = store.exists("pref:user:theme", MemoryScope.LONG_TERM).join();

// Delete
store.delete("pref:user:theme", MemoryScope.LONG_TERM).join();
```

---

## InMemoryStore

`InMemoryStore` is the default `MemoryStore` implementation in `jentic-runtime`. It stores entries in thread-safe `ConcurrentHashMap` instances, automatically removes expired entries on retrieval and via a background cleanup task, and does **not** persist to disk.

```java
// Default: max 10 000 entries per scope, 60 s cleanup interval
MemoryStore store = new InMemoryStore();

// Custom: max 500 entries, cleanup every 10 s
MemoryStore store = new InMemoryStore(500, 10);
```

Key behaviours:
- `store()` raises `MemoryException.quotaExceeded` if the scope is full (for new keys).
- `retrieve()` silently evicts expired entries and returns `Optional.empty()`.
- `search()` filters by text (substring), ownerId, and metadata in a single pass.
- `getStats()` returns cached statistics (refreshed on each write).

### Limitations

`InMemoryStore` is not suitable for long-term persistence across JVM restarts. For durable `LONG_TERM` memories use a custom `MemoryStore` backed by a database, or see [persistence.md](persistence.md) for agent state persistence options.

---

## BaseAgent Memory API

`BaseAgent` provides protected convenience methods so agents do not need to interact with `MemoryStore` directly. All keys are automatically **namespaced** as `agent:<agentId>:<key>` to avoid collisions between agents.

`MemoryStore` must be injected by the runtime (or set via `setMemoryStore()`) before these methods can be used. All methods throw `IllegalStateException` if no store is configured; use `hasMemory()` as a guard.

### Short-term memory (volatile)

```java
// Store with TTL
rememberShort("session-id", "abc123", Duration.ofMinutes(30));

// Store without TTL (lives until process exits or explicit deletion)
rememberShort("temp-result", "42", null);
```

### Long-term memory (persistent)

```java
// Basic
rememberLong("user-preference", "dark-mode");

// With metadata
rememberLong("user-preference", "dark-mode", Map.of(
    "category", "ui",
    "confidence", "high"
));
```

### Recall (retrieve by key)

```java
// Returns Optional<String> — empty if not found or expired
Optional<String> sessionId = recall("session-id", MemoryScope.SHORT_TERM).join();
sessionId.ifPresent(id -> log.info("Session: {}", id));
```

### Search

```java
// Text search in own memories
List<String> matches = searchMemory("dark-mode", MemoryScope.LONG_TERM).join();

// Custom query (returns full MemoryEntry objects)
MemoryQuery query = MemoryQuery.builder()
    .text("preference")
    .scope(MemoryScope.LONG_TERM)
    .filter("category", "ui")
    .limit(10)
    .build();
List<MemoryEntry> entries = searchMemory(query).join();
```

### Forget (delete) and clear

```java
// Delete one key
forget("session-id", MemoryScope.SHORT_TERM).join();

// Clear entire scope — irreversible
clearMemory(MemoryScope.SHORT_TERM).join();
```

### Stats

```java
MemoryStats stats = getMemoryStats();
log.info("Short-term: {}, Long-term: {}, ~{} tokens",
    stats.shortTermCount(), stats.longTermCount(), stats.estimatedTokens());
```

### Complete example: agent with memory

```java
@JenticAgent("preferences-agent")
public class PreferencesAgent extends BaseAgent {

    @Override
    protected void onStart() {
        log.info("Memory ready: {}", hasMemory());
    }

    @JenticMessageHandler("user.update-preference")
    public void storePreference(Message msg) {
        String value = msg.getContent(String.class);

        rememberLong("theme", value, Map.of("category", "ui")).thenRun(() ->
            log.info("Stored preference: theme={}", value)
        );
    }

    @JenticMessageHandler("user.get-preference")
    public void getPreference(Message msg) {
        recall("theme", MemoryScope.LONG_TERM).thenAccept(opt -> {
            String theme = opt.orElse("system");
            messageService.send(Message.builder()
                .topic("user.preference-result")
                .content(theme)
                .build());
        });
    }
}
```

---

## Shared Memory (Multi-Agent Coordination)

Agents can share memory entries with other agents without going through the message bus. Useful for orchestrated workflows where multiple agents need read access to the same data.

```java
// Agent A writes a shared result
shareMemory("pipeline:result:step1", resultJson,
    "step2-agent", "aggregator-agent").join();

// Agent B reads the shared result
Optional<String> result = recallShared("pipeline:result:step1").join();
```

Shared entries are stored under the key `shared:<key>` in `SHORT_TERM` scope. Access is controlled by the `sharedWith` set: `recallShared` returns `Optional.empty()` for agents not in the set.

To share with a dynamically known set of agents, use `MemoryStore` directly:

```java
Set<String> recipients = getWorkerAgentIds();
MemoryEntry entry = MemoryEntry.builder(taskResult)
    .ownerId(getAgentId())
    .sharedWith(recipients)
    .build();
memoryStore.store("shared:task-result", entry, MemoryScope.SHORT_TERM).join();
```

---

## MemoryException Error Handling

```java
try {
    store.store(key, entry, MemoryScope.LONG_TERM).join();
} catch (CompletionException ex) {
    if (ex.getCause() instanceof MemoryException memEx) {
        switch (memEx.getErrorType()) {
            case QUOTA_EXCEEDED -> log.warn("Memory full: {}", memEx.getStoreName());
            case VALIDATION_ERROR -> log.error("Invalid entry: {}", memEx.getMessage());
            case STORAGE_ERROR   -> {
                if (memEx.isRetryable()) retryLater();
                else log.error("Fatal storage error", memEx);
            }
            case ACCESS_DENIED   -> log.warn("Access denied to memory");
            default              -> log.error("Memory error [{}]", memEx.getErrorType(), memEx);
        }
    }
}
```

### ErrorType values

`UNKNOWN`, `STORAGE_ERROR` (retryable), `VALIDATION_ERROR`, `QUOTA_EXCEEDED`, `ACCESS_DENIED`, `NOT_FOUND`, `SERIALIZATION_ERROR`, `TIMEOUT`.

### Factory methods for tests

```java
throw MemoryException.storageError("InMemoryStore", "disk full", ioException);
throw MemoryException.quotaExceeded("InMemoryStore", "max 10000 entries");
throw MemoryException.validationError("content cannot be blank");
throw MemoryException.accessDenied("agent not in sharedWith set");
```

---

## MemoryStats

```java
MemoryStats stats = store.getStats();

System.out.println("Short-term entries : " + stats.shortTermCount());
System.out.println("Long-term entries  : " + stats.longTermCount());
System.out.println("Total              : " + stats.totalCount());
System.out.println("Estimated tokens   : " + stats.estimatedTokens());
System.out.println("Estimated size     : " + stats.estimatedSizeKB() + " KB");
System.out.println("Last updated       : " + stats.lastUpdated());
System.out.println("Stale (> 5 min)?   : " + stats.isStale(Duration.ofMinutes(5)));
System.out.println("Empty?             : " + stats.isEmpty());
```

---

## Decision Guide

| Scenario | Recommended approach |
|----------|---------------------|
| Session-scoped data (cleared on restart) | `rememberShort` / `MemoryScope.SHORT_TERM` |
| Facts that survive restarts | `rememberLong` / `MemoryScope.LONG_TERM` + a **durable** `MemoryStore` (not `InMemoryStore`) |
| Cross-agent data sharing | `shareMemory` / `recallShared` |
| Agent business state (counters, queues) | See [persistence.md](persistence.md) — `Stateful` + `FilePersistenceService` |
| LLM conversation history | `LLMMemoryManager` (see `docs/llm-integration.md`) |
| Scheduled auto-save / snapshots | See [persistence.md](persistence.md) — `@JenticPersistenceConfig` |

---

## See Also

- [Agent State Persistence Guide](persistence.md) — `Stateful`, `AgentState`, `FilePersistenceService`, `PersistenceManager`
- [Agent Development Guide](agent-development.md) — `@JenticPersist`, `@JenticPersistenceConfig` annotations
- [LLM Integration Guide](llm-integration.md) — `LLMMemoryManager`, `ContextWindowStrategy`
- [Architecture Guide](architecture.md) — module overview
