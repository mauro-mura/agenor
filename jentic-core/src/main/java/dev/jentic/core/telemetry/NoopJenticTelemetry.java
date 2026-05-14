package dev.jentic.core.telemetry;

/**
 * No-op implementation of {@link JenticTelemetry} that produces zero overhead.
 *
 * <p>All methods return singleton objects that perform no work. No objects are
 * allocated during normal operation: {@link #spanBuilder(String)} always returns
 * {@link #NOOP_BUILDER} and {@link SpanBuilder#startSpan()} always returns
 * {@link #NOOP_SPAN}.
 *
 * <p>This class is the default telemetry used by all Jentic runtime components
 * when no {@link JenticTelemetry} implementation is configured. It ensures that
 * the core and runtime modules compile and run without any tracing library on
 * the classpath.
 *
 * @since 0.19.0
 */
public final class NoopJenticTelemetry implements JenticTelemetry {

    /** Shared singleton instance. */
    static final NoopJenticTelemetry INSTANCE = new NoopJenticTelemetry();

    /** Shared no-op span returned by every {@link SpanBuilder#startSpan()} call. */
    static final Span NOOP_SPAN = new NoopSpan();

    /** Shared no-op scope returned by every {@link Span#makeCurrent()} call. */
    static final SpanScope NOOP_SCOPE = () -> {};

    /** Shared no-op builder returned by every {@link #spanBuilder(String)} call. */
    static final SpanBuilder NOOP_BUILDER = new NoopSpanBuilder();

    private NoopJenticTelemetry() {}

    @Override
    public SpanBuilder spanBuilder(String operationName) {
        return NOOP_BUILDER;
    }

    // -------------------------------------------------------------------------
    // Inner noop types — package-private so tests can reference them
    // -------------------------------------------------------------------------

    static final class NoopSpanBuilder implements SpanBuilder {

        @Override
        public SpanBuilder setAttribute(String key, String value) {
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, long value) {
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, boolean value) {
            return this;
        }

        @Override
        public Span startSpan() {
            return NOOP_SPAN;
        }
    }

    static final class NoopSpan implements Span {

        @Override
        public Span setAttribute(String key, String value) {
            return this;
        }

        @Override
        public Span setAttribute(String key, long value) {
            return this;
        }

        @Override
        public Span setAttribute(String key, boolean value) {
            return this;
        }

        @Override
        public Span setAttribute(String key, double value) {
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            return this;
        }

        @Override
        public Span setStatus(SpanStatus status) {
            return this;
        }

        @Override
        public SpanScope makeCurrent() {
            return NOOP_SCOPE;
        }

        @Override
        public void end() {
            // no-op
        }
    }
}
