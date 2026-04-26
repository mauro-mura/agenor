package dev.jentic.core.telemetry;

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
 * {@link dev.jentic.core.telemetry.NoopJenticTelemetry noop} implementation
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
     * Ends this span. Must be called exactly once, in a {@code finally} block.
     * Calling {@code end()} more than once has no effect on the noop implementation
     * and is safe (though discouraged) on OTel implementations.
     */
    void end();
}
