package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FunctionDefinitionTest {

    @Test
    void shouldCreateFunctionDefinitionWithBuilder() {
        // Given/When
        var definition = FunctionDefinition.builder("get_weather")
            .description("Get current weather")
            .stringParameter("location", "The city", true)
            .stringParameter("unit", "Temperature unit", false)
            .build();

        // Then
        assertEquals("get_weather", definition.name());
        assertEquals("Get current weather", definition.description());
        assertNotNull(definition.parameters());
    }

    @Test
    void shouldValidateFunctionName() {
        // Given
        String validName = "valid_function_123";

        // When/Then
        assertDoesNotThrow(() ->
            FunctionDefinition.builder(validName).build()
        );
    }

    @Test
    void shouldThrowExceptionForInvalidFunctionName() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            FunctionDefinition.builder("123-invalid").build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            FunctionDefinition.builder("invalid-name").build()
        );
    }

    @Test
    void shouldThrowExceptionWhenNameIsNull() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            FunctionDefinition.builder(null)
        );
    }

    @Test
    void shouldThrowExceptionWhenNameIsBlank() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new FunctionDefinition("  ", "desc", Map.of())
        );
    }

    @Test
    void shouldAddStringParameter() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("name", "User name", true)
            .build();

        // When
        var properties = definition.getProperties();

        // Then
        assertTrue(properties.containsKey("name"));
        @SuppressWarnings("unchecked")
        var param = (Map<String, Object>) properties.get("name");
        assertEquals("string", param.get("type"));
        assertEquals("User name", param.get("description"));
    }

    @Test
    void shouldAddNumberParameter() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .numberParameter("age", "User age", false)
            .build();

        // When
        var properties = definition.getProperties();

        // Then
        assertTrue(properties.containsKey("age"));
        @SuppressWarnings("unchecked")
        var param = (Map<String, Object>) properties.get("age");
        assertEquals("number", param.get("type"));
    }

    @Test
    void shouldAddBooleanParameter() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .booleanParameter("active", "Is active", true)
            .build();

        // When
        var properties = definition.getProperties();

        // Then
        assertTrue(properties.containsKey("active"));
        @SuppressWarnings("unchecked")
        var param = (Map<String, Object>) properties.get("active");
        assertEquals("boolean", param.get("type"));
    }

    @Test
    void shouldAddEnumParameter() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .enumParameter("unit", "Temperature unit", true, "celsius", "fahrenheit")
            .build();

        // When
        var properties = definition.getProperties();

        // Then
        assertTrue(properties.containsKey("unit"));
        @SuppressWarnings("unchecked")
        var param = (Map<String, Object>) properties.get("unit");
        assertEquals("string", param.get("type"));
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) param.get("enum");
        assertEquals(List.of("celsius", "fahrenheit"), enumValues);
    }

    @Test
    void shouldAddObjectParameter() {
        // Given
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of("x", Map.of("type", "number"))
        );

        var definition = FunctionDefinition.builder("test")
            .objectParameter("config", schema, "Configuration", false)
            .build();

        // When
        var properties = definition.getProperties();

        // Then
        assertTrue(properties.containsKey("config"));
    }

    @Test
    void shouldTrackRequiredParameters() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("required1", "desc", true)
            .stringParameter("optional", "desc", false)
            .numberParameter("required2", "desc", true)
            .build();

        // When
        List<String> required = definition.getRequiredParameters();

        // Then
        assertEquals(2, required.size());
        assertTrue(required.contains("required1"));
        assertTrue(required.contains("required2"));
        assertFalse(required.contains("optional"));
    }

    @Test
    void shouldCheckIfParameterIsRequired() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("required", "desc", true)
            .stringParameter("optional", "desc", false)
            .build();

        // Then
        assertTrue(definition.isParameterRequired("required"));
        assertFalse(definition.isParameterRequired("optional"));
        assertFalse(definition.isParameterRequired("nonexistent"));
    }

    @Test
    void shouldReturnEmptyListWhenNoRequiredParameters() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("optional", "desc", false)
            .build();

        // When
        List<String> required = definition.getRequiredParameters();

        // Then
        assertTrue(required.isEmpty());
    }

    @Test
    void shouldGetParameterSchema() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("param1", "desc", true)
            .build();

        // When
        Map<String, Object> schema = definition.getParameterSchema();

        // Then
        assertEquals("object", schema.get("type"));
        assertTrue(schema.containsKey("properties"));
        assertTrue(schema.containsKey("required"));
    }

    @Test
    void shouldGetProperties() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("param1", "desc", true)
            .numberParameter("param2", "desc", false)
            .build();

        // When
        Map<String, Object> properties = definition.getProperties();

        // Then
        assertEquals(2, properties.size());
        assertTrue(properties.containsKey("param1"));
        assertTrue(properties.containsKey("param2"));
    }

    @Test
    void shouldReturnEmptyMapWhenNoProperties() {
        // Given
        var definition = FunctionDefinition.builder("test").build();

        // When
        Map<String, Object> properties = definition.getProperties();

        // Then
        assertTrue(properties.isEmpty());
    }

    @Test
    void shouldMakeParametersImmutable() {
        // Given
        var definition = FunctionDefinition.builder("test")
            .stringParameter("param1", "desc", true)
            .build();

        // When/Then
        assertThrows(UnsupportedOperationException.class, () -> {
            Map<String, Object> params = definition.parameters();
            params.put("newKey", "value");
        });
    }

    @Test
    void shouldAllowNullDescription() {
        // Given/When
        var definition = FunctionDefinition.builder("test")
            .parameter("param", "string", null, false)
            .build();

        // Then
        assertNotNull(definition);
        var properties = definition.getProperties();
        assertTrue(properties.containsKey("param"));
    }

    @Test
    void shouldGenerateToStringWithRequiredCount() {
        // Given
        var definition = FunctionDefinition.builder("get_weather")
            .description("Get weather")
            .stringParameter("location", "City", true)
            .stringParameter("unit", "Unit", false)
            .build();

        // When
        String str = definition.toString();

        // Then
        assertTrue(str.contains("get_weather"));
        assertTrue(str.contains("Get weather"));
        assertTrue(str.contains("1 required"));
    }

    @Test
    void shouldBeEqualWhenSameValues() {
        // Given
        var def1 = FunctionDefinition.builder("test")
            .description("Test function")
            .stringParameter("param", "desc", true)
            .build();

        var def2 = FunctionDefinition.builder("test")
            .description("Test function")
            .stringParameter("param", "desc", true)
            .build();

        // Then
        assertEquals(def1, def2);
        assertEquals(def1.hashCode(), def2.hashCode());
    }

    @Test
    void shouldNotBeEqualWhenDifferentNames() {
        // Given
        var def1 = FunctionDefinition.builder("test1").build();
        var def2 = FunctionDefinition.builder("test2").build();

        // Then
        assertNotEquals(def1, def2);
    }

    @Test
    void shouldAcceptUnderscoreInName() {
        // When/Then
        assertDoesNotThrow(() ->
            FunctionDefinition.builder("_private_function").build()
        );
    }

    @Test
    void shouldAcceptNumbersInName() {
        // When/Then
        assertDoesNotThrow(() ->
            FunctionDefinition.builder("function123").build()
        );
    }

    @Test
    void shouldRejectNameStartingWithNumber() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            FunctionDefinition.builder("123function").build()
        );
    }
}
