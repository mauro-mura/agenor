# ADR-013 — MCP Adapter: Official SDK vs Custom HTTP+SSE

**Status**: Accepted  
**Date**: 2026-03-16  
**Deciders**: Project team

---

## Context

Jentic agents need to consume tools exposed via the Model Context Protocol (MCP).  
MCP defines a JSON-RPC 2.0 protocol over multiple transports (SSE, Streamable-HTTP, STDIO).  
We need to decide how to implement the client side of this protocol.

Constraints:
- `agenor-core` must remain free of external dependencies (ADR-002)
- The adapter must integrate with Jentic's async model (`CompletableFuture`-based)
- The Java MCP official SDK (`io.modelcontextprotocol.sdk:mcp:1.0.0`) was released February 2026 (MIT, Maven Central)

---

## Decision

**Use the official Java MCP SDK** (`io.modelcontextprotocol.sdk:mcp:1.0.0`) wrapped in a Jentic adapter.

---

## Alternatives Considered

### Option A — Official SDK `io.modelcontextprotocol.sdk:mcp:1.0.0` ✅ Chosen

**Approach**: Wrap `McpSyncClient` (SDK) with `CompletableFuture.supplyAsync()` to bridge the sync SDK into Jentic's async model.

```
McpSyncClient (SDK sync) ──supplyAsync()──► CompletableFuture (Jentic)
```

**Pros**:
- Full MCP spec conformance guaranteed by the SDK maintainers
- All three transports supported out of the box: SSE, Streamable-HTTP, STDIO
- `ToolListChanged` notification handling built in
- Zero transport-layer maintenance burden
- MIT license, stable Maven Central artifact

**Cons**:
- Transitive dependency on Project Reactor (via SDK internals)
- `McpSyncClient` blocking calls require thread pool sizing attention

**Mitigation**: Reactor dependency stays confined to `agenor-adapters` module; it does not leak into `agenor-core` or `agenor-runtime` (ADR-002 respected).

---

### Option B — Custom `java.net.http` + JSON-RPC from scratch ❌ Rejected

**Approach**: Implement MCP client using `java.net.http.HttpClient` with manual SSE parsing and JSON-RPC 2.0 message handling.

**Pros**:
- Zero external dependencies
- Full control over implementation

**Cons**:
- Significant implementation effort (~2–3 weeks for full transport support)
- High risk of spec drift as MCP protocol evolves
- SSE reconnection, backpressure, and error handling must be reimplemented from scratch
- No benefit over Option A given SDK availability and license

---

## Architecture

### Module Boundary

```
agenor-core
  └── dev.agenor.core.mcp
        ├── McpTool.java          (record — no SDK dependency)
        ├── McpToolResult.java    (record — no SDK dependency)
        └── McpClient.java        (interface — CompletableFuture + core types only)

agenor-adapters
  └── dev.agenor.adapters.mcp
        ├── AgenorMcpClientAdapter.java (wraps McpSyncClient, implements McpClient)
        ├── McpToolMapper.java          (SDK types → Jentic records)
        ├── McpClientFactory.java       (factory: serverUrl → adapter)
        ├── McpToolRegistry.java        (cache TTL 60s + ToolListChanged)
        └── McpFunctionAdapter.java     (McpTool → LLMFunction adapter)
```

### Bridge Pattern

```java
// AgenorMcpClientAdapter — async bridge over sync SDK
public CompletableFuture<List<McpTool>> listTools() {
    return CompletableFuture.supplyAsync(() ->
        sdkClient.listTools().tools().stream()
            .map(McpToolMapper::from)
            .toList()
    );
}
```

### ADR-002 Compliance

`agenor-core` declares **no dependency** on `io.modelcontextprotocol.sdk`.  
The SDK dependency is scoped exclusively to `agenor-adapters/pom.xml`.  
The transitive Reactor dependency is therefore confined to the adapters module.

---

## Consequences

- `AgenorMcpClientAdapter` requires a dedicated thread pool for `supplyAsync()` calls (default: `ForkJoinPool.commonPool()`; configurable)
- `McpClientFactory` performs the MCP handshake (`initialize()`) synchronously at construction time — callers should construct outside hot paths
- `McpToolRegistry` must handle `ToolListChanged` notifications from the SDK to invalidate its cache immediately
- **Supported transports (F2 scope): SSE + STDIO** — SSE via `HttpClientSseClientTransport` (remote servers), STDIO via `StdioClientTransport` (local processes, required by the `npx @modelcontextprotocol/server-filesystem` example in T6)
- `McpClientFactory` exposes two factory methods: `fromSse(String serverUrl)` and `fromStdio(String command, String... args)`
- **Streamable-HTTP**: not implemented in F2 — tracked as backlog item for when remote hosted servers adopt the new transport
