package dev.jentic.core.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the OpenTelemetry SDK is NOT present on the {@code jentic-core}
 * compile/test classpath.
 *
 * <p>This test acts as the CI-enforced proof that consumers who depend only on
 * {@code jentic-core} (or {@code jentic-runtime}) without explicitly declaring
 * an OTel dependency will not encounter {@link ClassNotFoundException} at runtime
 * — they get {@link NoopJenticTelemetry} by default.
 *
 * <p>The test also confirms the noop lifecycle works end-to-end without any
 * OTel import.
 */
@DisplayName("Telemetry classpath isolation")
class TelemetryClasspathIsolationTest {

    // -------------------------------------------------------------------------
    // OTel absent from jentic-core classpath
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("io.opentelemetry.api.OpenTelemetry is NOT on the jentic-core classpath")
    void otelApi_notOnCoreClasspath() {
        assertThatThrownBy(() -> Class.forName("io.opentelemetry.api.OpenTelemetry"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("io.opentelemetry.sdk.OpenTelemetrySdk is NOT on the jentic-core classpath")
    void otelSdk_notOnCoreClasspath() {
        assertThatThrownBy(() -> Class.forName("io.opentelemetry.sdk.OpenTelemetrySdk"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // NoopJenticTelemetry works without OTel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("full noop telemetry lifecycle does not throw when OTel is absent")
    void noopTelemetry_fullLifecycle_doesNotThrow() {
        JenticTelemetry noop = JenticTelemetry.noop();

        assertThatCode(() -> {
            Span span = noop.spanBuilder("test.isolation.op")
                    .setAttribute("key", "value")
                    .setAttribute("count", 42L)
                    .setAttribute("enabled", true)
                    .startSpan();

            span.setAttribute("latency_ms", 5L)
                .recordException(new RuntimeException("simulated error"))
                .setStatus(SpanStatus.ERROR)
                .end();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("JenticTelemetry.noop() resolves to NoopJenticTelemetry with no OTel on classpath")
    void noopTelemetry_classLoadedFromCoreAlone() {
        assertThat(JenticTelemetry.noop())
                .isInstanceOf(NoopJenticTelemetry.class);
    }
}
