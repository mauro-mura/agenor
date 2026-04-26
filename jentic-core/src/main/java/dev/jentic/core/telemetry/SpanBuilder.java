package dev.jentic.core.telemetry;

/**
 * Fluent builder for creating a {@link Span}.
 *
 * <p>Attributes set on the builder are recorded as span attributes at
 * {@link #startSpan()} time.
 *
 * <p>Example:
 * <pre>{@code
 * Span span = telemetry.spanBuilder("mcp.tool.call")
 *     .setAttribute("mcp.tool.name", toolName)
 *     .setAttribute("mcp.transport", "sse")
 *     .startSpan();
 * }</pre>
 *
 * @since 0.19.0
 */
public interface SpanBuilder {

    /**
     * Sets a {@link String} attribute that will be recorded on the started span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    SpanBuilder setAttribute(String key, String value);

    /**
     * Sets a {@code long} attribute that will be recorded on the started span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    SpanBuilder setAttribute(String key, long value);

    /**
     * Sets a {@code boolean} attribute that will be recorded on the started span.
     *
     * @param key   attribute key; never {@code null}
     * @param value attribute value
     * @return {@code this} for chaining
     */
    SpanBuilder setAttribute(String key, boolean value);

    /**
     * Starts and returns the span. The caller is responsible for calling
     * {@link Span#end()} in a {@code finally} block.
     *
     * @return the started span; never {@code null}
     */
    Span startSpan();
}
