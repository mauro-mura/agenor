package dev.jentic.adapters.mcp;

import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolMapperTest {

    // --- McpSchema.Tool → McpTool ---

    @Test
    void from_sdkTool_mapsNameAndDescription() {
        McpSchema.Tool sdkTool = mock(McpSchema.Tool.class);
        when(sdkTool.name()).thenReturn("read_file");
        when(sdkTool.description()).thenReturn("Reads a file from disk");
        when(sdkTool.inputSchema()).thenReturn(null);

        McpTool result = McpToolMapper.from(sdkTool);

        assertThat(result.name()).isEqualTo("read_file");
        assertThat(result.description()).isEqualTo("Reads a file from disk");
    }

    @Test
    void from_sdkTool_nullInputSchema_producesEmptyObjectNode() {
        McpSchema.Tool sdkTool = mock(McpSchema.Tool.class);
        when(sdkTool.name()).thenReturn("ping");
        when(sdkTool.description()).thenReturn("Ping");
        when(sdkTool.inputSchema()).thenReturn(null);

        McpTool result = McpToolMapper.from(sdkTool);

        assertThat(result.inputSchema()).isNotNull();
        assertThat(result.inputSchema().isObject()).isTrue();
        assertThat(result.inputSchema().isEmpty()).isTrue();
    }

    @Test
    void from_sdkTool_withInputSchema_producesJsonNode() {
        McpSchema.JsonSchema schema = mock(McpSchema.JsonSchema.class);
        when(schema.type()).thenReturn("object");

        McpSchema.Tool sdkTool = mock(McpSchema.Tool.class);
        when(sdkTool.name()).thenReturn("write_file");
        when(sdkTool.description()).thenReturn("Writes a file");
        when(sdkTool.inputSchema()).thenReturn(schema);

        McpTool result = McpToolMapper.from(sdkTool);

        assertThat(result.inputSchema()).isNotNull();
        assertThat(result.inputSchema().get("type").asText()).isEqualTo("object");
    }

    // --- McpSchema.CallToolResult → McpToolResult ---

    @Test
    void from_sdkResult_isErrorFalse_mapsToSuccess() {
        McpSchema.TextContent textBlock = mock(McpSchema.TextContent.class);
        when(textBlock.text()).thenReturn("file contents");

        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(List.of(textBlock));

        McpToolResult result = McpToolMapper.from(sdkResult);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("file contents");
    }

    @Test
    void from_sdkResult_isErrorTrue_mapsToError() {
        McpSchema.TextContent textBlock = mock(McpSchema.TextContent.class);
        when(textBlock.text()).thenReturn("permission denied");

        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(true);
        when(sdkResult.content()).thenReturn(List.of(textBlock));

        McpToolResult result = McpToolMapper.from(sdkResult);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("permission denied");
    }

    @Test
    void from_sdkResult_multipleTextBlocks_concatenatedWithNewline() {
        McpSchema.TextContent block1 = mock(McpSchema.TextContent.class);
        when(block1.text()).thenReturn("line one");
        McpSchema.TextContent block2 = mock(McpSchema.TextContent.class);
        when(block2.text()).thenReturn("line two");

        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(List.of(block1, block2));

        McpToolResult result = McpToolMapper.from(sdkResult);

        assertThat(result.content()).isEqualTo("line one\nline two");
    }

    @Test
    void from_sdkResult_emptyContentList_producesEmptyString() {
        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(List.of());

        McpToolResult result = McpToolMapper.from(sdkResult);

        assertThat(result.content()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void from_sdkResult_nullContentList_producesEmptyString() {
        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(false);
        when(sdkResult.content()).thenReturn(null);

        McpToolResult result = McpToolMapper.from(sdkResult);

        assertThat(result.content()).isEmpty();
    }

    @Test
    void from_sdkResult_nullIsError_treatedAsFalse() {
        McpSchema.CallToolResult sdkResult = mock(McpSchema.CallToolResult.class);
        when(sdkResult.isError()).thenReturn(null);
        when(sdkResult.content()).thenReturn(List.of());

        McpToolResult result = McpToolMapper.from(sdkResult);

        assertThat(result.isError()).isFalse();
    }
}