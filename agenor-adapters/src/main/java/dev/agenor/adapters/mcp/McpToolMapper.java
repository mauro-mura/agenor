package dev.agenor.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.mcp.McpTool;
import dev.agenor.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stateless mapper between MCP SDK types and Agenor core records.
 */
final class McpToolMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private McpToolMapper() {}

    static McpTool from(McpSchema.Tool sdkTool) {
        // inputSchema() returns McpSchema.JsonSchema in SDK 1.0.0; normalize to JsonNode
        JsonNode schema = toJsonNode(sdkTool.inputSchema());
        return new McpTool(sdkTool.name(), sdkTool.description(), schema);
    }

    static McpToolResult from(McpSchema.CallToolResult sdkResult) {
        String content = extractText(sdkResult.content());
        boolean isError = Boolean.TRUE.equals(sdkResult.isError());
        return new McpToolResult(content, isError);
    }

    // -------------------------------------------------------------------------

    private static JsonNode toJsonNode(Object inputSchema) {
        if (inputSchema == null) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            if (inputSchema instanceof JsonNode node) {
                return node;
            }
            // SDK may return a Map or a raw string — normalize via Jackson
            return OBJECT_MAPPER.valueToTree(inputSchema);
        } catch (Exception e) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static String extractText(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        return contents.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
