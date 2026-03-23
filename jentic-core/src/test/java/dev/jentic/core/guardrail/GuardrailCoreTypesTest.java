package dev.jentic.core.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

@DisplayName("dev.jentic.core.guardrail — base types")
class GuardrailCoreTypesTest {

    // =========================================================================
    // GuardrailResult
    // =========================================================================

    @Nested
    @DisplayName("GuardrailResult")
    class GuardrailResultTests {

        @Test
        @DisplayName("Passed is a valid GuardrailResult")
        void passed_isGuardrailResult() {
            GuardrailResult r = new GuardrailResult.Passed();
            assertThat(r).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("Modified stores newContent")
        void modified_storesContent() {
            var r = new GuardrailResult.Modified("redacted text");
            assertThat(r.newContent()).isEqualTo("redacted text");
        }

        @Test
        @DisplayName("Modified null newContent throws IllegalArgumentException")
        void modified_nullContent_throws() {
            assertThatThrownBy(() -> new GuardrailResult.Modified(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Blocked stores reason")
        void blocked_storesReason() {
            var r = new GuardrailResult.Blocked("PII detected");
            assertThat(r.reason()).isEqualTo("PII detected");
        }

        @Test
        @DisplayName("Blocked null reason throws IllegalArgumentException")
        void blocked_nullReason_throws() {
            assertThatThrownBy(() -> new GuardrailResult.Blocked(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Exhaustive switch covers all permits")
        void exhaustiveSwitch_allCases() {
            GuardrailResult[] results = {
                new GuardrailResult.Passed(),
                new GuardrailResult.Modified("x"),
                new GuardrailResult.Blocked("y")
            };
            for (GuardrailResult r : results) {
                // Compiler enforces exhaustiveness; this just verifies runtime dispatch
                String tag = switch (r) {
                    case GuardrailResult.Passed   p -> "passed";
                    case GuardrailResult.Modified m -> "modified";
                    case GuardrailResult.Blocked  b -> "blocked";
                };
                assertThat(tag).isNotNull();
            }
        }
    }

    // =========================================================================
    // GuardrailContext
    // =========================================================================

    @Nested
    @DisplayName("GuardrailContext")
    class GuardrailContextTests {

        @Test
        @DisplayName("canonical constructor stores all fields")
        void canonicalConstructor_storesFields() {
            var meta = Map.<String, Object>of("locale", "it-IT");
            var ctx = new GuardrailContext("agent-1", "finance", meta);
            assertThat(ctx.agentId()).isEqualTo("agent-1");
            assertThat(ctx.topic()).isEqualTo("finance");
            assertThat(ctx.metadata()).containsEntry("locale", "it-IT");
        }

        @Test
        @DisplayName("metadata map is unmodifiable")
        void metadata_isUnmodifiable() {
            var ctx = new GuardrailContext("a", null, Map.of("k", "v"));
            assertThatThrownBy(() -> ctx.metadata().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("null agentId throws NullPointerException")
        void nullAgentId_throws() {
            assertThatThrownBy(() -> new GuardrailContext(null, null, Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null metadata is treated as empty map")
        void nullMetadata_treatedAsEmpty() {
            var ctx = new GuardrailContext("a", null, null);
            assertThat(ctx.metadata()).isEmpty();
        }

        @Test
        @DisplayName("of(agentId) factory produces empty metadata and null topic")
        void of_agentId_factory() {
            var ctx = GuardrailContext.of("agent-x");
            assertThat(ctx.agentId()).isEqualTo("agent-x");
            assertThat(ctx.topic()).isNull();
            assertThat(ctx.metadata()).isEmpty();
        }

        @Test
        @DisplayName("of(agentId, topic) factory stores topic")
        void of_agentId_topic_factory() {
            var ctx = GuardrailContext.of("agent-x", "support");
            assertThat(ctx.topic()).isEqualTo("support");
        }
    }

    // =========================================================================
    // GuardrailViolationException
    // =========================================================================

    @Nested
    @DisplayName("GuardrailViolationException")
    class GuardrailViolationExceptionTests {

        @Test
        @DisplayName("stores reason and blockedBy")
        void stores_reason_and_blockedBy() {
            var ex = new GuardrailViolationException("PII found", "PiiRedactionGuardrail");
            assertThat(ex.reason()).isEqualTo("PII found");
            assertThat(ex.blockedBy()).isEqualTo("PiiRedactionGuardrail");
        }

        @Test
        @DisplayName("message includes blockedBy and reason")
        void message_includesFields() {
            var ex = new GuardrailViolationException("bad content", "ContentPolicyGuardrail");
            assertThat(ex.getMessage())
                    .contains("ContentPolicyGuardrail")
                    .contains("bad content");
        }

        @Test
        @DisplayName("null reason throws NullPointerException")
        void nullReason_throws() {
            assertThatThrownBy(() -> new GuardrailViolationException(null, "SomeGuardrail"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null blockedBy throws NullPointerException")
        void nullBlockedBy_throws() {
            assertThatThrownBy(() -> new GuardrailViolationException("reason", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("constructor with cause stores cause")
        void constructorWithCause_storesCause() {
            var cause = new RuntimeException("root");
            var ex = new GuardrailViolationException("reason", "SomeGuardrail", cause);
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }

    // =========================================================================
    // InputGuardrail / OutputGuardrail (functional interface contract)
    // =========================================================================

    @Nested
    @DisplayName("InputGuardrail / OutputGuardrail functional contract")
    class FunctionalInterfaceTests {

        @Test
        @DisplayName("InputGuardrail lambda returns Passed")
        void inputGuardrail_lambda_passed() throws Exception {
            InputGuardrail g = (input, ctx) ->
                    CompletableFuture.completedFuture(new GuardrailResult.Passed());
            var result = g.apply("hello", GuardrailContext.of("agent")).get();
            assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("OutputGuardrail lambda returns Blocked")
        void outputGuardrail_lambda_blocked() throws Exception {
            OutputGuardrail g = (output, ctx) ->
                    CompletableFuture.completedFuture(new GuardrailResult.Blocked("policy"));
            var result = g.apply("bad output", GuardrailContext.of("agent")).get();
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(((GuardrailResult.Blocked) result).reason()).isEqualTo("policy");
        }
    }
}