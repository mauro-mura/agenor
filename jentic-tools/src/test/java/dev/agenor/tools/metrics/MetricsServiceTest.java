package dev.agenor.tools.metrics;

import dev.agenor.core.Agent;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MetricsServiceTest {

    @Mock
    private AgenorRuntime runtime;

    @Mock
    private Agent agent;

    private MetricsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(runtime.isRunning()).thenReturn(true);
        when(runtime.getAgents()).thenReturn(List.of(agent));
        when(agent.isRunning()).thenReturn(true);

        service = new MetricsService(runtime);
    }

    @Test
    void shouldIncrementCounter() {
        service.increment("test.counter");
        service.increment("test.counter");
        service.increment("test.counter");

        assertThat(service.counter("test.counter").get()).isEqualTo(3);
    }

    @Test
    void shouldIncrementCounterByDelta() {
        service.increment("test.counter", 5);
        service.increment("test.counter", 3);

        assertThat(service.counter("test.counter").get()).isEqualTo(8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRegisterGauge() {
        service.registerGauge("test.gauge", () -> 42);

        var snapshot = service.snapshot();
        var gauges = (Map<String, Object>) snapshot.metrics().get("gauges");

        assertThat(gauges).containsEntry("test.gauge", 42);
    }

    @Test
    void shouldRecordTimerDuration() throws Exception {
        try (var context = service.startTimer("test.timer")) {
            Thread.sleep(10);
        }

        var timer = service.timer("test.timer");
        var map = timer.toMap();

        assertThat((Long) map.get("count")).isEqualTo(1);
        assertThat((Double) map.get("meanMs")).isGreaterThan(0);
    }

    @Test
    void shouldRecordHistogramValues() {
        service.record("test.histogram", 10);
        service.record("test.histogram", 20);
        service.record("test.histogram", 30);

        var histogram = service.histogram("test.histogram");
        var map = histogram.toMap();

        assertThat((Long) map.get("count")).isEqualTo(3);
        assertThat((Long) map.get("sum")).isEqualTo(60);
        assertThat((Double) map.get("mean")).isEqualTo(20.0);
        assertThat((Long) map.get("min")).isEqualTo(10);
        assertThat((Long) map.get("max")).isEqualTo(30);
    }

    @Test
    void shouldCreateSnapshot() {
        service.increment("messages.sent", 100);
        service.registerGauge("custom.value", () -> "test");

        var snapshot = service.snapshot();

        assertThat(snapshot.metrics()).containsKey("uptime");
        assertThat(snapshot.metrics()).containsKey("runtimeRunning");
        assertThat(snapshot.metrics()).containsKey("gauges");
        assertThat(snapshot.metrics()).containsKey("gc");
        assertThat(snapshot.timestamp()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeDefaultGauges() {
        var snapshot = service.snapshot();
        var gauges = (Map<String, Object>) snapshot.metrics().get("gauges");

        assertThat(gauges).containsKey("agents.total");
        assertThat(gauges).containsKey("agents.running");
        assertThat(gauges).containsKey("jvm.memory.used");
        assertThat(gauges).containsKey("jvm.threads.count");
    }

    @Test
    void shouldConvertSnapshotToMap() {
        var snapshot = service.snapshot();
        var map = snapshot.toMap();

        assertThat(map).containsKey("timestamp");
        assertThat(map).containsKey("gauges");
    }

    @Test
    void shouldResetCounter() {
        service.increment("reset.test", 50);
        service.counter("reset.test").reset();

        assertThat(service.counter("reset.test").get()).isEqualTo(0);
    }

    @Test
    void shouldTrackMinMaxInTimer() throws Exception {
        // Record multiple durations
        for (int i = 0; i < 5; i++) {
            try (var context = service.startTimer("minmax.timer")) {
                Thread.sleep(5 + i * 5);
            }
        }

        var map = service.timer("minmax.timer").toMap();

        assertThat((Long) map.get("count")).isEqualTo(5);
        assertThat((Double) map.get("minMs")).isGreaterThan(0);
        assertThat((Double) map.get("maxMs")).isGreaterThan((Double) map.get("minMs"));
    }
}
