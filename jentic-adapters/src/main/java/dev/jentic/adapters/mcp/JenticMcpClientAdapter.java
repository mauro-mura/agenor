package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.McpSyncClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Jentic {@link McpClient} implementation backed by the official MCP Java SDK.
 *
 * <p>Bridges the synchronous {@link McpSyncClient} into Jentic's async model via
 * {@link CompletableFuture#supplyAsync(java.util.function.Supplier, Executor)}.
 *
 * <p>Instances are created via {@link McpClientFactory} — do not instantiate directly.
 *
 * <p>Thread safety: all public methods are thread-safe; each call dispatches to the
 * provided {@link Executor}.
 */
public class JenticMcpClientAdapter implements McpClient {

    private final McpSyncClient sdkClient;
    private final Executor executor;

    /**
     * @param sdkClient already-initialized SDK client (handshake completed)
     * @param executor  executor used for async dispatch; defaults to
     *                  {@link ForkJoinPool#commonPool()} when {@code null}
     */
    JenticMcpClientAdapter(McpSyncClient sdkClient, Executor executor) {
        this.sdkClient = sdkClient;
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    @Override
    public CompletableFuture<List<McpTool>> listTools() {
        return CompletableFuture.supplyAsync(
                () -> sdkClient.listTools().tools().stream()
                        .map(McpToolMapper::from)
                        .toList(),
                executor
        );
    }

    @Override
    public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
        return CompletableFuture.supplyAsync(
                () -> McpToolMapper.from(
                        sdkClient.callTool(new McpSchema.CallToolRequest(name, args))
                ),
                executor
        );
    }

    /**
     * Closes the underlying SDK client and releases transport resources.
     */
    public void close() {
        sdkClient.close();
    }
}