package dev.agenor.runtime.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonSchemaOutputGuardrail")
class JsonSchemaOutputGuardrailTest {

    private static final GuardrailContext CTX = GuardrailContext.of("test-agent");

    // Simple object schema: { name: string, score: number } — both required
    private static final String SIMPLE_SCHEMA = """
            {
              "type": "object",
              "required": ["name", "score"],
              "properties": {
                "name":  { "type": "string"  },
                "score": { "type": "number"  }
              }
            }
            """;

    private JsonSchemaOutputGuardrail guardrail;

    @BeforeEach
    void setUp() {
        guardrail = new JsonSchemaOutputGuardrail(SIMPLE_SCHEMA);
    }

    // -------------------------------------------------------------------------
    // Valid JSON — Passed
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Valid JSON output")
    class ValidJsonTests {

        @Test
        @DisplayName("conformant JSON object → Passed")
        void conformant_passed() {
            var result = guardrail.evaluate("""
                    {"name": "Alice", "score": 9.5}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("extra properties beyond schema → Passed (no additionalProperties constraint)")
        void extraProperties_passed() {
            var result = guardrail.evaluate("""
                    {"name": "Bob", "score": 7, "extra": true}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("integer satisfies number type constraint")
        void integerSatisfiesNumber() {
            var result = guardrail.evaluate("""
                    {"name": "Carol", "score": 10}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        }
    }

    // -------------------------------------------------------------------------
    // Invalid JSON — immediate Blocked
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Non-JSON output → immediate Blocked")
    class NonJsonTests {

        @Test
        @DisplayName("plain text → Blocked immediately")
        void plainText_blockedImmediately() {
            var result = guardrail.evaluate("Here is the result: Alice scored 9.5");
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(((GuardrailResult.Blocked) result).reason()).contains("not valid JSON");
        }

        @Test
        @DisplayName("JSON in markdown fence → Blocked immediately")
        void markdownFence_blocked() {
            var result = guardrail.evaluate("```json\n{\"name\":\"Alice\"}\n```");
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        }

        @Test
        @DisplayName("partial JSON → Blocked immediately")
        void partialJson_blocked() {
            var result = guardrail.evaluate("{\"name\": \"Alice\"");
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        }

        @Test
        @DisplayName("empty string → Blocked immediately")
        void emptyString_blocked() {
            var result = guardrail.evaluate("");
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        }
    }

    // -------------------------------------------------------------------------
    // Schema violations — reprompt then block
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Schema violations")
    class SchemaViolationTests {

        @Test
        @DisplayName("first invalid attempt → Modified with reprompt instruction")
        void firstInvalidAttempt_modified() {
            // missing required 'score'
            var result = guardrail.evaluate("""
                    {"name": "Alice"}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            String instruction = ((GuardrailResult.Modified) result).newContent();
            assertThat(instruction).contains("score");
            assertThat(instruction).containsIgnoringCase("JSON schema");
        }

        @Test
        @DisplayName("second invalid attempt (maxRepromptAttempts=1) → Blocked")
        void secondInvalidAttempt_blocked() {
            String invalidJson = """
                    {"name": "Alice"}
                    """;
            // first call → Modified
            guardrail.evaluate(invalidJson);
            // second call → Blocked
            var result = guardrail.evaluate(invalidJson);
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(((GuardrailResult.Blocked) result).reason())
                    .containsIgnoringCase("attempt");
        }

        @Test
        @DisplayName("maxRepromptAttempts=0 → first invalid attempt is Blocked immediately")
        void zeroAttempts_blockedImmediately() {
            var g = new JsonSchemaOutputGuardrail(SIMPLE_SCHEMA, 0);
            var result = g.evaluate("""
                    {"name": "Alice"}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        }

        @Test
        @DisplayName("wrong type for property → Modified with type error in instruction")
        void wrongType_modifiedWithTypeError() {
            var result = guardrail.evaluate("""
                    {"name": 42, "score": 9.5}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            assertThat(((GuardrailResult.Modified) result).newContent()).contains("name");
        }

        @Test
        @DisplayName("missing multiple required fields → Modified lists all violations")
        void missingMultipleFields_allViolationsListed() {
            var result = guardrail.evaluate("{}");
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            String instruction = ((GuardrailResult.Modified) result).newContent();
            assertThat(instruction).contains("name");
            assertThat(instruction).contains("score");
        }

        @Test
        @DisplayName("resetAttempts() resets the counter — Modified is returned again")
        void resetAttempts_allowsRepromptAgain() {
            String invalidJson = """
                    {"name": "Alice"}
                    """;
            guardrail.evaluate(invalidJson); // attempt 0 → Modified
            guardrail.evaluate(invalidJson); // attempt 1 → Blocked
            guardrail.resetAttempts();
            var result = guardrail.evaluate(invalidJson); // reset → Modified again
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }
    }

    // -------------------------------------------------------------------------
    // Nested schema validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Nested properties schema")
    class NestedSchemaTests {

        private static final String NESTED_SCHEMA = """
                {
                  "type": "object",
                  "required": ["user"],
                  "properties": {
                    "user": {
                      "type": "object",
                      "required": ["id"],
                      "properties": {
                        "id": { "type": "integer" }
                      }
                    }
                  }
                }
                """;

        @Test
        @DisplayName("valid nested object → Passed")
        void validNested_passed() {
            var g = new JsonSchemaOutputGuardrail(NESTED_SCHEMA);
            assertThat(g.evaluate("""
                    {"user": {"id": 1}}
                    """)).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("nested required field missing → Modified")
        void nestedMissing_modified() {
            var g = new JsonSchemaOutputGuardrail(NESTED_SCHEMA);
            var result = g.evaluate("""
                    {"user": {}}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            assertThat(((GuardrailResult.Modified) result).newContent()).contains("id");
        }

        @Test
        @DisplayName("nested type violation → Modified with path in instruction")
        void nestedTypeViolation_pathInInstruction() {
            var g = new JsonSchemaOutputGuardrail(NESTED_SCHEMA);
            var result = g.evaluate("""
                    {"user": {"id": "not-an-int"}}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
            assertThat(((GuardrailResult.Modified) result).newContent()).contains("id");
        }
    }

    // -------------------------------------------------------------------------
    // Array schema
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Array schema")
    class ArraySchemaTests {

        private static final String ARRAY_SCHEMA = """
                {
                  "type": "array",
                  "items": { "type": "string" }
                }
                """;

        @Test
        @DisplayName("valid string array → Passed")
        void validArray_passed() {
            var g = new JsonSchemaOutputGuardrail(ARRAY_SCHEMA);
            assertThat(g.evaluate("""
                    ["a", "b", "c"]
                    """)).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("array with wrong element type → Modified")
        void arrayWrongType_modified() {
            var g = new JsonSchemaOutputGuardrail(ARRAY_SCHEMA);
            var result = g.evaluate("""
                    ["a", 2, "c"]
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }

        @Test
        @DisplayName("non-array output against array schema → Modified")
        void nonArray_modified() {
            var g = new JsonSchemaOutputGuardrail(ARRAY_SCHEMA);
            var result = g.evaluate("""
                    {"items": ["a"]}
                    """);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("null schemaJson throws NullPointerException")
        void nullSchema_throws() {
            assertThatThrownBy(() -> new JsonSchemaOutputGuardrail(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("invalid JSON schema throws IllegalArgumentException")
        void invalidSchemaJson_throws() {
            assertThatThrownBy(() -> new JsonSchemaOutputGuardrail("not-json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid JSON Schema");
        }

        @Test
        @DisplayName("negative maxRepromptAttempts throws IllegalArgumentException")
        void negativeAttempts_throws() {
            assertThatThrownBy(() -> new JsonSchemaOutputGuardrail(SIMPLE_SCHEMA, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Async contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("apply() returns CompletableFuture with Passed for valid JSON")
    void asyncContract_passed() throws Exception {
        var result = guardrail.apply("""
                {"name": "Alice", "score": 8.0}
                """, CTX).get();
        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
    }

    @Test
    @DisplayName("apply() returns CompletableFuture with Blocked for non-JSON")
    void asyncContract_blocked() throws Exception {
        var result = guardrail.apply("plain text output", CTX).get();
        assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
    }
}
