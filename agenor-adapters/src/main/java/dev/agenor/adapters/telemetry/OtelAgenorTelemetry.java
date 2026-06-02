package dev.agenor.adapters.telemetry;

import dev.agenor.core.telemetry.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.util.Objects;

/**
 * {@link AgenorTelemetry} implementation backed by the OpenTelemetry SDK.
 *
 * <p>Each call to {@link #spanBuilder(String)} captures the current OTel
 * {@link Context}, so spans started from it are automatically children of the
 * ambient parent span. Parent-child relationships are preserved across
 * virtual-thread boundaries as long as the caller holds the context correctly
 * (e.g., within a {@code try-with-resources} scope returned by
 * {@link Context#makeCurrent()}).
 *
 * <p>Use {@link OtelTelemetryFactory} to construct instances:
 * <pre>{@code
 * AgenorTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 * }</pre>
 *
 * @since 0.19.0
 */
public final class OtelAgenorTelemetry implements AgenorTelemetry, AutoCloseable {

    static final String INSTRUMENTATION_NAME = "dev.agenor";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    /**
     * Creates an {@code OtelAgenorTelemetry} backed by the given {@link OpenTelemetry} instance.
     *
     * <p>The instance is retained so that {@link #close()} can flush and shut down the
     * underlying {@link io.opentelemetry.sdk.OpenTelemetrySdk} (including its
     * {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor}) when the runtime stops.
     * Without holding a reference the SDK would be eligible for GC immediately after
     * construction, silently discarding all buffered spans.
     *
     * @param openTelemetry the configured OTel instance; never {@code null}
     */
    public OtelAgenorTelemetry(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    /**
     * Flushes and shuts down the underlying OTel SDK.
     *
     * <p>Forces the {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor} to export
     * any buffered spans before the process exits. Called automatically by
     * {@code AgenorRuntime.stop()} when telemetry is {@link AutoCloseable}.
     */
    @Override
    public void close() {
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            sdk.close();
        }
    }

    @Override
    public SpanBuilder spanBuilder(String operationName) {
        Objects.requireNonNull(operationName, "operationName must not be null");
        // Capture the parent context at call time (the thread calling spanBuilder).
        Context parentContext = Context.current();
        return new OtelSpanBuilder(tracer.spanBuilder(operationName)
                .setParent(parentContext));
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private static final class OtelSpanBuilder implements SpanBuilder {

        private final io.opentelemetry.api.trace.SpanBuilder delegate;

        OtelSpanBuilder(io.opentelemetry.api.trace.SpanBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public SpanBuilder setAttribute(String key, String value) {
            delegate.setAttribute(key, value != null ? value : "");
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, long value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, boolean value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span startSpan() {
            return new OtelSpan(delegate.startSpan());
        }
    }

    static final class OtelSpan implements Span {

        private final io.opentelemetry.api.trace.Span delegate;

        OtelSpan(io.opentelemetry.api.trace.Span delegate) {
            this.delegate = delegate;
        }

        @Override
        public Span setAttribute(String key, String value) {
            delegate.setAttribute(key, value != null ? value : "");
            return this;
        }

        @Override
        public Span setAttribute(String key, long value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, boolean value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, double value) {
            delegate.setAttribute(key, value);
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            if (t != null) {
                delegate.recordException(t);
            }
            return this;
        }

        @Override
        public Span setStatus(SpanStatus status) {
            if (status != null) {
                delegate.setStatus(toOtelStatus(status));
            }
            return this;
        }

        @Override
        public SpanScope makeCurrent() {
            var otelScope = delegate.makeCurrent();
            return otelScope::close;
        }

        @Override
        public void end() {
            delegate.end();
        }

        private static StatusCode toOtelStatus(SpanStatus status) {
            return switch (status) {
                case OK    -> StatusCode.OK;
                case ERROR -> StatusCode.ERROR;
                case UNSET -> StatusCode.UNSET;
            };
        }
    }
}
