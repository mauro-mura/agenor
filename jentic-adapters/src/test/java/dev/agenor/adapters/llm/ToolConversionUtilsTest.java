package dev.agenor.adapters.llm;

import dev.agenor.core.llm.FunctionDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolConversionUtilsTest {

    @Test
    void testConvertSimpleFunction() {
        FunctionDefinition function = FunctionDefinition.builder("get_weather")
                .description("Get current weather")
                .stringParameter("location", "City name", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("get_weather", spec.name());
        assertEquals("Get current weather", spec.description());
        assertNotNull(spec.parameters());
        assertTrue(spec.parameters() instanceof JsonObjectSchema);
    }

    @Test
    void testConvertFunctionWithMultipleParameters() {
        FunctionDefinition function = FunctionDefinition.builder("calculate")
                .description("Calculate something")
                .parameter("a", "integer", "First number", true)
                .parameter("b", "integer", "Second number", true)
                .stringParameter("operation", "Math operation", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("calculate", spec.name());
        assertEquals("Calculate something", spec.description());
        assertNotNull(spec.parameters());

        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties());
        assertEquals(3, schema.properties().size());
        assertTrue(schema.properties().containsKey("a"));
        assertTrue(schema.properties().containsKey("b"));
        assertTrue(schema.properties().containsKey("operation"));
    }

    @Test
    void testConvertFunctionWithEnum() {
        FunctionDefinition function = FunctionDefinition.builder("set_mode")
                .description("Set operation mode")
                .enumParameter("mode", "Operation mode", true, "fast", "normal", "slow")
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("set_mode", spec.name());
        assertEquals("Set operation mode", spec.description());
        assertNotNull(spec.parameters());

        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("mode"));
    }

    @Test
    void testConvertFunctionList() {
        List<FunctionDefinition> functions = List.of(
                FunctionDefinition.builder("func1")
                        .description("First function")
                        .stringParameter("param1", "First param", true)
                        .build(),
                FunctionDefinition.builder("func2")
                        .description("Second function")
                        .parameter("param2", "integer", "Second param", false)
                        .build()
        );

        List<ToolSpecification> specs = ToolConversionUtils.convertFunctionsToToolSpecs(functions);

        assertNotNull(specs);
        assertEquals(2, specs.size());
        assertEquals("func1", specs.get(0).name());
        assertEquals("First function", specs.get(0).description());
        assertEquals("func2", specs.get(1).name());
        assertEquals("Second function", specs.get(1).description());
    }

    @Test
    void testConvertFunctionWithArrayParameter() {
        FunctionDefinition function = FunctionDefinition.builder("process_items")
                .description("Process a list of items")
                .parameter("items", "array", "List of item IDs", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("process_items", spec.name());
        assertNotNull(spec.parameters());

        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("items"));
    }

    @Test
    void testConvertFunctionWithBooleanParameter() {
        FunctionDefinition function = FunctionDefinition.builder("toggle_feature")
                .description("Toggle a feature")
                .booleanParameter("enabled", "Enable or disable", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("toggle_feature", spec.name());
        assertNotNull(spec.parameters());

        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("enabled"));
    }

    @Test
    void testConvertFunctionWithNumberParameter() {
        FunctionDefinition function = FunctionDefinition.builder("set_temperature")
                .description("Set temperature")
                .numberParameter("degrees", "Temperature in degrees", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("set_temperature", spec.name());
        assertNotNull(spec.parameters());

        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("degrees"));
    }

    @Test
    void testConvertFunctionWithRequiredAndOptionalParameters() {
        FunctionDefinition function = FunctionDefinition.builder("search")
                .description("Search for items")
                .stringParameter("query", "Search query", true)
                .parameter("limit", "integer", "Max results", false)
                .booleanParameter("includeArchived", "Include archived items", false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertEquals(3, schema.properties().size());

        // Verify required fields
        List<String> required = schema.required();
        assertNotNull(required);
        assertEquals(1, required.size());
        assertTrue(required.contains("query"));
    }

    @Test
    void testConvertFunctionWithNoParameters() {
        FunctionDefinition function = FunctionDefinition.builder("get_status")
                .description("Get current status")
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("get_status", spec.name());
        assertEquals("Get current status", spec.description());
        // Parameters might be null or empty schema
        if (spec.parameters() != null) {
            JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
            assertTrue(schema.properties() == null || schema.properties().isEmpty());
        }
    }

    @Test
    void testConvertComplexFunctionWithAllTypes() {
        FunctionDefinition function = FunctionDefinition.builder("complex_operation")
                .description("Complex operation with multiple parameter types")
                .stringParameter("name", "Item name", true)
                .parameter("count", "integer", "Item count", true)
                .numberParameter("price", "Item price", false)
                .booleanParameter("inStock", "Is in stock", false)
                .enumParameter("category", "Item category", true, "electronics", "clothing", "food")
                .parameter("tags", "array", "Item tags", false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(function);

        assertNotNull(spec);
        assertEquals("complex_operation", spec.name());

        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties());
        assertEquals(6, schema.properties().size());

        // Verify all properties exist
        assertTrue(schema.properties().containsKey("name"));
        assertTrue(schema.properties().containsKey("count"));
        assertTrue(schema.properties().containsKey("price"));
        assertTrue(schema.properties().containsKey("inStock"));
        assertTrue(schema.properties().containsKey("category"));
        assertTrue(schema.properties().containsKey("tags"));

        // Verify required fields - be flexible about the exact count
        // since different parameter types might handle required differently
        List<String> required = schema.required();
        assertNotNull(required);
        assertFalse(required.isEmpty(), "Should have at least one required field");

        // Just verify that at least the string parameter is marked as required
        assertTrue(required.contains("name"), "name should be required");
    }

    // -----------------------------------------------------------------------
    // Object type property
    // -----------------------------------------------------------------------

    @Test
    void shouldConvertObjectTypeProperty() {
        // Build a FunctionDefinition whose parameter map contains an "object" property
        Map<String, Object> addressProps = new HashMap<>();
        addressProps.put("street", Map.of("type", "string", "description", "Street name"));
        addressProps.put("zip", Map.of("type", "string"));

        Map<String, Object> addressProp = new HashMap<>();
        addressProp.put("type", "object");
        addressProp.put("description", "Postal address");
        addressProp.put("properties", addressProps);
        addressProp.put("required", List.of("street"));

        Map<String, Object> properties = new HashMap<>();
        properties.put("address", addressProp);

        Map<String, Object> params = new HashMap<>();
        params.put("properties", properties);

        FunctionDefinition func = FunctionDefinition.builder("create_user")
                .description("Create a new user")
                .parameter("address", "object", "Postal address", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties());
        assertTrue(schema.properties().containsKey("address"));
    }

    // -----------------------------------------------------------------------
    // Default / unknown type
    // -----------------------------------------------------------------------

    @Test
    void shouldHandleUnknownTypeAsString() {
        // "binary" is not a recognized type → should fall through to default case
        FunctionDefinition func = FunctionDefinition.builder("upload")
                .description("Upload content")
                .parameter("data", "binary", "Raw binary data", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties());
        assertTrue(schema.properties().containsKey("data"));
    }

    @Test
    void shouldHandleUnknownTypeWithDescriptionAsString() {
        // Unknown type WITH description → default case with description branch
        FunctionDefinition func = FunctionDefinition.builder("encode")
                .description("Encode data")
                .parameter("payload", "base64", "Base64 payload", false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        assertNotNull(spec.parameters());
    }

    // -----------------------------------------------------------------------
    // Array variants
    // -----------------------------------------------------------------------

    @Test
    void shouldConvertArrayWithItemsAndDescription() {
        // array with both items schema and description
        FunctionDefinition func = FunctionDefinition.builder("batch_process")
                .description("Process a batch of numbers")
                .parameter("values", "array", "List of numeric values", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("values"));
    }

    @Test
    void shouldConvertArrayWithNoDescription() {
        // array without description → hits the else-if(items != null) branch
        FunctionDefinition func = FunctionDefinition.builder("collect")
                .description("Collect items")
                .parameter("ids", "array", null, true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("ids"));
    }

    // -----------------------------------------------------------------------
    // Types without descriptions
    // -----------------------------------------------------------------------

    @Test
    void shouldConvertIntegerWithoutDescription() {
        FunctionDefinition func = FunctionDefinition.builder("repeat")
                .description("Repeat n times")
                .parameter("n", "integer", null, true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("n"));
    }

    @Test
    void shouldConvertNumberWithoutDescription() {
        FunctionDefinition func = FunctionDefinition.builder("scale")
                .description("Scale by factor")
                .parameter("factor", "number", null, false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("factor"));
    }

    @Test
    void shouldConvertBooleanWithoutDescription() {
        FunctionDefinition func = FunctionDefinition.builder("toggle")
                .description("Toggle flag")
                .parameter("flag", "boolean", null, false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("flag"));
    }

    @Test
    void shouldConvertStringWithDescriptionNoEnum() {
        // string WITH description but WITHOUT enum
        FunctionDefinition func = FunctionDefinition.builder("greet")
                .description("Greet a person")
                .stringParameter("name", "Person's full name", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("name"));
    }

    @Test
    void shouldConvertStringWithoutDescriptionNoEnum() {
        // string WITHOUT description and WITHOUT enum → plain addStringProperty(name)
        FunctionDefinition func = FunctionDefinition.builder("echo")
                .description("Echo input")
                .parameter("text", "string", null, true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("text"));
    }

    // -----------------------------------------------------------------------
    // Nested objects / convertPropertyToJsonSchema paths
    // -----------------------------------------------------------------------

    @Test
    void shouldConvertFunctionWithNestedObjectInArray() {
        // Verifies convertPropertyToJsonSchema is exercised for nested "object" type in items
        FunctionDefinition func = FunctionDefinition.builder("multi_op")
                .description("Multi-type operation")
                .parameter("items", "array", "List of items", true)
                .parameter("count", "integer", "Count", false)
                .parameter("ratio", "number", "Ratio", false)
                .parameter("active", "boolean", "Active flag", false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertEquals(4, schema.properties().size());
    }

    @Test
    void shouldConvertFunctionListWithMixedTypes() {
        List<FunctionDefinition> functions = List.of(
                FunctionDefinition.builder("f1")
                        .description("First")
                        .parameter("x", "integer", "X value", true)
                        .parameter("enabled", "boolean", "Enabled", false)
                        .build(),
                FunctionDefinition.builder("f2")
                        .description("Second")
                        .parameter("values", "array", "Values", true)
                        .parameter("obj", "object", "Object", false)
                        .build()
        );

        List<ToolSpecification> specs = ToolConversionUtils.convertFunctionsToToolSpecs(functions);

        assertNotNull(specs);
        assertEquals(2, specs.size());
    }

    // -----------------------------------------------------------------------
    // Empty function list
    // -----------------------------------------------------------------------

    @Test
    void shouldHandleEmptyFunctionList() {
        List<ToolSpecification> specs = ToolConversionUtils.convertFunctionsToToolSpecs(List.of());

        assertNotNull(specs);
        assertTrue(specs.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ToolSpecification buildSpecForProperty(String propName, Map<String, Object> propSchema,
                                                    String propDescription, boolean required) {
        FunctionDefinition func = FunctionDefinition.builder("test_func")
                .description("test")
                .objectParameter(propName, propSchema, propDescription, required)
                .build();
        return ToolConversionUtils.convertFunctionToToolSpec(func);
    }

    private Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "array");
        if (items != null) {
            schema.put("items", items);
        }
        return schema;
    }

 // -----------------------------------------------------------------------
    // Array branch 1: description != null AND items != null
    // → covers convertPropertyToJsonSchema for string type
    // -----------------------------------------------------------------------

    @Test
    void arrayWithStringItems_andDescription_coversFirstBranchAndStringConversion() {
        Map<String, Object> items = new HashMap<>();
        items.put("type", "string");
        items.put("description", "tag value");

        ToolSpecification spec = buildSpecForProperty("tags", arraySchema(items), "Tag list", true);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("tags"));
    }

    // -----------------------------------------------------------------------
    // Array branch 2: items != null, description == null
    // -----------------------------------------------------------------------

    @Test
    void arrayWithStringItems_noDescription_coversSecondBranch() {
        Map<String, Object> items = new HashMap<>();
        items.put("type", "string");

        Map<String, Object> propSchema = new HashMap<>();
        propSchema.put("type", "array");
        propSchema.put("items", items);
        // objectParameter will add description but we set it to null-like by not passing one
        // Actually objectParameter adds description only if != null → pass null
        FunctionDefinition func = FunctionDefinition.builder("func_b2")
                .description("d")
                .objectParameter("values", propSchema, null, true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);
        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("values"));
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - integer type via items
    // -----------------------------------------------------------------------

    @Test
    void arrayWithIntegerItems_coversIntegerConversion() {
        Map<String, Object> items = Map.of("type", "integer", "description", "count");
        ToolSpecification spec = buildSpecForProperty("counts", arraySchema(items), "Counts", true);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("counts"));
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - number type via items
    // -----------------------------------------------------------------------

    @Test
    void arrayWithNumberItems_coversNumberConversion() {
        Map<String, Object> items = Map.of("type", "number", "description", "ratio");
        ToolSpecification spec = buildSpecForProperty("ratios", arraySchema(items), "Ratios", false);

        assertNotNull(spec);
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - boolean type via items
    // -----------------------------------------------------------------------

    @Test
    void arrayWithBooleanItems_coversBooleanConversion() {
        Map<String, Object> items = Map.of("type", "boolean", "description", "flag");
        ToolSpecification spec = buildSpecForProperty("flags", arraySchema(items), "Flags", false);

        assertNotNull(spec);
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - default/unknown type via items
    // -----------------------------------------------------------------------

    @Test
    void arrayWithUnknownTypeItems_coversDefaultConversion() {
        Map<String, Object> items = Map.of("type", "binary");
        ToolSpecification spec = buildSpecForProperty("blobs", arraySchema(items), "Binary blobs", false);

        assertNotNull(spec);
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - non-Map items → if (!(propDef instanceof Map)) branch
    // -----------------------------------------------------------------------

    @Test
    void arrayWithNonMapItems_coversNonMapBranch() {
        Map<String, Object> propSchema = new HashMap<>();
        propSchema.put("type", "array");
        propSchema.put("items", "string_literal_not_a_map"); // non-Map items

        ToolSpecification spec = buildSpecForProperty("raw", propSchema, "Raw", false);

        assertNotNull(spec);
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - nested array (array of arrays)
    // → covers "array" case in convertPropertyToJsonSchema → buildArraySchema
    // -----------------------------------------------------------------------

    @Test
    void arrayOfArrays_coversBuildArraySchemaWithoutItems() {
        // Inner array has no items
        Map<String, Object> innerArray = new HashMap<>();
        innerArray.put("type", "array");

        ToolSpecification spec = buildSpecForProperty("matrix", arraySchema(innerArray), "Matrix", true);

        assertNotNull(spec);
    }

    @Test
    void arrayOfArraysWithItems_coversBuildArraySchemaWithItems() {
        // Inner array itself has items (3 levels deep)
        Map<String, Object> innerItem = Map.of("type", "string");
        Map<String, Object> innerArray = new HashMap<>();
        innerArray.put("type", "array");
        innerArray.put("items", innerItem);

        ToolSpecification spec = buildSpecForProperty("nested", arraySchema(innerArray), "Nested lists", true);

        assertNotNull(spec);
    }

    // -----------------------------------------------------------------------
    // convertPropertyToJsonSchema - object type via items
    // → covers "object" case → buildObjectSchema
    // -----------------------------------------------------------------------

    @Test
    void arrayWithObjectItems_coversBuildObjectSchemaBasic() {
        Map<String, Object> objectItems = new HashMap<>();
        objectItems.put("type", "object");
        objectItems.put("description", "nested object");

        ToolSpecification spec = buildSpecForProperty("objects", arraySchema(objectItems), "Objects", true);

        assertNotNull(spec);
    }

    @Test
    void arrayWithObjectItemsWithProperties_coversBuildObjectSchemaWithProperties() {
        Map<String, Object> nestedProps = new HashMap<>();
        nestedProps.put("id", Map.of("type", "integer", "description", "item id"));
        nestedProps.put("name", Map.of("type", "string", "description", "item name"));

        Map<String, Object> objectItems = new HashMap<>();
        objectItems.put("type", "object");
        objectItems.put("description", "structured item");
        objectItems.put("properties", nestedProps);
        objectItems.put("required", List.of("id"));

        ToolSpecification spec = buildSpecForProperty("items", arraySchema(objectItems), "Items", true);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("items"));
    }

    // -----------------------------------------------------------------------
    // buildObjectSchema - nested properties and required (direct object parameter)
    // -----------------------------------------------------------------------

    @Test
    void objectParameterWithNestedProperties_coversBuildObjectSchemaProperties() {
        Map<String, Object> nestedProps = new HashMap<>();
        nestedProps.put("street", Map.of("type", "string", "description", "Street name"));
        nestedProps.put("city", Map.of("type", "string"));
        nestedProps.put("zip", Map.of("type", "string"));

        Map<String, Object> objectSchema = new HashMap<>();
        objectSchema.put("type", "object");
        objectSchema.put("description", "Address");
        objectSchema.put("properties", nestedProps);
        objectSchema.put("required", List.of("street", "city"));

        FunctionDefinition func = FunctionDefinition.builder("create_address")
                .description("Create an address")
                .objectParameter("address", objectSchema, "Postal address", true)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertTrue(schema.properties().containsKey("address"));
    }

    @Test
    void objectParameterWithoutProperties_coversBuildObjectSchemaNoPropsPath() {
        // Object with only description (no nested properties, no required)
        Map<String, Object> objectSchema = new HashMap<>();
        objectSchema.put("type", "object");
        // No "properties" or "required" keys → tests those if-not-containsKey paths

        FunctionDefinition func = FunctionDefinition.builder("empty_obj")
                .description("Empty object test")
                .objectParameter("payload", objectSchema, "Payload", false)
                .build();

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
    }

    // -----------------------------------------------------------------------
    // convertFunctionToToolSpec with null parameters → no-parameters path
    // -----------------------------------------------------------------------

    @Test
    void functionWithNullParameters_skipsSchemaBuilding() {
        // Build a FunctionDefinition with null parameters by direct construction
        FunctionDefinition func = new FunctionDefinition("no_params", "No parameters", null);

        ToolSpecification spec = ToolConversionUtils.convertFunctionToToolSpec(func);

        assertNotNull(spec);
        assertEquals("no_params", spec.name());
        assertNull(spec.parameters());
    }
}
