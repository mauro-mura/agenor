package dev.jentic.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jentic.core.mcp.McpTool;
import dev.jentic.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Maps MCP SDK types to Jentic domain records.
 *
 * <p>All methods are stateless and thread-safe.
 */
final class McpToolMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolMapper() {}

    /**
     * Maps an SDK {@link McpSchema.Tool} to a Jentic {@link McpTool}.
     */
    static McpTool from(McpSchema.Tool sdkTool) {
        JsonNode schema = null;
        if (sdkTool.inputSchema() != null) {
            schema = MAPPER.valueToTree(sdkTool.inputSchema());
        }
        return new McpTool(
                sdkTool.name(),
                sdkTool.description(),
                schema
        );
    }

    /**
     * Maps an SDK {@link McpSchema.CallToolResult} to a Jentic {@link McpToolResult}.
     *
     * <p>Content blocks are concatenated in order, separated by newlines.
     */
    static McpToolResult from(McpSchema.CallToolResult sdkResult) {
        String content = extractContent(sdkResult.content());
        boolean isError = Boolean.TRUE.equals(sdkResult.isError());
        return new McpToolResult(content, isError);
    }

    private static String extractContent(List<McpSchema.Content> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content block : blocks) {
            if (block instanceof McpSchema.TextContent text) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}