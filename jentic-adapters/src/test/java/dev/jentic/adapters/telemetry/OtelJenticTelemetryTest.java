package dev.jentic.adapters.telemetry;

import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.Span;
import dev.jentic.core.telemetry.SpanStatus;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link OtelJenticTelemetry} emits correctly named spans with the right
 * attributes and status codes, using {@link InMemorySpanExporter} (no external collector).
 */
@DisplayName("OtelJenticTelemetry")
class OtelJenticTelemetryTest {

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;
    private JenticTelemetry telemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        telemetry = new OtelJenticTelemetry(sdk);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SpanData finishedSpan(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No span named '" + name + "' found. Available: "
                        + spans.stream().map(SpanData::getName).toList()));
    }

    // -------------------------------------------------------------------------
    // Basic span lifecycle
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Basic span lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("span is recorded with the given name after end()")
        void span_recordedWithCorrectName() {
            telemetry.spanBuilder("test.span").startSpan().end();

            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            assertThat(exporter.getFinishedSpanItems().get(0).getName()).isEqualTo("test.span");
        }

        @Test
        @DisplayName("span is NOT recorded before end() is called")
        void span_notRecordedBeforeEnd() {
            telemetry.spanBuilder("unfinished").startSpan(); // no end()
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }

        @Test
        @DisplayName("multiple spans produce multiple records")
        void multipleSpans_allRecorded() {
            telemetry.spanBuilder("span.a").startSpan().end();
            telemetry.spanBuilder("span.b").startSpan().end();

            assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        }
    }

    // -------------------------------------------------------------------------
    // Attributes
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Attributes")
    class AttributeTests {

        @Test
        @DisplayName("string attribute set via SpanBuilder is present on finished span")
        void builderAttribute_string() {
            telemetry.spanBuilder("op")
                    .setAttribute("llm.provider", "openai")
                    .startSpan().end();

            SpanData span = finishedSpan("op");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("llm.provider")))
                    .isEqualTo("openai");
        }

        @Test
        @DisplayName("long attribute set via SpanBuilder is present on finished span")
        void builderAttribute_long() {
            telemetry.spanBuilder("op")
                    .setAttribute("llm.tokens.input", 256L)
                    .startSpan().end();

            SpanData span = finishedSpan("op");
            assertThat(span.getAttributes().get(AttributeKey.longKey("llm.tokens.input")))
                    .isEqualTo(256L);
        }

        @Test
        @DisplayName("boolean attribute set via SpanBuilder is present on finished span")
        void builderAttribute_boolean() {
            telemetry.spanBuilder("op")
                    .setAttribute("reflection.accepted", true)
                    .startSpan().end();

            SpanData span = finishedSpan("op");
            assertThat(span.getAttributes().get(AttributeKey.booleanKey("reflection.accepted")))
                    .isTrue();
        }

        @Test
        @DisplayName("string attribute set on Span (after start) is present on finished span")
        void spanAttribute_postStart() {
            Span span = telemetry.spanBuilder("op").startSpan();
            span.setAttribute("llm.latency_ms", 99L);
            span.end();

            SpanData data = finishedSpan("op");
            assertThat(data.getAttributes().get(AttributeKey.longKey("llm.latency_ms")))
                    .isEqualTo(99L);
        }
    }

    // -------------------------------------------------------------------------
    // Status codes
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Status codes")
    class StatusTests {

        @Test
        @DisplayName("setStatus(OK) maps to OTel StatusCode.OK")
        void setStatus_ok() {
            telemetry.spanBuilder("ok.op").startSpan().setStatus(SpanStatus.OK).end();

            assertThat(finishedSpan("ok.op").getStatus().getStatusCode())
                    .isEqualTo(StatusCode.OK);
        }

        @Test
        @DisplayName("setStatus(ERROR) maps to OTel StatusCode.ERROR")
        void setStatus_error() {
            telemetry.spanBuilder("err.op").startSpan().setStatus(SpanStatus.ERROR).end();

            assertThat(finishedSpan("err.op").getStatus().getStatusCode())
                    .isEqualTo(StatusCode.ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // Exception recording
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("recordException adds an exception event to the span")
    void recordException_addsEvent() {
        Span span = telemetry.spanBuilder("fail.op").startSpan();
        span.recordException(new RuntimeException("boom"));
        span.setStatus(SpanStatus.ERROR);
        span.end();

        SpanData data = finishedSpan("fail.op");
        assertThat(data.getEvents()).isNotEmpty();
        assertThat(data.getEvents().get(0).getName()).isEqualTo("exception");
    }

    // -------------------------------------------------------------------------
    // LLM chat span (realistic scenario)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("llm.chat span carries all expected attributes")
    void llmChat_span() {
        Span span = telemetry.spanBuilder("llm.chat")
                .setAttribute("llm.provider", "openai")
                .setAttribute("llm.model", "gpt-4o-mini")
                .startSpan();
        span.setAttribute("llm.latency_ms", 320L)
            .setAttribute("llm.tokens.input", 50L)
            .setAttribute("llm.tokens.output", 80L)
            .setStatus(SpanStatus.OK)
            .end();

        SpanData data = finishedSpan("llm.chat");
        var attrs = data.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("llm.provider"))).isEqualTo("openai");
        assertThat(attrs.get(AttributeKey.stringKey("llm.model"))).isEqualTo("gpt-4o-mini");
        assertThat(attrs.get(AttributeKey.longKey("llm.latency_ms"))).isEqualTo(320L);
        assertThat(attrs.get(AttributeKey.longKey("llm.tokens.input"))).isEqualTo(50L);
        assertThat(attrs.get(AttributeKey.longKey("llm.tokens.output"))).isEqualTo(80L);
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    // -------------------------------------------------------------------------
    // Parent-child context propagation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("child span created inside parent scope is linked via parent context")
    void parentChild_contextPropagation() {
        // Use the same tracer name as OtelJenticTelemetry.INSTRUMENTATION_NAME
        io.opentelemetry.api.trace.Tracer tracer = sdk.getTracer(OtelJenticTelemetry.INSTRUMENTATION_NAME);

        io.opentelemetry.api.trace.Span otelParent = tracer.spanBuilder("parent").startSpan();

        try (io.opentelemetry.context.Scope ignored = otelParent.makeCurrent()) {
            telemetry.spanBuilder("child").startSpan().end();
        } finally {
            otelParent.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData child  = spans.stream().filter(s -> s.getName().equals("child")).findFirst().orElseThrow();
        SpanData parent = spans.stream().filter(s -> s.getName().equals("parent")).findFirst().orElseThrow();

        assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
    }
}
