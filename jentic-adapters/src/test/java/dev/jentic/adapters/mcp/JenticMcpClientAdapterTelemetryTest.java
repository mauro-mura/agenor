package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpToolResult;
import dev.jentic.core.telemetry.*;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link JenticMcpClientAdapter#callTool} emits {@code mcp.tool.call} spans
 * with the correct attributes ({@code mcp.tool.name}, {@code mcp.transport}) and that
 * spans are always ended — including on error.
 *
 * <p>Uses a direct executor ({@code Runnable::run}) to keep assertions synchronous.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JenticMcpClientAdapter telemetry")
class JenticMcpClientAdapterTelemetryTest {

    @Mock
    private McpSyncClient sdkClient;

    private RecordingTelemetry telemetry;

    @BeforeEach
    void setUp() {
        telemetry = new RecordingTelemetry();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("callTool emits mcp.tool.call span with tool name and transport")
    void callTool_emitsMcpToolCallSpan() throws Exception {
        JenticMcpClientAdapter adapter = new JenticMcpClientAdapter(
                sdkClient, Runnable::run, telemetry, "sse");

        McpSchema.TextContent content = mock(McpSchema.TextContent.class);
        when(content.text()).thenReturn("ok");
        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(List.of(content));
        when(sdkClient.callTool(any())).thenReturn(sdkResult);

        McpToolResult result = adapter.callTool("read_file", Map.of("path", "/tmp/f.txt")).get();

        assertThat(result.isError()).isFalse();

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("mcp.tool.call");
        assertThat(span.stringAttrs.get("mcp.tool.name")).isEqualTo("read_file");
        assertThat(span.stringAttrs.get("mcp.transport")).isEqualTo("sse");
        assertThat(span.status).isEqualTo(SpanStatus.OK);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("transport attribute reflects the transport label passed at construction")
    void callTool_transportAttributeMatchesLabel() throws Exception {
        JenticMcpClientAdapter adapter = new JenticMcpClientAdapter(
                sdkClient, Runnable::run, telemetry, "stdio");

        McpSchema.TextContent content = mock(McpSchema.TextContent.class);
        when(content.text()).thenReturn("output");
        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(List.of(content));
        when(sdkClient.callTool(any())).thenReturn(sdkResult);

        adapter.callTool("list_dir", Map.of()).get();

        assertThat(telemetry.spans.get(0).stringAttrs.get("mcp.transport")).isEqualTo("stdio");
    }

    @Test
    @DisplayName("when sdkClient throws, span status is ERROR and span is still ended")
    void callTool_sdkThrows_spanStatusError() {
        JenticMcpClientAdapter adapter = new JenticMcpClientAdapter(
                sdkClient, Runnable::run, telemetry, "sse");

        when(sdkClient.callTool(any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> adapter.callTool("broken_tool", Map.of()).get())
                .hasCauseInstanceOf(RuntimeException.class);

        assertThat(telemetry.spans).hasSize(1);
        RecordingSpan span = telemetry.spans.get(0);
        assertThat(span.name).isEqualTo("mcp.tool.call");
        assertThat(span.status).isEqualTo(SpanStatus.ERROR);
        assertThat(span.ended).isTrue();
    }

    @Test
    @DisplayName("listTools does NOT emit a span (only callTool is instrumented)")
    void listTools_doesNotEmitSpan() throws Exception {
        JenticMcpClientAdapter adapter = new JenticMcpClientAdapter(
                sdkClient, Runnable::run, telemetry, "sse");

        McpSchema.ListToolsResult sdkResult = mock(McpSchema.ListToolsResult.class);
        when(sdkResult.tools()).thenReturn(List.of());
        when(sdkClient.listTools()).thenReturn(sdkResult);

        adapter.listTools().get();

        assertThat(telemetry.spans).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    static class RecordingTelemetry implements JenticTelemetry {
        final List<RecordingSpan> spans = new ArrayList<>();

        @Override
        public SpanBuilder spanBuilder(String name) {
            return new RecordingSpanBuilder(name, this);
        }
    }

    static class RecordingSpanBuilder implements SpanBuilder {
        private final String name;
        private final RecordingTelemetry telemetry;
        final Map<String, String>  stringAttrs = new LinkedHashMap<>();
        final Map<String, Long>    longAttrs   = new LinkedHashMap<>();
        final Map<String, Boolean> boolAttrs   = new LinkedHashMap<>();

        RecordingSpanBuilder(String name, RecordingTelemetry telemetry) {
            this.name      = name;
            this.telemetry = telemetry;
        }

        @Override public SpanBuilder setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public SpanBuilder setAttribute(String k, long v)    { longAttrs.put(k, v);   return this; }
        @Override public SpanBuilder setAttribute(String k, boolean v) { boolAttrs.put(k, v);   return this; }

        @Override
        public Span startSpan() {
            var span = new RecordingSpan(name,
                    new LinkedHashMap<>(stringAttrs),
                    new LinkedHashMap<>(longAttrs),
                    new LinkedHashMap<>(boolAttrs));
            telemetry.spans.add(span);
            return span;
        }
    }

    static class RecordingSpan implements Span {
        final String name;
        final Map<String, String>  stringAttrs;
        final Map<String, Long>    longAttrs;
        final Map<String, Boolean> boolAttrs;
        SpanStatus status;
        boolean    ended;

        RecordingSpan(String name,
                      Map<String, String> stringAttrs,
                      Map<String, Long> longAttrs,
                      Map<String, Boolean> boolAttrs) {
            this.name        = name;
            this.stringAttrs = stringAttrs;
            this.longAttrs   = longAttrs;
            this.boolAttrs   = boolAttrs;
        }

        @Override public Span setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public Span setAttribute(String k, long v)    { longAttrs.put(k, v);   return this; }
        @Override public Span setAttribute(String k, boolean v) { boolAttrs.put(k, v);   return this; }
        @Override public Span setAttribute(String k, double v)  { return this; }
        @Override public Span recordException(Throwable t)      { return this; }
        @Override public Span setStatus(SpanStatus s)           { this.status = s; return this; }
        @Override public SpanScope makeCurrent() { return () -> {}; }
        @Override public void end()                              { this.ended = true; }
    }
}
