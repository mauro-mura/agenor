# ADR-013 — MCP Adapter: Official SDK vs Custom HTTP+SSE

**Status**: Accepted  
**Date**: 2026-03-16  
**Last Modified**: 2026-11-17 (see Amendment below)  
**Deciders**: Project team

---

## Context

Agenor agents need to consume tools exposed via the Model Context Protocol (MCP).  
MCP defines a JSON-RPC 2.0 protocol over multiple transports (SSE, Streamable-HTTP, STDIO).  
We need to decide how to implement the client side of this protocol.

Constraints:
- `agenor-core` must remain free of external dependencies (ADR-002)
- The adapter must integrate with Agenor's async model (`CompletableFuture`-based)
- The Java MCP official SDK (`io.modelcontextprotocol.sdk:mcp:1.0.0`) was released February 2026 (MIT, Maven Central)

---

## Decision

**Use the official Java MCP SDK** (`io.modelcontextprotocol.sdk:mcp:1.0.0`) wrapped in a Agenor adapter.

---

## Alternatives Considered

### Option A — Official SDK `io.modelcontextprotocol.sdk:mcp:1.0.0` ✅ Chosen

**Approach**: Wrap `McpSyncClient` (SDK) with `CompletableFuture.supplyAsync()` to bridge the sync SDK into Agenor's async model.

```
McpSyncClient (SDK sync) ──supplyAsync()──► CompletableFuture (Agenor)
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
        ├── McpToolMapper.java          (SDK types → Agenor records)
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

---

## Amendment — 2026-11-17: the condition for Streamable HTTP has been met

**Status**: Accepted. Corrects the *Consequences*; the choice of the official SDK stands.

The last bullet above deferred Streamable HTTP "for when remote hosted servers adopt the new
transport". They have. HTTP+SSE was deprecated by the MCP protocol revision 2025-03-26, which made
Streamable HTTP the remote transport, so `fromSse` now reaches only servers that still serve the
legacy transport. *Context* listing three transports and *Option A* claiming "all three transports
supported out of the box" describe the SDK, not what Agenor wires: Agenor offers SSE and STDIO.

agenor-research met this on 2026-10-21 (its D4): `fromSse` against a Streamable HTTP endpoint fails
after about 20 seconds without naming the transport, and the 38-line workaround had to copy the
adapter because `AgenorMcpClientAdapter`'s constructors and `McpToolMapper` are package-private.
The transport itself, `HttpClientStreamableHttpTransport`, is already in SDK 1.0.0.

What changes here is the documentation: `docs/adapters/mcp.md` and `McpClientFactory`'s Javadoc
now say that SSE is the legacy transport and what a Streamable HTTP endpoint does to it. Whether
to wire Streamable HTTP, and to make the adapter reusable with another transport, is a separate
decision this amendment does not take.
