package dev.agenor.adapters.llm;

import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.FunctionDefinition;
import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for converting Agenor function definitions to LangChain4j tool specifications,
 * and Agenor function-calling messages to and from LangChain4j's tool-execution types.
 */
public final class ToolConversionUtils {

    private ToolConversionUtils() {
        // Utility class
    }

    /**
     * Convert list of Agenor FunctionDefinitions to LangChain4j ToolSpecifications.
     */
    public static List<ToolSpecification> convertFunctionsToToolSpecs(List<FunctionDefinition> functions) {
        return functions.stream()
                .map(ToolConversionUtils::convertFunctionToToolSpec)
                .collect(Collectors.toList());
    }

    /**
     * Converts an {@code ASSISTANT} message's {@link FunctionCall}s into the requests LangChain4j
     * attaches to its own {@code AiMessage} — the outbound half of a tool-calling round trip.
     */
    public static List<ToolExecutionRequest> convertFunctionCallsToToolExecutionRequests(
            List<FunctionCall> functionCalls) {
        return functionCalls.stream()
                .map(call -> ToolExecutionRequest.builder()
                        .id(call.id())
                        .name(call.name())
                        .arguments(call.arguments())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Converts a {@code FUNCTION}-role message into the {@code tool_result} LangChain4j expects
     * — the return half of a tool-calling round trip.
     *
     * @param provider the provider name, used only in the exception message
     * @param message  a message with role {@code FUNCTION}
     * @throws LLMException {@code INVALID_REQUEST} if {@code message} carries no
     *         {@link LLMMessage#functionCallId()} — built with the deprecated
     *         {@link LLMMessage#function(String, String)} rather than
     *         {@link LLMMessage#function(FunctionCall, String)} — since a provider cannot pair an
     *         unidentified result to the call it answers
     */
    public static ToolExecutionResultMessage convertFunctionResultToToolExecutionResultMessage(
            String provider, LLMMessage message) {
        if (message.functionCallId() == null) {
            throw LLMException.invalidRequest(provider + ": FUNCTION message for '" + message.name()
                    + "' has no functionCallId — build it with LLMMessage.function(FunctionCall, "
                    + "String), not the deprecated two-argument overload, so the result can be "
                    + "paired to the call it answers");
        }
        return ToolExecutionResultMessage.from(message.functionCallId(), message.name(), message.content());
    }

    /**
     * Convert single FunctionDefinition to ToolSpecification.
     */
    public static ToolSpecification convertFunctionToToolSpec(FunctionDefinition func) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(func.name())
                .description(func.description());

        // Convert parameters from Agenor format to LangChain4j JsonObjectSchema
        if (func.parameters() != null && !func.parameters().isEmpty()) {
            JsonObjectSchema schema = convertParametersToJsonSchema(func.parameters(), func.getRequiredParameters());
            builder.parameters(schema);
        }

        return builder.build();
    }

    /**
     * Convert parameters map to JsonObjectSchema.
     */
    private static JsonObjectSchema convertParametersToJsonSchema(Map<String, Object> params, List<String> requiredParams) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        // Extract properties
        if (params.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            properties.forEach((propName, propDef) -> {
                addPropertyToSchema(schemaBuilder, propName, propDef);
            });
        }

        // Extract required fields
        schemaBuilder.required(requiredParams);
//        if (params.containsKey("required")) {
//            @SuppressWarnings("unchecked")
//            List<String> required = (List<String>) params.get("required");
//            required.forEach(schemaBuilder::required);
//        }

        return schemaBuilder.build();
    }

    /**
     * Add property to schema builder using helper methods.
     */
    private static void addPropertyToSchema(JsonObjectSchema.Builder builder, String name, Object propDef) {
        if (!(propDef instanceof Map)) {
            builder.addStringProperty(name);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> prop = (Map<String, Object>) propDef;

        String type = (String) prop.getOrDefault("type", "string");
        String description = (String) prop.get("description");

        switch (type) {
            case "string" -> {
                if (prop.containsKey("enum")) {
                    @SuppressWarnings("unchecked")
                    List<String> enumValues = (List<String>) prop.get("enum");
                    builder.addEnumProperty(name, enumValues);
                } else if (description != null) {
                    builder.addStringProperty(name, description);
                } else {
                    builder.addStringProperty(name);
                }
            }
            case "integer" -> {
                if (description != null) {
                    builder.addIntegerProperty(name, description);
                } else {
                    builder.addIntegerProperty(name);
                }
            }
            case "number" -> {
                if (description != null) {
                    builder.addNumberProperty(name, description);
                } else {
                    builder.addNumberProperty(name);
                }
            }
            case "boolean" -> {
                if (description != null) {
                    builder.addBooleanProperty(name, description);
                } else {
                    builder.addBooleanProperty(name);
                }
            }
            case "array" -> {
                JsonSchemaElement items = null;
                if (prop.containsKey("items")) {
                    items = convertPropertyToJsonSchema(prop.get("items"));
                }

                if (description != null && items != null) {
                    builder.addProperty(name, JsonArraySchema.builder()
                            .description(description)
                            .items(items)
                            .build());
                } else if (items != null) {
                    builder.addProperty(name, JsonArraySchema.builder()
                            .items(items)
                            .build());
                } else if (description != null) {
                    builder.addProperty(name, JsonArraySchema.builder()
                            .description(description)
                            .build());
                } else {
                    builder.addProperty(name, JsonArraySchema.builder().build());
                }
            }
            case "object" -> {
                JsonObjectSchema objectSchema = buildObjectSchema(prop);
                builder.addProperty(name, objectSchema);
            }
            default -> {
                if (description != null) {
                    builder.addStringProperty(name, description);
                } else {
                    builder.addStringProperty(name);
                }
            }
        }
    }

    /**
     * Convert property definition to JsonSchemaElement (for nested schemas).
     */
    private static JsonSchemaElement convertPropertyToJsonSchema(Object propDef) {
        if (!(propDef instanceof Map)) {
            return JsonStringSchema.builder().build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> prop = (Map<String, Object>) propDef;

        String type = (String) prop.getOrDefault("type", "string");
        String description = (String) prop.get("description");

        return switch (type) {
            case "string" -> JsonStringSchema.builder()
                    .description(description)
                    .build();
            case "integer" -> JsonIntegerSchema.builder()
                    .description(description)
                    .build();
            case "number" -> JsonNumberSchema.builder()
                    .description(description)
                    .build();
            case "boolean" -> JsonBooleanSchema.builder()
                    .description(description)
                    .build();
            case "array" -> buildArraySchema(prop, description);
            case "object" -> buildObjectSchema(prop);
            default -> JsonStringSchema.builder()
                    .description(description)
                    .build();
        };
    }

    private static JsonArraySchema buildArraySchema(Map<String, Object> prop, String description) {
        JsonArraySchema.Builder builder = JsonArraySchema.builder()
                .description(description);

        if (prop.containsKey("items")) {
            JsonSchemaElement items = convertPropertyToJsonSchema(prop.get("items"));
            builder.items(items);
        }

        return builder.build();
    }

    private static JsonObjectSchema buildObjectSchema(Map<String, Object> prop) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        if (prop.containsKey("description")) {
            builder.description((String) prop.get("description"));
        }

        if (prop.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) prop.get("properties");

            properties.forEach((propName, propDef) -> {
                addPropertyToSchema(builder, propName, propDef);
            });
        }

        if (prop.containsKey("required")) {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) prop.get("required");
            required.forEach(builder::required);
        }

        return builder.build();
    }
}
