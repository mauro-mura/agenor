package dev.agenor.core.llm;

import java.util.Map;
import java.util.Objects;

/**
 * Defines a function that can be called by an LLM.
 *
 * <p>Function calling (also known as tool use) allows LLMs to request the
 * execution of external functions to gather information or perform actions.
 * This class describes the function's name, purpose, and parameters using
 * JSON Schema.
 *
 * <p>Example usage:
 * <pre>{@code
 * FunctionDefinition weatherFunction = FunctionDefinition.builder("get_weather")
 *     .description("Get the current weather for a location")
 *     .parameter("location", "string", "The city and state, e.g. San Francisco, CA", true)
 *     .parameter("unit", "string", "Temperature unit (celsius or fahrenheit)", false)
 *     .build();
 * }</pre>
 *
 * @param name the name of the function
 * @param description a description of what the function does
 * @param parameters the JSON Schema defining the function's parameters
 *
 * @since 0.3.0
 */
public record FunctionDefinition(
    String name,
    String description,
    Map<String, Object> parameters
) {

    /**
     * Compact constructor with validation and defensive copying.
     */
    public FunctionDefinition {
        Objects.requireNonNull(name, "Function name cannot be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Function name cannot be blank");
        }

        // Name should follow typical programming language identifier rules
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException(
                "Function name must start with letter or underscore and contain only letters, numbers, and underscores"
            );
        }

        if (parameters != null) {
            parameters = Map.copyOf(parameters);
        }
    }

    /**
     * Create a new builder.
     *
     * @param name the function name
     * @return a new builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Builder for creating FunctionDefinition instances.
     */
    public static class Builder {
        private final String name;
        private String description;
        private final Map<String, Object> properties = new java.util.HashMap<>();
        private final java.util.List<String> required = new java.util.ArrayList<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /**
         * Set the function description.
         *
         * <p>Should clearly explain what the function does so the LLM knows
         * when to use it.
         *
         * @param description the function description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Add a parameter to the function.
         *
         * @param name the parameter name
         * @param type the parameter type (string, number, boolean, object, array)
         * @param description description of the parameter
         * @param required whether this parameter is required
         * @return this builder
         */
        public Builder parameter(String name, String type, String description, boolean required) {
            Map<String, Object> paramDef = new java.util.HashMap<>();
            paramDef.put("type", type);
            if (description != null) {
                paramDef.put("description", description);
            }
            properties.put(name, paramDef);

            if (required) {
                this.required.add(name);
            }

            return this;
        }

        /**
         * Add a string parameter.
         *
         * @param name parameter name
         * @param description parameter description
         * @param required whether required
         * @return this builder
         */
        public Builder stringParameter(String name, String description, boolean required) {
            return parameter(name, "string", description, required);
        }

        /**
         * Add a number parameter.
         *
         * @param name parameter name
         * @param description parameter description
         * @param required whether required
         * @return this builder
         */
        public Builder numberParameter(String name, String description, boolean required) {
            return parameter(name, "number", description, required);
        }

        /**
         * Add a boolean parameter.
         *
         * @param name parameter name
         * @param description parameter description
         * @param required whether required
         * @return this builder
         */
        public Builder booleanParameter(String name, String description, boolean required) {
            return parameter(name, "boolean", description, required);
        }

        /**
         * Add an object parameter with custom schema.
         *
         * @param name parameter name
         * @param schema the JSON schema for this object
         * @param description parameter description
         * @param required whether required
         * @return this builder
         */
        public Builder objectParameter(String name, Map<String, Object> schema,
                                     String description, boolean required) {
            Map<String, Object> paramDef = new java.util.HashMap<>(schema);
            if (description != null) {
                paramDef.put("description", description);
            }
            properties.put(name, paramDef);

            if (required) {
                this.required.add(name);
            }

            return this;
        }

        /**
         * Add an enum parameter (string with specific allowed values).
         *
         * @param name parameter name
         * @param description parameter description
         * @param required whether required
         * @param enumValues allowed values
         * @return this builder
         */
        public Builder enumParameter(String name, String description,
                                    boolean required, String... enumValues) {
            Map<String, Object> paramDef = new java.util.HashMap<>();
            paramDef.put("type", "string");
            if (description != null) {
                paramDef.put("description", description);
            }
            paramDef.put("enum", java.util.List.of(enumValues));
            properties.put(name, paramDef);

            if (required) {
                this.required.add(name);
            }

            return this;
        }

        /**
         * Build the function definition.
         *
         * @return the constructed function definition
         */
        public FunctionDefinition build() {
            // Build JSON Schema for parameters
            Map<String, Object> parameters = new java.util.HashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", Map.copyOf(properties));

            if (!required.isEmpty()) {
                parameters.put("required", java.util.List.copyOf(required));
            }

            return new FunctionDefinition(name, description, parameters);
        }
    }

    /**
     * Get the parameter schema.
     *
     * @return the JSON Schema for parameters
     */
    public Map<String, Object> getParameterSchema() {
        return parameters;
    }

    /**
     * Get the list of required parameters.
     *
     * @return list of required parameter names, or empty list if none
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> getRequiredParameters() {
        if (parameters == null) {
            return java.util.List.of();
        }
        Object required = parameters.get("required");
        if (required instanceof java.util.List) {
            return (java.util.List<String>) required;
        }
        return java.util.List.of();
    }

    /**
     * Check if a parameter is required.
     *
     * @param parameterName the parameter name to check
     * @return true if the parameter is required
     */
    public boolean isParameterRequired(String parameterName) {
        return getRequiredParameters().contains(parameterName);
    }

    /**
     * Get the properties definition.
     *
     * @return map of parameter properties
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProperties() {
        if (parameters == null) {
            return Map.of();
        }
        Object props = parameters.get("properties");
        if (props instanceof Map) {
            return (Map<String, Object>) props;
        }
        return Map.of();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FunctionDefinition{");
        sb.append("name='").append(name).append('\'');
        if (description != null) {
            sb.append(", description='").append(description).append('\'');
        }
        sb.append(", parameters=").append(getRequiredParameters().size())
          .append(" required");
        sb.append('}');
        return sb.toString();
    }
}
