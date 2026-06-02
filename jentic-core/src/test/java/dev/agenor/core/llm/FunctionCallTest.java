package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FunctionCallTest {

    @Test
    void shouldCreateFunctionCallWithAllParameters() {
        // Given
        String id = "call_123";
        String name = "get_weather";
        String arguments = "{\"location\":\"London\"}";

        // When
        var functionCall = new FunctionCall(id, name, arguments);

        // Then
        assertEquals(id, functionCall.id());
        assertEquals(name, functionCall.name());
        assertEquals(arguments, functionCall.arguments());
    }

    @Test
    void shouldCreateFunctionCallWithFactoryMethod() {
        // Given
        String name = "calculate";
        String arguments = "{\"x\":5,\"y\":10}";

        // When
        var functionCall = FunctionCall.of(name, arguments);

        // Then
        assertNotNull(functionCall.id());
        assertTrue(functionCall.id().startsWith("call_"));
        assertEquals(name, functionCall.name());
        assertEquals(arguments, functionCall.arguments());
    }

    @Test
    void shouldCreateFunctionCallWithoutArguments() {
        // Given
        String name = "ping";

        // When
        var functionCall = FunctionCall.of(name);

        // Then
        assertNotNull(functionCall.id());
        assertEquals(name, functionCall.name());
        assertEquals("{}", functionCall.arguments());
    }

    @Test
    void shouldNormalizeBlankArgumentsToEmptyJson() {
        // Given
        String name = "test";
        String blankArgs = "   ";

        // When
        var functionCall = new FunctionCall("id1", name, blankArgs);

        // Then
        assertEquals("{}", functionCall.arguments());
    }

    @Test
    void shouldThrowExceptionWhenNameIsNull() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            new FunctionCall("id1", null, "{}")
        );
    }

    @Test
    void shouldThrowExceptionWhenNameIsBlank() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new FunctionCall("id1", "  ", "{}")
        );
    }

    @Test
    void shouldParseSimpleJsonArguments() {
        // Given
        String args = "{\"name\":\"John\",\"age\":30,\"active\":true}";
        var functionCall = FunctionCall.of("test", args);

        // When
        Map<String, Object> parsed = functionCall.parseArguments();

        // Then
        assertEquals("John", parsed.get("name"));
        assertEquals(30, parsed.get("age"));
        assertEquals(true, parsed.get("active"));
    }

    @Test
    void shouldParseNumericArguments() {
        // Given
        String args = "{\"count\":42,\"price\":19.99}";
        var functionCall = FunctionCall.of("test", args);

        // When
        Map<String, Object> parsed = functionCall.parseArguments();

        // Then
        assertEquals(42, parsed.get("count"));
        assertEquals(19.99, parsed.get("price"));
    }

    @Test
    void shouldReturnEmptyMapForNullArguments() {
        // Given
        var functionCall = new FunctionCall("id1", "test", null);

        // When
        Map<String, Object> parsed = functionCall.parseArguments();

        // Then
        assertTrue(parsed.isEmpty());
    }

    @Test
    void shouldReturnEmptyMapForEmptyJsonArguments() {
        // Given
        var functionCall = FunctionCall.of("test", "{}");

        // When
        Map<String, Object> parsed = functionCall.parseArguments();

        // Then
        assertTrue(parsed.isEmpty());
    }

    @Test
    void shouldCheckIfHasArguments() {
        // Given
        var withArgs = FunctionCall.of("test", "{\"key\":\"value\"}");
        var noArgs = FunctionCall.of("test");
        var emptyArgs = FunctionCall.of("test", "{}");

        // Then
        assertTrue(withArgs.hasArguments());
        assertFalse(noArgs.hasArguments());
        assertFalse(emptyArgs.hasArguments());
    }

    @Test
    void shouldGetStringArgument() {
        // Given
        String args = "{\"location\":\"Paris\",\"unit\":\"celsius\"}";
        var functionCall = FunctionCall.of("weather", args);

        // When
        String location = functionCall.getStringArgument("location");
        String unit = functionCall.getStringArgument("unit");
        String missing = functionCall.getStringArgument("missing");

        // Then
        assertEquals("Paris", location);
        assertEquals("celsius", unit);
        assertNull(missing);
    }

    @Test
    void shouldGetIntArgument() {
        // Given
        String args = "{\"count\":42,\"price\":19.99}";
        var functionCall = FunctionCall.of("test", args);

        // When
        Integer count = functionCall.getIntArgument("count");
        Integer missing = functionCall.getIntArgument("missing");

        // Then
        assertEquals(42, count);
        assertNull(missing);
    }

    @Test
    void shouldGetDoubleArgument() {
        // Given
        String args = "{\"price\":19.99,\"count\":42}";
        var functionCall = FunctionCall.of("test", args);

        // When
        Double price = functionCall.getDoubleArgument("price");
        Double missing = functionCall.getDoubleArgument("missing");

        // Then
        assertEquals(19.99, price);
        assertNull(missing);
    }

    @Test
    void shouldGetBooleanArgument() {
        // Given
        String args = "{\"active\":true,\"verified\":false}";
        var functionCall = FunctionCall.of("test", args);

        // When
        Boolean active = functionCall.getBooleanArgument("active");
        Boolean verified = functionCall.getBooleanArgument("verified");
        Boolean missing = functionCall.getBooleanArgument("missing");

        // Then
        assertTrue(active);
        assertFalse(verified);
        assertNull(missing);
    }

    @Test
    void shouldConvertIntegerToDouble() {
        // Given
        String args = "{\"value\":42}";
        var functionCall = FunctionCall.of("test", args);

        // When
        Double value = functionCall.getDoubleArgument("value");

        // Then
        assertEquals(42.0, value);
    }

    @Test
    void shouldHandleNullValueInArguments() {
        // Given
        String args = "{\"value\":null}";
        var functionCall = FunctionCall.of("test", args);

        // When
        String value = functionCall.getStringArgument("value");

        // Then
        assertNull(value);
    }

    @Test
    void shouldGenerateToStringWithId() {
        // Given
        var functionCall = new FunctionCall("call_123", "test", "{\"key\":\"value\"}");

        // When
        String str = functionCall.toString();

        // Then
        assertTrue(str.contains("call_123"));
        assertTrue(str.contains("test"));
        assertTrue(str.contains("arguments"));
    }

    @Test
    void shouldGenerateToStringWithoutArgumentsWhenEmpty() {
        // Given
        var functionCall = FunctionCall.of("test");

        // When
        String str = functionCall.toString();

        // Then
        assertTrue(str.contains("test"));
        assertFalse(str.contains("arguments"));
    }

    @Test
    void shouldBeEqualWhenSameValues() {
        // Given
        var call1 = new FunctionCall("id1", "test", "{\"x\":1}");
        var call2 = new FunctionCall("id1", "test", "{\"x\":1}");

        // Then
        assertEquals(call1, call2);
        assertEquals(call1.hashCode(), call2.hashCode());
    }

    @Test
    void shouldNotBeEqualWhenDifferentValues() {
        // Given
        var call1 = new FunctionCall("id1", "test", "{\"x\":1}");
        var call2 = new FunctionCall("id2", "test", "{\"x\":1}");

        // Then
        assertNotEquals(call1, call2);
    }
}
