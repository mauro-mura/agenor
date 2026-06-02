package dev.agenor.runtime.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.OutputGuardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates LLM output against a JSON Schema definition using Jackson.
 *
 * <p>Supports a subset of JSON Schema Draft 7 sufficient for practical LLM output
 * validation without requiring external schema-validator libraries:
 * <ul>
 *   <li>{@code type} — {@code object}, {@code array}, {@code string}, {@code number},
 *       {@code integer}, {@code boolean}, {@code null}</li>
 *   <li>{@code required} — list of mandatory property names on an object</li>
 *   <li>{@code properties} — nested per-property schema validation (recursive)</li>
 * </ul>
 *
 * <p><b>Execution semantics</b></p>
 * <ol>
 *   <li>If output is not parseable as JSON → {@link GuardrailResult.Blocked} immediately.</li>
 *   <li>If output is valid JSON and conforms to the schema → {@link GuardrailResult.Passed}.</li>
 *   <li>If output is valid JSON but violates the schema and attempts remaining:
 *       {@link GuardrailResult.Modified} with a re-prompt instruction.</li>
 *   <li>If output violates the schema after exhausting all attempts:
 *       {@link GuardrailResult.Blocked}.</li>
 * </ol>
 *
 * <p>The attempt counter is per-instance and is reset by calling {@link #resetAttempts()}.
 * Each invocation of {@link #apply} that returns {@link GuardrailResult.Modified} increments
 * the counter; once {@code maxRepromptAttempts} is reached, subsequent violations are
 * {@link GuardrailResult.Blocked}.
 *
 * <p>Example:
 * <pre>{@code
 * String schema = """
 *     {
 *       "type": "object",
 *       "required": ["name", "score"],
 *       "properties": {
 *         "name":  { "type": "string" },
 *         "score": { "type": "number" }
 *       }
 *     }
 *     """;
 *
 * OutputGuardrail guardrail = new JsonSchemaOutputGuardrail(schema);
 * }</pre>
 *
 * @since 0.13.0
 */
public class JsonSchemaOutputGuardrail implements OutputGuardrail {

    static final int DEFAULT_MAX_REPROMPT_ATTEMPTS = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode schema;
    private final int maxRepromptAttempts;
    private final AtomicInteger attemptCount = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a guardrail with the given JSON Schema and default
     * {@link #DEFAULT_MAX_REPROMPT_ATTEMPTS} re-prompt attempts.
     *
     * @param schemaJson JSON Schema as a JSON string; never {@code null}
     * @throws IllegalArgumentException if {@code schemaJson} is not valid JSON
     */
    public JsonSchemaOutputGuardrail(String schemaJson) {
        this(schemaJson, DEFAULT_MAX_REPROMPT_ATTEMPTS);
    }

    /**
     * Creates a guardrail with the given JSON Schema and explicit re-prompt cap.
     *
     * @param schemaJson          JSON Schema as a JSON string; never {@code null}
     * @param maxRepromptAttempts maximum number of times a {@link GuardrailResult.Modified}
     *                            (re-prompt instruction) is returned before switching to
     *                            {@link GuardrailResult.Blocked}; must be ≥ 0
     */
    public JsonSchemaOutputGuardrail(String schemaJson, int maxRepromptAttempts) {
        Objects.requireNonNull(schemaJson, "schemaJson must not be null");
        if (maxRepromptAttempts < 0) {
            throw new IllegalArgumentException("maxRepromptAttempts must be >= 0");
        }
        this.maxRepromptAttempts = maxRepromptAttempts;
        try {
            this.schema = MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON Schema: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // OutputGuardrail
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<GuardrailResult> apply(String output, GuardrailContext ctx) {
        return CompletableFuture.completedFuture(evaluate(output));
    }

    // -------------------------------------------------------------------------
    // Core evaluation
    // -------------------------------------------------------------------------

    GuardrailResult evaluate(String output) {
        // Step 1: parse JSON
        JsonNode node;
        try {
            node = MAPPER.readTree(output);
        } catch (Exception e) {
            return new GuardrailResult.Blocked("Output is not valid JSON: " + e.getMessage());
        }

        if (node == null || node.isMissingNode() || (node.isNull() && !allowsNull(schema))) {
            return new GuardrailResult.Blocked("Output is not valid JSON");
        }

        // Step 2: validate against schema
        List<String> violations = new ArrayList<>();
        validate(node, schema, "$", violations);

        if (violations.isEmpty()) {
            return new GuardrailResult.Passed();
        }

        // Step 3: reprompt or block
        int used = attemptCount.getAndIncrement();
        if (used < maxRepromptAttempts) {
            return new GuardrailResult.Modified(buildRepromptInstruction(violations));
        }

        return new GuardrailResult.Blocked(
                "Output does not conform to schema after " + (used + 1) + " attempt(s): "
                + String.join("; ", violations));
    }

    /**
     * Resets the internal re-prompt attempt counter.
     * Call this when starting a new agent invocation to allow re-prompting again.
     */
    public void resetAttempts() {
        attemptCount.set(0);
    }

    int currentAttempts() {
        return attemptCount.get();
    }

    // -------------------------------------------------------------------------
    // Schema validation (JSON Schema Draft 7 subset)
    // -------------------------------------------------------------------------

    private static void validate(JsonNode node, JsonNode schema, String path, List<String> violations) {
        if (schema == null || !schema.isObject()) return;

        // --- type ---
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            String expectedType = typeNode.asText();
            if (!matchesType(node, expectedType)) {
                violations.add(path + ": expected type '" + expectedType
                        + "' but got " + jsonTypeName(node));
                return; // no point validating properties if type is wrong
            }
        }

        // --- required ---
        JsonNode required = schema.get("required");
        if (required != null && required.isArray() && node.isObject()) {
            for (JsonNode req : required) {
                String fieldName = req.asText();
                if (!node.has(fieldName)) {
                    violations.add(path + ": missing required property '" + fieldName + "'");
                }
            }
        }

        // --- properties (recursive) ---
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject() && node.isObject()) {
            properties.properties().iterator().forEachRemaining(entry -> {
                String propName = entry.getKey();
                JsonNode propSchema = entry.getValue();
                JsonNode propValue = node.get(propName);
                if (propValue != null) {
                    validate(propValue, propSchema, path + "." + propName, violations);
                }
            });
        }

        // --- items (array element schema) ---
        JsonNode items = schema.get("items");
        if (items != null && node.isArray()) {
            int idx = 0;
            for (JsonNode element : node) {
                validate(element, items, path + "[" + idx++ + "]", violations);
            }
        }
    }

    private static boolean matchesType(JsonNode node, String type) {
        return switch (type) {
            case "object"  -> node.isObject();
            case "array"   -> node.isArray();
            case "string"  -> node.isTextual();
            case "number"  -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "null"    -> node.isNull();
            default        -> true; // unknown type → no constraint
        };
    }

    private static String jsonTypeName(JsonNode node) {
        if (node.isObject())          return "object";
        if (node.isArray())           return "array";
        if (node.isTextual())         return "string";
        if (node.isIntegralNumber())  return "integer";
        if (node.isNumber())          return "number";
        if (node.isBoolean())         return "boolean";
        if (node.isNull())            return "null";
        return "unknown";
    }

    private static boolean allowsNull(JsonNode schema) {
        JsonNode typeNode = schema.get("type");
        return typeNode != null && "null".equals(typeNode.asText());
    }

    // -------------------------------------------------------------------------
    // Re-prompt instruction builder
    // -------------------------------------------------------------------------

    private static String buildRepromptInstruction(List<String> violations) {
        return "Your previous response did not conform to the required JSON schema. "
                + "Please provide a valid JSON response that fixes the following issues: "
                + String.join("; ", violations)
                + ". Return only the JSON object with no additional text or markdown fences.";
    }
}
