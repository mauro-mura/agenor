package dev.agenor.adapters.mcp;

import dev.agenor.core.mcp.McpClient;
import dev.agenor.core.mcp.McpTool;
import dev.agenor.core.mcp.McpToolResult;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanStatus;
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
public class AgenorMcpClientAdapter implements McpClient {

    private final McpSyncClient sdkClient;
    private final Executor executor;
    private final AgenorTelemetry telemetry;
    private final String transport;

    AgenorMcpClientAdapter(McpSyncClient sdkClient, Executor executor) {
        this(sdkClient, executor, AgenorTelemetry.noop(), "unknown");
    }

    AgenorMcpClientAdapter(McpSyncClient sdkClient, Executor executor,
                           AgenorTelemetry telemetry, String transport) {
        this.sdkClient  = sdkClient;
        this.executor   = executor;
        this.telemetry  = telemetry != null ? telemetry : AgenorTelemetry.noop();
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
