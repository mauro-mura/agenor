package dev.jentic.core.mcp;

/**
 * Represents the result of an MCP tool invocation.
 *
 * <p>SDK types are mapped to this record in {@code jentic-adapters} via {@code McpToolMapper}.
 *
 * @param content the text content returned by the tool (may be empty, never null)
 * @param isError {@code true} if the MCP server reported this as an error result
 */
public record McpToolResult(
        String content,
        boolean isError
) {
    public McpToolResult {
        if (content == null) {
            content = "";
        }
    }

    /** Convenience factory for a successful result. */
    public static McpToolResult success(String content) {
        return new McpToolResult(content, false);
    }

    /** Convenience factory for an error result. */
    public static McpToolResult error(String content) {
        return new McpToolResult(content, true);
    }
}