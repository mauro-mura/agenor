package dev.jentic.adapters.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jentic.core.llm.FunctionCall;
import dev.jentic.core.llm.FunctionDefinition;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.mcp.McpTool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges MCP tools into the Jentic LLM function-calling model.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>{@link #buildFunctionDefinitions()} — fetches the tool list from
 *       {@link McpToolRegistry} and converts each {@link McpTool} to a
 *       {@link FunctionDefinition} that can be included in an {@link dev.jentic.core.llm.LLMRequest}.</li>
 *   <li>{@link #execute(FunctionCall)} — when the LLM issues a function call,
 *       parses the JSON arguments, invokes {@link McpToolRegistry#callTool}, and
 *       wraps the result as an {@link LLMMessage} ready to be appended to the
 *       conversation.</li>
 * </ol>
 *
 * <p>Typical round-trip:
 * <pre>{@code
 * McpFunctionBridge behavior = new McpFunctionBridge(registry);
 *
 * // 1 — build request with MCP tools exposed as functions
 * List<FunctionDefinition> functions = behavior.buildFunctionDefinitions().get();
 * LLMRequest request = LLMRequest.builder(model)
 *     .messages(messages)
 *     .functions(functions)
 *     .build();
 *
 * // 2 — send to LLM provider and handle function calls
 * LLMResponse response = provider.chat(request).get();
 * if (response.hasFunctionCalls()) {
 *     for (FunctionCall call : response.functionCalls()) {
 *         LLMMessage result = behavior.execute(call).get();
 *         messages.add(result);
 *     }
 * }
 * }</pre>
 */
public class McpFunctionBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final McpToolRegistry registry;

    public McpFunctionBridge(McpToolRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
    }

    /**
     * Fetches available MCP tools and converts them to {@link FunctionDefinition}s.
     *
     * @return future completing with the list of function definitions
     */
    public CompletableFuture<List<FunctionDefinition>> buildFunctionDefinitions() {
        return registry.getTools()
                .thenApply(tools -> tools.stream()
                        .map(McpFunctionBridge::toFunctionDefinition)
                        .toList());
    }

    /**
     * Executes an LLM-issued {@link FunctionCall} by dispatching it to the MCP server.
     *
     * @param call the function call from the LLM response
     * @return future completing with an {@link LLMMessage} of role {@code FUNCTION}
     *         containing the tool result, ready to append to the conversation
     */
    public CompletableFuture<LLMMessage> execute(FunctionCall call) {
        Map<String, Object> args = parseArguments(call.arguments());
        return registry.callTool(call.name(), args)
                .thenApply(result -> LLMMessage.function(call.name(), result.content()));
    }

    // --- private helpers ---

    private static FunctionDefinition toFunctionDefinition(McpTool tool) {
        Map<String, Object> parameters = schemaToParameters(tool.inputSchema());
        return new FunctionDefinition(tool.name(), tool.description(), parameters);
    }

    /**
     * Converts a JSON Schema {@link JsonNode} (as stored in {@link McpTool#inputSchema()})
     * to the {@code Map<String, Object>} format expected by {@link FunctionDefinition}.
     *
     * <p>If {@code inputSchema} is null or not an object node, returns null so that
     * {@link FunctionDefinition} is created without parameter constraints.
     */
    static Map<String, Object> schemaToParameters(JsonNode inputSchema) {
        if (inputSchema == null || !inputSchema.isObject()) {
            return null;
        }
        return MAPPER.convertValue(inputSchema, MAP_TYPE);
    }

    /**
     * Parses the JSON arguments string from a {@link FunctionCall} into a map.
     *
     * <p>Returns an empty map on null, blank, or unparseable input — the MCP server
     * will validate required parameters and return an error result if needed.
     */
    static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments.strip())) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(arguments, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}