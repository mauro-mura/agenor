package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpClient;
import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Jentic {@link McpClient} that delegates to the official MCP Java SDK's
 * {@link McpSyncClient}, bridging its blocking calls to {@link CompletableFuture}
 * via {@code supplyAsync()}.
 *
 * <p>Use {@link McpClientFactory} to construct instances.
 */
public class JenticMcpClientAdapter implements McpClient {

    private final McpSyncClient sdkClient;
    private final Executor executor;

    JenticMcpClientAdapter(McpSyncClient sdkClient, Executor executor) {
        this.sdkClient = sdkClient;
        this.executor  = executor;
    }

    @Override
    public CompletableFuture<List<McpTool>> listTools() {
        return CompletableFuture.supplyAsync(() ->
                        sdkClient.listTools().tools().stream()
                                .map(McpToolMapper::from)
                                .toList(),
                executor
        );
    }

    @Override
    public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            var request = new McpSchema.CallToolRequest(name, args);
            return McpToolMapper.from(sdkClient.callTool(request));
        }, executor);
    }

    @Override
    public void close() {
        sdkClient.close();
    }
}