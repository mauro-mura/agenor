package dev.agenor.core.llm;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a function call requested by an LLM.
 *
 * <p>When an LLM determines it needs to call a function to gather information
 * or perform an action, it returns a FunctionCall object containing the
 * function name and arguments.
 *
 * <p>The arguments are typically provided as a JSON string that can be parsed
 * into the appropriate parameter types.
 *
 * <p>Example usage:
 * <pre>{@code
 * // LLM response contains a function call
 * if (response.hasFunctionCalls()) {
 *     FunctionCall call = response.functionCalls().get(0);
 *     String functionName = call.name();
 *     Map<String, Object> args = parseArguments(call.arguments());
 *
 *     // Execute the function
 *     String result = executeFunction(functionName, args);
 *
 *     // Send result back to LLM
 *     request = request.withFunctionResult(functionName, result);
 * }
 * }</pre>
 *
 * @param id the unique identifier for this function call
 * @param name the name of the function to be called
 * @param arguments the arguments for the function as a JSON string
 *
 * @since 0.3.0
 */
public record FunctionCall(
    String id,
    String name,
    String arguments
) {

    /**
     * Compact constructor with validation.
     */
    public FunctionCall {
        Objects.requireNonNull(name, "Function name cannot be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Function name cannot be blank");
        }

        // Arguments can be null for functions with no parameters
        if (arguments != null && arguments.isBlank()) {
            arguments = "{}";  // Normalize empty to empty JSON object
        }
    }

    /**
     * Create a function call with generated ID.
     *
     * @param name the function name
     * @param arguments the arguments as JSON string
     * @return a new function call
     */
    public static FunctionCall of(String name, String arguments) {
        String id = "call_" + java.util.UUID.randomUUID().toString().replace("-", "");
        return new FunctionCall(id, name, arguments);
    }

    /**
     * Create a function call with no arguments.
     *
     * @param name the function name
     * @return a new function call with empty arguments
     */
    public static FunctionCall of(String name) {
        return of(name, "{}");
    }

    /**
     * Parse the arguments JSON string into a Map.
     *
     * <p>This is a convenience method for parsing the arguments. In practice,
     * you'll likely want to use a proper JSON library like Jackson or Gson.
     *
     * @return the arguments as a map, or empty map if parsing fails
     */
    public Map<String, Object> parseArguments() {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }

        // This is a simple implementation - in real usage you'd use Jackson/Gson
        try {
            return parseSimpleJson(arguments);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Simple JSON parser for basic cases.
     *
     * <p>Note: This is a simplified parser for demonstration. In production,
     * use a proper JSON library like Jackson.
     */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new java.util.HashMap<>();

        if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) {
            return result;
        }

        // Remove outer braces and whitespace
        String content = json.trim();
        if (content.startsWith("{") && content.endsWith("}")) {
            content = content.substring(1, content.length() - 1).trim();
        }

        if (content.isEmpty()) {
            return result;
        }

        // Split by commas (naive - doesn't handle nested objects/arrays properly)
        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replaceAll("^\"|\"$", "");
                String value = keyValue[1].trim();

                // Parse value type
                Object parsedValue;
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    parsedValue = value.substring(1, value.length() - 1);
                } else if ("true".equalsIgnoreCase(value)) {
                    parsedValue = true;
                } else if ("false".equalsIgnoreCase(value)) {
                    parsedValue = false;
                } else if ("null".equalsIgnoreCase(value)) {
                    parsedValue = null;
                } else {
                    try {
                        if (value.contains(".")) {
                            parsedValue = Double.parseDouble(value);
                        } else {
                            parsedValue = Integer.parseInt(value);
                        }
                    } catch (NumberFormatException e) {
                        parsedValue = value;
                    }
                }

                result.put(key, parsedValue);
            }
        }

        return result;
    }

    /**
     * Check if this function call has arguments.
     *
     * @return true if arguments are present and non-empty
     */
    public boolean hasArguments() {
        return arguments != null && !arguments.isBlank() && !"{}".equals(arguments.trim());
    }

    /**
     * Get a specific argument value.
     *
     * @param <T> the expected type
     * @param key the argument key
     * @param type the expected class type
     * @return the argument value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getArgument(String key, Class<T> type) {
        Map<String, Object> args = parseArguments();
        Object value = args.get(key);

        if (value == null) {
            return null;
        }

        if (type.isInstance(value)) {
            return (T) value;
        }

        // Try some basic conversions
        if (type == String.class) {
            return (T) value.toString();
        }
        if (type == Integer.class && value instanceof Number) {
            return (T) Integer.valueOf(((Number) value).intValue());
        }
        if (type == Double.class && value instanceof Number) {
            return (T) Double.valueOf(((Number) value).doubleValue());
        }
        if (type == Boolean.class) {
            return (T) Boolean.valueOf(value.toString());
        }

        return null;
    }

    /**
     * Get a string argument.
     *
     * @param key the argument key
     * @return the argument value as string, or null if not found
     */
    public String getStringArgument(String key) {
        return getArgument(key, String.class);
    }

    /**
     * Get an integer argument.
     *
     * @param key the argument key
     * @return the argument value as integer, or null if not found
     */
    public Integer getIntArgument(String key) {
        return getArgument(key, Integer.class);
    }

    /**
     * Get a double argument.
     *
     * @param key the argument key
     * @return the argument value as double, or null if not found
     */
    public Double getDoubleArgument(String key) {
        return getArgument(key, Double.class);
    }

    /**
     * Get a boolean argument.
     *
     * @param key the argument key
     * @return the argument value as boolean, or null if not found
     */
    public Boolean getBooleanArgument(String key) {
        return getArgument(key, Boolean.class);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FunctionCall{");
        if (id != null) {
            sb.append("id='").append(id).append("', ");
        }
        sb.append("name='").append(name).append('\'');
        if (hasArguments()) {
            sb.append(", arguments='").append(arguments).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
