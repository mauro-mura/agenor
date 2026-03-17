package dev.jentic.adapters.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;

import java.util.concurrent.Executor;

/**
 * Factory for creating {@link JenticMcpClientAdapter} instances.
 *
 * <p>Performs the MCP handshake ({@code initialize()}) synchronously at construction time.
 * Callers should construct adapters outside hot paths (e.g., at application startup).
 *
 * <p>Example:
 * <pre>{@code
 * JenticMcpClientAdapter client = McpClientFactory.from("http://localhost:3000/sse");
 * // or with a custom executor:
 * JenticMcpClientAdapter client = McpClientFactory.from("http://localhost:3000/sse", myExecutor);
 * }</pre>
 */
public final class McpClientFactory {

    private McpClientFactory() {}

    /**
     * Creates a {@link JenticMcpClientAdapter} connected to the given SSE endpoint.
     *
     * <p>Uses {@link java.util.concurrent.ForkJoinPool#commonPool()} for async dispatch.
     *
     * @param serverUrl base URL of the MCP server (e.g., {@code http://localhost:3000/sse})
     * @return a ready-to-use adapter with completed MCP handshake
     */
    public static JenticMcpClientAdapter from(String serverUrl) {
        return from(serverUrl, null);
    }

    /**
     * Creates a {@link JenticMcpClientAdapter} connected to the given SSE endpoint,
     * dispatching async calls on the provided {@code executor}.
     *
     * @param serverUrl base URL of the MCP server
     * @param executor  executor for {@link java.util.concurrent.CompletableFuture#supplyAsync};
     *                  pass {@code null} to use {@link java.util.concurrent.ForkJoinPool#commonPool()}
     * @return a ready-to-use adapter with completed MCP handshake
     */
    public static JenticMcpClientAdapter from(String serverUrl, Executor executor) {
        var transport = HttpClientSseClientTransport.builder(serverUrl).build();
        McpSyncClient sdkClient = McpClient.sync(transport).build();
        sdkClient.initialize(); // MCP handshake + capability negotiation
        return new JenticMcpClientAdapter(sdkClient, executor);
    }
}