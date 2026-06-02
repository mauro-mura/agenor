# MCP Adapter
 
Integrates [Model Context Protocol](https://modelcontextprotocol.io) servers into Agenor agents.
MCP tools are exposed as `FunctionDefinition`s to any `LLMProvider`, enabling a full tool-call
round-trip without writing provider-specific code.
 
---
 
## Maven dependency
 
```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters</artifactId>
    <version>${agenor.version}</version>
</dependency>
```
 
---
 
## Supported transports
 
| Transport | Factory method | Notes |
|-----------|----------------|-------|
| **SSE** | `McpClientFactory.fromSse(url)` | Remote servers over HTTP/SSE |
| **STDIO** | `McpClientFactory.fromStdio(command, args...)` | Local subprocess (e.g. Docker) |
| Streamable-HTTP | — | Not supported; tracked as backlog |
 
---
 
## Quick start
 
```java
// 1 — connect (performs MCP handshake synchronously)
var mcpClient = McpClientFactory.fromSse("http://localhost:3000/sse");
 
// 2 — cache layer (default TTL: 60s)
var registry = new McpToolRegistry(mcpClient);
 
// 3 — adapter: McpTool → FunctionDefinition / FunctionCall → LLMMessage
var adapter = new McpFunctionAdapter(registry);
 
// 4 — build function list and attach to LLM request
List<FunctionDefinition> functions = adapter.buildFunctionDefinitions().get();
 
LLMRequest.Builder builder = LLMRequest.builder()
        .maxTokens(512);
functions.forEach(builder::addFunction);
builder.addMessage(LLMMessage.user("List /tmp"));
 
// 5 — tool-call loop
LLMResponse response = provider.chat(builder.build()).get();
while (response.hasFunctionCalls()) {
    history.add(LLMMessage.assistant(response.content(), response.functionCalls()));
    for (FunctionCall call : response.functionCalls()) {
        history.add(adapter.execute(call).get());
    }
    response = provider.chat(/* next request with history */).get();
}
 
mcpClient.close();
```
 
---
 
## Docker-based STDIO (no local Node.js required)
 
Run a filesystem MCP server in Docker without installing Node.js locally:
 
```java
var mcpClient = McpClientFactory.fromStdio(
    "docker", "run", "--rm", "-i",
    "-v", "/tmp:/tmp",
    "node:22-alpine",
    "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"
);
```
 
---
 
## Component overview
 
| Class | Module | Responsibility |
|-------|--------|---------------|
| `McpTool` | `agenor-core` | Record: `name`, `description`, `inputSchema` |
| `McpToolResult` | `agenor-core` | Record: `content`, `isError` |
| `McpClient` | `agenor-core` | Interface: `listTools()`, `callTool()`, `close()` |
| `AgenorMcpClientAdapter` | `agenor-adapters` | Wraps `McpSyncClient` via `supplyAsync()` |
| `McpClientFactory` | `agenor-adapters` | Creates adapters for SSE and STDIO transports |
| `McpToolRegistry` | `agenor-adapters` | Thread-safe cache (TTL 60s, `invalidate()`) |
| `McpFunctionAdapter` | `agenor-adapters` | `McpTool` → `FunctionDefinition`; `FunctionCall` → `LLMMessage` |
 
---
 
## Custom executor and TTL
 
```java
// Custom executor
ExecutorService executor = Executors.newFixedThreadPool(4);
var client = McpClientFactory.fromSse("http://localhost:3000/sse", executor);
 
// Custom TTL
var registry = new McpToolRegistry(client, Duration.ofSeconds(30));
 
// Manual invalidation (wire to ToolListChanged SDK notification)
registry.invalidate();
```
 
---
 
## Running the example
 
Requires Docker:
 
```bash
# Tool listing + direct call
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass=dev.agenor.examples.McpExample
 
# With LLM round-trip
export ANTHROPIC_API_KEY=sk-ant-...
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass=dev.agenor.examples.McpExample
 
# Custom root directory
mvn exec:java -pl agenor-examples \
    -Dexec.mainClass=dev.agenor.examples.McpExample \
    -Dmcp.root=/path/to/dir
```
 
---
 
## See also
 
- `ADR-013` — architectural decision: official SDK vs custom HTTP+SSE
- `McpFunctionAdapterTest` — unit tests for the full round-trip with mock client
- `McpToolRegistryTest` — cache TTL and invalidation tests