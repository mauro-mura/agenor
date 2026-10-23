package dev.agenor.adapters.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.FunctionDefinition;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts MCP tools for use in LLM function-calling workflows.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>{@link #buildFunctionDefinitions()} — converts {@link dev.agenor.core.mcp.McpTool}s
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

    private static final Logger log = LoggerFactory.getLogger(McpFunctionAdapter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /** Reads one JSON object and nothing after it: {@code {"a": 1} junk} is not an object. */
    private static final ObjectReader ARGUMENTS_READER = OBJECT_MAPPER.readerFor(MAP_TYPE)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** How much of malformed arguments the error message repeats. */
    private static final int ARGUMENTS_SHOWN = 200;

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
     * <p>A tool whose name no LLM provider accepts (see {@link FunctionDefinition}) is left out,
     * and one WARN per list names each such tool and why. MCP allows names the providers do not,
     * and one of them must not cost the model every other tool.
     *
     * @return future completing with the function definitions (never {@code null})
     */
    public CompletableFuture<List<FunctionDefinition>> buildFunctionDefinitions() {
        return registry.getTools().thenApply(tools -> {
            List<FunctionDefinition> definitions = new ArrayList<>(tools.size());
            List<String> skipped = new ArrayList<>();
            for (McpTool tool : tools) {
                try {
                    definitions.add(toFunctionDefinition(tool));
                } catch (IllegalArgumentException e) {
                    skipped.add(e.getMessage());
                }
            }
            if (!skipped.isEmpty()) {
                log.warn("{} of {} MCP tools left out of the LLM's tool list: {}",
                        skipped.size(), tools.size(), skipped);
            }
            return List.copyOf(definitions);
        });
    }

    /**
     * Executes an LLM function call by dispatching it to the MCP server via the registry,
     * then wraps the result as a {@link LLMMessage} of role {@code FUNCTION}.
     *
     * <p>Arguments that are not one JSON object (an unclosed object, {@code count=1},
     * {@code [1]}) do not reach the tool: the result is a {@code FUNCTION} message saying so,
     * which the model can read and retry, and a WARN. Missing, blank or {@code {}} arguments
     * mean no arguments.
     *
     * @param call the function call requested by the LLM
     * @return future completing with the result message
     */
    public CompletableFuture<LLMMessage> execute(FunctionCall call) {
        Optional<Map<String, Object>> args = parseArguments(call.arguments());
        if (args.isEmpty()) {
            String shown = shorten(call.arguments());
            log.warn("MCP tool '{}' not called: its arguments are not a JSON object: {}", call.name(), shown);
            return CompletableFuture.completedFuture(LLMMessage.function(call,
                    "Error: the arguments for '" + call.name() + "' are not a JSON object, so the tool"
                            + " was not called. Received: " + shown));
        }
        return registry.callTool(call.name(), args.get())
                .thenApply(result -> LLMMessage.function(call, result.content()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private FunctionDefinition toFunctionDefinition(McpTool tool) {
        Map<String, Object> parameters = toParameterMap(tool);
        return new FunctionDefinition(tool.name(), tool.description(), parameters);
    }

    /**
     * Converts the tool's JSON Schema to the {@code Map<String, Object>} format expected by
     * {@link FunctionDefinition}.
     *
     * <p>Returns {@code null} (no parameters) when the schema is null or an empty object.
     *
     * @throws IllegalArgumentException when the schema is not a JSON object. A tool offered
     *         without the parameters it declares would be called with whatever the model guesses.
     */
    private static Map<String, Object> toParameterMap(McpTool tool) {
        JsonNode schema = tool.inputSchema();
        if (schema == null || schema.isNull() || (schema.isObject() && schema.isEmpty())) {
            return null;
        }
        if (!schema.isObject()) {
            throw new IllegalArgumentException("Tool '" + tool.name()
                    + "' has an input schema that is not a JSON object: " + shorten(schema.toString()));
        }
        return OBJECT_MAPPER.convertValue(schema, MAP_TYPE);
    }

    /**
     * Parses the JSON arguments string from a {@link FunctionCall} into a
     * {@code Map<String, Object>}.
     *
     * @return the arguments, empty for null, blank or {@code {}}; no value when they are not
     *         one JSON object
     */
    private static Optional<Map<String, Object>> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments.strip())) {
            return Optional.of(Collections.emptyMap());
        }
        try {
            return Optional.ofNullable(ARGUMENTS_READER.readValue(arguments));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String shorten(String text) {
        return text.length() <= ARGUMENTS_SHOWN ? text : text.substring(0, ARGUMENTS_SHOWN) + "…";
    }
}
