package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpClient;
import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.Span;
import dev.jentic.core.telemetry.SpanStatus;
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
    private final JenticTelemetry telemetry;
    private final String transport;

    JenticMcpClientAdapter(McpSyncClient sdkClient, Executor executor) {
        this(sdkClient, executor, JenticTelemetry.noop(), "unknown");
    }

    JenticMcpClientAdapter(McpSyncClient sdkClient, Executor executor,
                           JenticTelemetry telemetry, String transport) {
        this.sdkClient  = sdkClient;
        this.executor   = executor;
        this.telemetry  = telemetry != null ? telemetry : JenticTelemetry.noop();
        this.transport  = transport != null ? transport : "unknown";
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
            Span span = telemetry.spanBuilder("mcp.tool.call")
                    .setAttribute("mcp.tool.name", name)
                    .setAttribute("mcp.transport", transport)
                    .startSpan();
            try {
                var request = new McpSchema.CallToolRequest(name, args);
                McpToolResult result = McpToolMapper.from(sdkClient.callTool(request));
                span.setStatus(SpanStatus.OK);
                return result;
            } catch (Exception e) {
                span.recordException(e).setStatus(SpanStatus.ERROR);
                throw e;
            } finally {
                span.end();
            }
        }, executor);
    }

    @Override
    public void close() {
        sdkClient.close();
    }
}