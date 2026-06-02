package dev.agenor.adapters.telemetry;

import dev.agenor.core.telemetry.JenticTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Objects;

/**
 * Factory for creating {@link OtelJenticTelemetry} instances.
 *
 * <p>Use the fluent {@link #builder()} to configure exporter, endpoint, and service name:
 * <pre>{@code
 * JenticTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 * }</pre>
 *
 * <p>Alternatively, configure entirely via environment variables:
 * <pre>{@code
 * // OTEL_SERVICE_NAME=my-agent
 * // OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
 * JenticTelemetry telemetry = OtelTelemetryFactory.fromEnvironment();
 * }</pre>
 *
 * <p>Supported exporter types: {@code otlp-http} (default), {@code otlp-grpc}, {@code none}.
 *
 * @since 0.19.0
 */
public final class OtelTelemetryFactory {

    private static final String DEFAULT_ENDPOINT_HTTP = "http://localhost:4318/v1/traces";
    private static final String DEFAULT_ENDPOINT_GRPC = "http://localhost:4317";
    private static final String DEFAULT_SERVICE_NAME  = "agenor";

    private OtelTelemetryFactory() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Returns a new {@link Builder}.
     *
     * @return a new builder; never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an {@link OtelJenticTelemetry} reading configuration entirely from
     * standard OTel environment variables:
     * <ul>
     *   <li>{@code OTEL_SERVICE_NAME} — service name (default: {@code agenor})</li>
     *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — collector URL (default: {@code http://localhost:4318})</li>
     *   <li>{@code OTEL_EXPORTER_TYPE} — {@code otlp-http} | {@code otlp-grpc} | {@code none} (default: {@code otlp-http})</li>
     * </ul>
     *
     * @return a configured {@link JenticTelemetry}; never {@code null}
     */
    public static JenticTelemetry fromEnvironment() {
        String serviceName = System.getenv().getOrDefault("OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME);
        String endpoint    = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_ENDPOINT_HTTP);
        String type        = System.getenv().getOrDefault("OTEL_EXPORTER_TYPE", "otlp-http");
        return builder()
                .serviceName(serviceName)
                .endpoint(endpoint)
                .exporter(type)
                .build();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link OtelJenticTelemetry}.
     */
    public static final class Builder {

        private String serviceName = DEFAULT_SERVICE_NAME;
        private String exporter    = "otlp-http";
        private String endpoint    = null; // resolved per-exporter if null

        private Builder() {}

        /**
         * Sets the service name reported in all spans (OTel {@code service.name} resource attribute).
         *
         * @param serviceName the service name; never {@code null}
         * @return {@code this}
         */
        public Builder serviceName(String serviceName) {
            this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
            return this;
        }

        /**
         * Sets the exporter type.
         *
         * @param exporter {@code "otlp-http"} (default), {@code "otlp-grpc"}, or {@code "none"}
         * @return {@code this}
         */
        public Builder exporter(String exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
            return this;
        }

        /**
         * Sets the OTLP collector endpoint URL.
         *
         * <p>For HTTP exporters this must be the full URL including the signal path,
         * e.g. {@code http://localhost:4318/v1/traces}. A bare base URL such as
         * {@code http://localhost:4318} is also accepted — the factory appends
         * {@code /v1/traces} automatically when no {@code /v1/} path is present.
         * This mirrors the behaviour of the standard {@code OTEL_EXPORTER_OTLP_ENDPOINT}
         * environment variable, which conventionally carries only the host and port.
         *
         * <p>For gRPC exporters pass only the base URL, e.g. {@code http://localhost:4317}.
         *
         * @param endpoint the endpoint URL, or {@code null} to use the default
         * @return {@code this}
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Builds and returns the {@link JenticTelemetry} instance.
         *
         * <p>When {@code exporter} is {@code "none"}, returns {@link JenticTelemetry#noop()}.
         *
         * @return a configured {@link JenticTelemetry}; never {@code null}
         */
        public JenticTelemetry build() {
            if ("none".equalsIgnoreCase(exporter)) {
                return JenticTelemetry.noop();
            }

            Resource resource = Resource.getDefault().merge(
                    Resource.create(Attributes.of(
                            AttributeKey.stringKey("service.name"), serviceName
                    )));

            SpanExporter spanExporter = buildExporter();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                    .setResource(resource)
                    .build();

            OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();

            return new OtelJenticTelemetry(openTelemetry);
        }

        private SpanExporter buildExporter() {
            return switch (exporter.toLowerCase()) {
                case "otlp-grpc" -> {
                    String ep = endpoint != null ? endpoint : DEFAULT_ENDPOINT_GRPC;
                    yield OtlpGrpcSpanExporter.builder().setEndpoint(ep).build();
                }
                default -> { // "otlp-http" and anything else
                    // OtlpHttpSpanExporter.setEndpoint() takes the full URL — it does NOT
                    // auto-append /v1/traces (unlike the OTel SDK's own default).
                    // The standard OTEL_EXPORTER_OTLP_ENDPOINT env var is typically a base
                    // URL (host:port only), so we append the signal path when it is absent.
                    String ep = endpoint != null ? endpoint : DEFAULT_ENDPOINT_HTTP;
                    if (!ep.contains("/v1/")) {
                        ep = ep.replaceAll("/+$", "") + "/v1/traces";
                    }
                    yield OtlpHttpSpanExporter.builder().setEndpoint(ep).build();
                }
            };
        }
    }
}
