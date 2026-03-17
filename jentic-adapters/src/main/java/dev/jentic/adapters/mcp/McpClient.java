package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Jentic abstraction over an MCP server connection.
 *
 * <p>Implementations must be thread-safe. All operations are non-blocking and return
 * {@link CompletableFuture} to integrate with Jentic's async execution model.
 *
 * <p>Typical usage:
 * <pre>{@code
 * McpClient client = McpClientFactory.from("http://localhost:3000/sse");
 * client.listTools()
 *       .thenCompose(tools -> client.callTool("read_file", Map.of("path", "/tmp/test.txt")))
 *       .thenAccept(result -> System.out.println(result.content()));
 * }</pre>
 */
public interface McpClient {

    /**
     * Returns the list of tools advertised by the MCP server.
     *
     * @return a future that completes with the tool list; never null, may be empty
     */
    CompletableFuture<List<McpTool>> listTools();

    /**
     * Invokes a tool on the MCP server.
     *
     * @param name tool name as returned by {@link #listTools()}
     * @param args tool input arguments matching the tool's {@code inputSchema}
     * @return a future that completes with the tool result;
     *         {@link McpToolResult#isError()} is {@code true} if the server reported an error
     */
    CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args);
}