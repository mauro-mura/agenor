package dev.agenor.core.telemetry;

/**
 * A single unit of work tracked for distributed tracing.
 *
 * <p>Typical usage:
 * <pre>{@code
 * Span span = telemetry.spanBuilder("llm.chat")
 *     .setAttribute("llm.provider", provider)
 *     .startSpan();
 * try {
 *     // ... do work ...
 *     span.setStatus(SpanStatus.OK);
 * } catch (Exception e) {
 *     span.recordException(e).setStatus(SpanStatus.ERROR);
 *     throw e;
 * } finally {
 *     span.end();
 * }
 * }</pre>
 *
 * <p>Implementations must be safe for use across virtual threads. The
 * {@link NoopAgenorTelemetry noop} implementation
 * performs no work and introduces zero allocations.
 *
 * @since 0.19.0
 */
public interface Span {

    /**
     * Sets a {@link String} attribute on this span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    Span setAttribute(String key, String value);

    /**
     * Sets a {@code long} attribute on this span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    Span setAttribute(String key, long value);

    /**
     * Sets a {@code boolean} attribute on this span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    Span setAttribute(String key, boolean value);

    /**
     * Sets a {@code double} attribute on this span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    Span setAttribute(String key, double value);

    /**
     * Records an exception on this span.
     *
     * @param t the throwable to record; never {@code null}
     * @return {@code this} for chaining
     */
    Span recordException(Throwable t);

    /**
     * Sets the status of this span.
     *
     * @param status the span status; never {@code null}
     * @return {@code this} for chaining
     */
    Span setStatus(SpanStatus status);

    /**
     * Makes this span the current span in the active context, enabling child spans
     * created during the scope to be automatically linked to this span as their parent.
     *
     * <p>Must be used in a {@code try-with-resources} block. The scope must be closed
     * on the same thread that called this method. Closing the scope does not end the
     * span; {@link #end()} must still be called in a {@code finally} block.
     *
     * @return a {@link SpanScope} that, when closed, restores the previous context
     * @since 0.22.0
     */
    SpanScope makeCurrent();

    /**
     * Ends this span. Must be called exactly once, in a {@code finally} block.
     * Calling {@code end()} more than once has no effect on the noop implementation
     * and is safe (though discouraged) on OTel implementations.
     */
    void end();
}
