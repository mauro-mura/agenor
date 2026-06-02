package dev.agenor.core.mcp;

/**
 * Immutable result of an MCP tool invocation.
 *
 * @param content text content returned by the tool (maybe empty on error)
 * @param isError {@code true} if the server signaled an error condition
 */
public record McpToolResult(String content, boolean isError) {

    public McpToolResult {
        if (content == null) {
            content = "";
        }
    }

    /** Convenience factory for successful results. */
    public static McpToolResult success(String content) {
        return new McpToolResult(content, false);
    }

    /** Convenience factory for error results. */
    public static McpToolResult error(String message) {
        return new McpToolResult(message, true);
    }
}
