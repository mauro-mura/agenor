package dev.jentic.core.telemetry;

/**
 * A handle to the active span context set by {@link Span#makeCurrent()}.
 *
 * <p>Closing this scope restores the context that was active before
 * {@code makeCurrent()} was called. Must be closed on the same thread
 * that called {@code makeCurrent()}.
 *
 * <p>Typical usage:
 * <pre>{@code
 * Span span = telemetry.spanBuilder("operation").startSpan();
 * try (var scope = span.makeCurrent()) {
 *     doWork(); // child spans will be automatically linked to this span
 *     span.setStatus(SpanStatus.OK);
 * } catch (Exception e) {
 *     span.recordException(e).setStatus(SpanStatus.ERROR);
 *     throw e;
 * } finally {
 *     span.end();
 * }
 * }</pre>
 *
 * @since 0.22.0
 */
public interface SpanScope extends AutoCloseable {

    /**
     * Closes this scope and restores the previous context. Never throws.
     */
    @Override
    void close();
}
