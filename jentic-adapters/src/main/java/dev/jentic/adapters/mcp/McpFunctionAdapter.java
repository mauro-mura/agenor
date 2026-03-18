package dev.jentic.adapters.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jentic.core.llm.FunctionCall;
import dev.jentic.core.llm.FunctionDefinition;
import dev.jentic.core.llm.LLMMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts MCP tools for use in LLM function-calling workflows.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>{@link #buildFunctionDefinitions()} — converts {@link dev.jentic.core.mcp.McpTool}s
 *       from the registry into {@link FunctionDefinition}s that any {@code LLMProvider} can send
 *       to the model.</li>
 *   <li>{@link #execute(FunctionCall)} — when the LLM returns a function call, dispatches it
 *       to {@link McpToolRegistry#callTool} and wraps the result as an {@link LLMMessage}
 *       ready to be appended to the conversation history.</li>
 * </ol>
 *
 * <p>Typical usage:
 * <pre>
 *   McpFunctionAdapter adapter = new McpFunctionAdapter(registry);
 *
 *   // Build tool list for the LLM request
 *   List&lt;FunctionDefinition&gt; functions = adapter.buildFunctionDefinitions().get();
 *
 *   // After LLM returns a function call
 *   for (FunctionCall call : response.functionCalls()) {
 *       LLMMessage result = adapter.execute(call).get();
 *       history.add(result);
 *   }
 * </pre>
 */
public class McpFunctionAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final McpToolRegistry registry;

    public McpFunctionAdapter(McpToolRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link FunctionDefinition} list from all tools currently in the registry.
     *
     * <p>The MCP tool's {@code inputSchema} (JSON Schema object) is converted directly to
     * the {@code Map<String, Object>} that {@link FunctionDefinition} expects as its
     * {@code parameters} field — no per-property iteration needed.
     *
     * @return future completing with the function definitions (never {@code null})
     */
    public CompletableFuture<List<FunctionDefinition>> buildFunctionDefinitions() {
        return registry.getTools().thenApply(tools ->
                tools.stream()
                        .map(this::toFunctionDefinition)
                        .toList()
        );
    }

    /**
     * Executes an LLM function call by dispatching it to the MCP server via the registry,
     * then wraps the result as a {@link LLMMessage} of role {@code FUNCTION}.
     *
     * @param call the function call requested by the LLM
     * @return future completing with the result message
     */
    public CompletableFuture<LLMMessage> execute(FunctionCall call) {
        Map<String, Object> args = parseArguments(call.arguments());
        return registry.callTool(call.name(), args)
                .thenApply(result -> LLMMessage.function(call.name(), result.content()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private FunctionDefinition toFunctionDefinition(dev.jentic.core.mcp.McpTool tool) {
        Map<String, Object> parameters = toParameterMap(tool.inputSchema());
        return new FunctionDefinition(tool.name(), tool.description(), parameters);
    }

    /**
     * Converts a {@link JsonNode} representing a JSON Schema to the
     * {@code Map<String, Object>} format expected by {@link FunctionDefinition}.
     *
     * <p>Returns {@code null} (no parameters) when the schema is null or empty object.
     */
    private static Map<String, Object> toParameterMap(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.convertValue(schema, MAP_TYPE);
        } catch (Exception e) {
            // Malformed schema: treat as no parameters rather than failing the call
            return null;
        }
    }

    /**
     * Parses the JSON arguments string from a {@link FunctionCall} into a
     * {@code Map<String, Object>}.
     *
     * <p>Returns an empty map for null, blank, or unparseable arguments.
     */
    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments.strip())) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(arguments, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}