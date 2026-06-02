package dev.agenor.core.telemetry;

/**
 * Service-provider interface for distributed tracing in Jentic.
 *
 * <p>Implementations must be safe for concurrent use across virtual threads.
 *
 * <p>The default implementation, {@link NoopAgenorTelemetry}, performs no work
 * and introduces zero allocations. It is the active implementation whenever the
 * OpenTelemetry SDK is absent from the classpath.
 *
 * <p>To activate real tracing, declare the {@code opentelemetry-sdk} dependency
 * and either:
 * <ul>
 *   <li>Use the Spring Boot starter (auto-configured when the SDK is present and
 *       {@code agenor.telemetry.enabled=true}), or</li>
 *   <li>Build an {@code OtelAgenorTelemetry} via {@code OtelTelemetryFactory} and
 *       pass it to {@code AgenorRuntime.Builder.telemetry(...)}.</li>
 * </ul>
 *
 * <p>Example — programmatic wiring:
 * <pre>{@code
 * AgenorTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent-app")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 *
 * AgenorRuntime runtime = AgenorRuntime.builder()
 *     .telemetry(telemetry)
 *     .build();
 * }</pre>
 *
 * @see NoopAgenorTelemetry
 * @since 0.19.0
 */
public interface AgenorTelemetry {

    /**
     * Returns a {@link SpanBuilder} for the given operation name.
     *
     * <p>The returned builder captures the current tracing context so that
     * spans started from it are automatically children of the ambient parent span.
     *
     * @param operationName the name of the span (e.g. {@code "llm.chat"}); never {@code null}
     * @return a new span builder; never {@code null}
     */
    SpanBuilder spanBuilder(String operationName);

    /**
     * Returns the no-op singleton that produces zero-overhead spans.
     *
     * <p>This is the default used by all Jentic components when no telemetry
     * implementation is configured.
     *
     * @return the shared noop instance; never {@code null}
     */
    static AgenorTelemetry noop() {
        return NoopAgenorTelemetry.INSTANCE;
    }
}
