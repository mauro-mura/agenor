package dev.jentic.core.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that {@link NoopJenticTelemetry} (and its inner noop Span / SpanBuilder)
 * never throw, return non-null values, and produce the same singletons on every call.
 */
@DisplayName("NoopJenticTelemetry")
class NoopJenticTelemetryTest {

    private final JenticTelemetry noop = JenticTelemetry.noop();

    // -------------------------------------------------------------------------
    // Singleton identity
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("noop() always returns the same instance")
    void noop_returnsSameSingleton() {
        assertThat(JenticTelemetry.noop()).isSameAs(JenticTelemetry.noop());
    }

    // -------------------------------------------------------------------------
    // SpanBuilder
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("SpanBuilder")
    class SpanBuilderTests {

        @Test
        @DisplayName("spanBuilder returns non-null")
        void spanBuilder_returnsNonNull() {
            assertThat(noop.spanBuilder("any.operation")).isNotNull();
        }

        @Test
        @DisplayName("setAttribute(String) returns this (fluent)")
        void setAttribute_string_returnsSelf() {
            var builder = noop.spanBuilder("op");
            assertThat(builder.setAttribute("k", "v")).isSameAs(builder);
        }

        @Test
        @DisplayName("setAttribute(long) returns this (fluent)")
        void setAttribute_long_returnsSelf() {
            var builder = noop.spanBuilder("op");
            assertThat(builder.setAttribute("k", 42L)).isSameAs(builder);
        }

        @Test
        @DisplayName("setAttribute(boolean) returns this (fluent)")
        void setAttribute_boolean_returnsSelf() {
            var builder = noop.spanBuilder("op");
            assertThat(builder.setAttribute("k", true)).isSameAs(builder);
        }

        @Test
        @DisplayName("startSpan returns non-null")
        void startSpan_returnsNonNull() {
            assertThat(noop.spanBuilder("op").startSpan()).isNotNull();
        }

        @Test
        @DisplayName("successive spanBuilders return the same singleton builder")
        void spanBuilder_returnsSameInstance() {
            assertThat(noop.spanBuilder("a")).isSameAs(noop.spanBuilder("b"));
        }
    }

    // -------------------------------------------------------------------------
    // Span
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Span")
    class SpanTests {

        private final Span span = noop.spanBuilder("op").startSpan();

        @Test
        @DisplayName("setAttribute(String) returns this (fluent)")
        void setAttribute_string_returnsSelf() {
            assertThat(span.setAttribute("k", "v")).isSameAs(span);
        }

        @Test
        @DisplayName("setAttribute(long) returns this (fluent)")
        void setAttribute_long_returnsSelf() {
            assertThat(span.setAttribute("k", 1L)).isSameAs(span);
        }

        @Test
        @DisplayName("setAttribute(boolean) returns this (fluent)")
        void setAttribute_boolean_returnsSelf() {
            assertThat(span.setAttribute("k", false)).isSameAs(span);
        }

        @Test
        @DisplayName("setAttribute(double-as-String) returns this (fluent)")
        void setAttribute_double_returnsSelf() {
            assertThat(span.setAttribute("score", String.valueOf(0.9))).isSameAs(span);
        }

        @Test
        @DisplayName("recordException does not throw")
        void recordException_doesNotThrow() {
            assertThatCode(() -> span.recordException(new RuntimeException("boom")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("recordException returns this (fluent)")
        void recordException_returnsSelf() {
            assertThat(span.recordException(new RuntimeException())).isSameAs(span);
        }

        @Test
        @DisplayName("setStatus(OK) returns this (fluent)")
        void setStatus_ok_returnsSelf() {
            assertThat(span.setStatus(SpanStatus.OK)).isSameAs(span);
        }

        @Test
        @DisplayName("setStatus(ERROR) returns this (fluent)")
        void setStatus_error_returnsSelf() {
            assertThat(span.setStatus(SpanStatus.ERROR)).isSameAs(span);
        }

        @Test
        @DisplayName("end() does not throw")
        void end_doesNotThrow() {
            assertThatCode(span::end).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("calling end() multiple times does not throw")
        void end_idempotent() {
            assertThatCode(() -> {
                span.end();
                span.end();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("successive startSpan calls return the same singleton span")
        void startSpan_returnsSameInstance() {
            var s1 = noop.spanBuilder("x").startSpan();
            var s2 = noop.spanBuilder("y").startSpan();
            assertThat(s1).isSameAs(s2);
        }
    }

    // -------------------------------------------------------------------------
    // Chained fluent API
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("full fluent chain does not throw")
    void fullChain_doesNotThrow() {
        assertThatCode(() ->
            noop.spanBuilder("llm.chat")
                .setAttribute("llm.provider", "test")
                .setAttribute("llm.model", "gpt-mock")
                .startSpan()
                .setAttribute("llm.latency_ms", 42L)
                .setAttribute("llm.tokens.input", 100L)
                .setStatus(SpanStatus.OK)
                .end()
        ).doesNotThrowAnyException();
    }
}
