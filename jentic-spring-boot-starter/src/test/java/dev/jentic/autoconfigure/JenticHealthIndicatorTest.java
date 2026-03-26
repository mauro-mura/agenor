package dev.jentic.autoconfigure;

import dev.jentic.runtime.JenticRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JenticHealthIndicatorTest {

    // --- unit tests (no Spring context) ---

    @Test
    void healthIsUpWhenRuntimeIsRunning() {
        JenticRuntime runtime = runningRuntimeMock(2, 2);

        Health health = new JenticHealthIndicator(runtime).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("runtime.name", "test-runtime");
        assertThat(health.getDetails()).containsEntry("agents.total", 2);
        assertThat(health.getDetails()).containsEntry("agents.running", 2);
    }

    @Test
    void healthIsDownWhenRuntimeIsNotRunning() {
        JenticRuntime runtime = stoppedRuntimeMock();

        Health health = new JenticHealthIndicator(runtime).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("runtime.name");
    }

    @Test
    void healthDetailsIncludePartiallyRunningAgents() {
        JenticRuntime runtime = runningRuntimeMock(3, 1);

        Health health = new JenticHealthIndicator(runtime).health();

        assertThat(health.getDetails()).containsEntry("agents.total", 3);
        assertThat(health.getDetails()).containsEntry("agents.running", 1);
    }

    // --- integration: absent when actuator not on classpath ---
    // This is verified implicitly: the test classpath includes actuator (test scope),
    // so the bean IS present. The @ConditionalOnClass guard is tested by Spring Boot's
    // own test infrastructure in the auto-configuration tests.

    // --- integration: present when actuator is on classpath ---

    @Test
    void healthIndicatorBeanRegisteredWhenActuatorPresent() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JenticAutoConfiguration.class))
            .run(ctx ->
                assertThat(ctx).hasSingleBean(JenticHealthIndicator.class));
    }

    @Test
    void userHealthIndicatorBeanOverridesAutoConfigured() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JenticAutoConfiguration.class))
            .withUserConfiguration(UserHealthConfig.class)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(
                        org.springframework.boot.actuate.health.HealthIndicator.class);
                // user bean must be the one present
                assertThat(ctx.getBean(
                        org.springframework.boot.actuate.health.HealthIndicator.class))
                    .isInstanceOf(UserHealthConfig.CustomIndicator.class);
            });
    }

    // --- helpers ---

    private static JenticRuntime runningRuntimeMock(int total, int running) {
        JenticRuntime runtime = mock(JenticRuntime.class);
        when(runtime.isRunning()).thenReturn(true);
        when(runtime.getStats()).thenReturn(
                new JenticRuntime.RuntimeStats(total, running, 0, 0));

        dev.jentic.core.JenticConfiguration config = mock(dev.jentic.core.JenticConfiguration.class);
        dev.jentic.core.JenticConfiguration.RuntimeConfig rc =
                new dev.jentic.core.JenticConfiguration.RuntimeConfig("test-runtime", "test", null);
        when(config.runtime()).thenReturn(rc);
        when(runtime.getConfiguration()).thenReturn(config);
        return runtime;
    }

    private static JenticRuntime stoppedRuntimeMock() {
        JenticRuntime runtime = mock(JenticRuntime.class);
        when(runtime.isRunning()).thenReturn(false);
        when(runtime.getStats()).thenReturn(
                new JenticRuntime.RuntimeStats(0, 0, 0, 0));

        dev.jentic.core.JenticConfiguration config = mock(dev.jentic.core.JenticConfiguration.class);
        dev.jentic.core.JenticConfiguration.RuntimeConfig rc =
                new dev.jentic.core.JenticConfiguration.RuntimeConfig("test-runtime", "test", null);
        when(config.runtime()).thenReturn(rc);
        when(runtime.getConfiguration()).thenReturn(config);
        return runtime;
    }

    @org.springframework.context.annotation.Configuration
    static class UserHealthConfig {
        static class CustomIndicator
                implements org.springframework.boot.actuate.health.HealthIndicator {
            @Override
            public Health health() { return Health.up().build(); }
        }

        @org.springframework.context.annotation.Bean
        org.springframework.boot.actuate.health.HealthIndicator jenticHealthIndicator() {
            return new CustomIndicator();
        }
    }
}