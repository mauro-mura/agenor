package dev.agenor.adapters.mcp;

import dev.agenor.core.mcp.McpTool;
import dev.agenor.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgenorMcpClientAdapterTest {

    @Mock
    private McpSyncClient sdkClient;

    private AgenorMcpClientAdapter adapter;

    @BeforeEach
    void setUp() {
        // Use a direct executor to keep tests synchronous and deterministic
        adapter = new AgenorMcpClientAdapter(sdkClient, Runnable::run);
    }

    // --- listTools ---

    @Test
    void listTools_delegatesToSdkClient() throws Exception {
        McpSchema.Tool sdkTool = mock(McpSchema.Tool.class);
        when(sdkTool.name()).thenReturn("read_file");
        when(sdkTool.description()).thenReturn("Reads a file");
        when(sdkTool.inputSchema()).thenReturn(null);

        McpSchema.ListToolsResult sdkResult = mock(McpSchema.ListToolsResult.class);
        when(sdkResult.tools()).thenReturn(List.of(sdkTool));
        when(sdkClient.listTools()).thenReturn(sdkResult);

        List<McpTool> tools = adapter.listTools().get();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("read_file");
        verify(sdkClient).listTools();
    }

    @Test
    void listTools_emptyToolList_returnsEmptyList() throws Exception {
        McpSchema.ListToolsResult sdkResult = mock(McpSchema.ListToolsResult.class);
        when(sdkResult.tools()).thenReturn(List.of());
        when(sdkClient.listTools()).thenReturn(sdkResult);

        List<McpTool> tools = adapter.listTools().get();

        assertThat(tools).isEmpty();
    }

    @Test
    void listTools_sdkThrows_futureCompletesExceptionally() {
        when(sdkClient.listTools()).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> adapter.listTools().get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
    }

    // --- callTool ---

    @Test
    void callTool_delegatesToSdkClientWithCorrectArgs() throws Exception {
        McpSchema.TextContent textBlock = mock(McpSchema.TextContent.class);
        when(textBlock.text()).thenReturn("result content");

        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(List.of(textBlock));
        when(sdkClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(sdkResult);

        McpToolResult result = adapter.callTool("read_file", Map.of("path", "/tmp/test.txt")).get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("result content");

        verify(sdkClient).callTool(argThat(req ->
                "read_file".equals(req.name()) &&
                "/tmp/test.txt".equals(req.arguments().get("path"))
        ));
    }

    @Test
    void callTool_errorResult_propagatesIsError() throws Exception {
        McpSchema.TextContent textBlock = mock(McpSchema.TextContent.class);
        when(textBlock.text()).thenReturn("file not found");

        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(true);
        when(sdkResult.content()).thenReturn(List.of(textBlock));
        when(sdkClient.callTool(any())).thenReturn(sdkResult);

        McpToolResult result = adapter.callTool("read_file", Map.of("path", "/missing")).get();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("file not found");
    }

    @Test
    void callTool_sdkThrows_futureCompletesExceptionally() {
        when(sdkClient.callTool(any())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> adapter.callTool("read_file", Map.of()).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("timeout");
    }

    // --- async dispatch ---

    @Test
    void listTools_usesProvidedExecutor() throws Exception {
        var trackingExecutor = Executors.newSingleThreadExecutor();
        var trackingAdapter = new AgenorMcpClientAdapter(sdkClient, trackingExecutor);

        McpSchema.ListToolsResult sdkResult = mock(McpSchema.ListToolsResult.class);
        when(sdkResult.tools()).thenReturn(List.of());
        when(sdkClient.listTools()).thenReturn(sdkResult);

        // Should complete without error on a custom executor
        assertThat(trackingAdapter.listTools().get()).isEmpty();
        trackingExecutor.shutdown();
    }

    // --- close ---

    @Test
    void close_delegatesToSdkClient() {
        adapter.close();
        verify(sdkClient).close();
    }
}
