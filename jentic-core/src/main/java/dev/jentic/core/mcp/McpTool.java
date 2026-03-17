package dev.jentic.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents an MCP tool as exposed by an MCP server.
 *
 * <p>SDK types are mapped to this record in {@code jentic-adapters} via {@code McpToolMapper}.
 *
 * @param name        unique tool name within the MCP server
 * @param description human-readable description of what the tool does
 * @param inputSchema JSON Schema describing the tool's input parameters;
 *                    may be {@code null} if the server does not advertise a schema
 */
public record McpTool(
        String name,
        String description,
        JsonNode inputSchema
) {
    public McpTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("McpTool name must not be blank");
        }
    }
}