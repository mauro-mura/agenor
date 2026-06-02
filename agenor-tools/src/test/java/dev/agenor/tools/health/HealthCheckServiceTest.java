package dev.agenor.tools.health;

import dev.agenor.core.Agent;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HealthCheckServiceTest {

    @Mock
    private AgenorRuntime runtime;

    @Mock
    private Agent runningAgent;

    @Mock
    private Agent stoppedAgent;

    private HealthCheckService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(runtime.isRunning()).thenReturn(true);
        when(runningAgent.isRunning()).thenReturn(true);
        when(stoppedAgent.isRunning()).thenReturn(false);

        service = new HealthCheckService(runtime);
    }

    @Test
    void shouldReturnUpWhenRuntimeIsRunning() {
        when(runtime.getAgents()).thenReturn(List.of(runningAgent));

        var report = service.check();

        assertThat(report.isHealthy()).isTrue();
        assertThat(report.status().state())
                .isEqualTo(HealthCheckService.HealthStatus.State.UP);
    }

    @Test
    void shouldReturnDownWhenRuntimeIsNotRunning() {
        when(runtime.isRunning()).thenReturn(false);
        when(runtime.getAgents()).thenReturn(List.of());

        var report = service.check();

        assertThat(report.status().isDown()).isTrue();
    }

    @Test
    void shouldIncludeAllDefaultIndicators() {
        when(runtime.getAgents()).thenReturn(List.of());

        var report = service.check();

        assertThat(report.components()).containsKey("runtime");
        assertThat(report.components()).containsKey("memory");
        assertThat(report.components()).containsKey("agents");
        assertThat(report.components()).containsKey("threads");
    }

    @Test
    void shouldCheckSpecificIndicator() {
        var status = service.check("runtime");

        assertThat(status.isUp()).isTrue();
    }

    @Test
    void shouldReturnUnknownForMissingIndicator() {
        var status = service.check("nonexistent");

        assertThat(status.state())
                .isEqualTo(HealthCheckService.HealthStatus.State.UNKNOWN);
    }

    @Test
    void shouldRegisterCustomIndicator() {
        service.registerIndicator("custom", () ->
                HealthCheckService.HealthStatus.up().withDetail("test", "value"));

        when(runtime.getAgents()).thenReturn(List.of());

        var report = service.check();

        assertThat(report.components()).containsKey("custom");
        assertThat(report.components().get("custom").isUp()).isTrue();
    }

    @Test
    void shouldUnregisterIndicator() {
        service.registerIndicator("temp", HealthCheckService.HealthStatus::up);
        service.unregisterIndicator("temp");

        when(runtime.getAgents()).thenReturn(List.of());

        var report = service.check();

        assertThat(report.components()).doesNotContainKey("temp");
    }

    @Test
    void shouldConvertReportToMap() {
        when(runtime.getAgents()).thenReturn(List.of(runningAgent));

        var report = service.check();
        var map = report.toMap();

        assertThat(map).containsKey("status");
        assertThat(map).containsKey("uptime");
        assertThat(map).containsKey("components");
    }

    @Test
    void shouldIncludeDetailsInStatus() {
        var status = HealthCheckService.HealthStatus.up()
                .withDetail("key1", "value1")
                .withDetail("key2", 42);

        assertThat(status.details()).containsEntry("key1", "value1");
        assertThat(status.details()).containsEntry("key2", 42);
    }
}
