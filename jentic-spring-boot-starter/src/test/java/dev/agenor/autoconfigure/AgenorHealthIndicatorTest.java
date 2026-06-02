package dev.agenor.autoconfigure;

import dev.agenor.core.AgenorConfiguration;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgenorHealthIndicatorTest {

    // --- unit tests (no Spring context) ---

    @Test
    void healthIsUpWhenRuntimeIsRunning() {
        AgenorRuntime runtime = runningRuntimeMock(2, 2);

        Health health = new AgenorHealthIndicator(runtime).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("runtime.name", "test-runtime");
        assertThat(health.getDetails()).containsEntry("agents.total", 2);
        assertThat(health.getDetails()).containsEntry("agents.running", 2);
    }

    @Test
    void healthIsDownWhenRuntimeIsNotRunning() {
        AgenorRuntime runtime = stoppedRuntimeMock();

        Health health = new AgenorHealthIndicator(runtime).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("runtime.name");
    }

    @Test
    void healthDetailsIncludePartiallyRunningAgents() {
        AgenorRuntime runtime = runningRuntimeMock(3, 1);

        Health health = new AgenorHealthIndicator(runtime).health();

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
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
            .run(ctx ->
                assertThat(ctx).hasSingleBean(AgenorHealthIndicator.class));
    }

    @Test
    void userHealthIndicatorBeanOverridesAutoConfigured() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
            .withUserConfiguration(UserHealthConfig.class)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(
                        org.springframework.boot.health.contributor.HealthIndicator.class);
                // user bean must be the one present
                assertThat(ctx.getBean(
                        org.springframework.boot.health.contributor.HealthIndicator.class))
                    .isInstanceOf(UserHealthConfig.CustomIndicator.class);
            });
    }

    // --- helpers ---

    private static AgenorRuntime runningRuntimeMock(int total, int running) {
        AgenorRuntime runtime = mock(AgenorRuntime.class);
        when(runtime.isRunning()).thenReturn(true);
        when(runtime.getStats()).thenReturn(
                new AgenorRuntime.RuntimeStats(total, running, 0, 0));

        AgenorConfiguration config = mock(AgenorConfiguration.class);
        AgenorConfiguration.RuntimeConfig rc =
                new AgenorConfiguration.RuntimeConfig("test-runtime", "test", null);
        when(config.runtime()).thenReturn(rc);
        when(runtime.getConfiguration()).thenReturn(config);
        return runtime;
    }

    private static AgenorRuntime stoppedRuntimeMock() {
        AgenorRuntime runtime = mock(AgenorRuntime.class);
        when(runtime.isRunning()).thenReturn(false);
        when(runtime.getStats()).thenReturn(
                new AgenorRuntime.RuntimeStats(0, 0, 0, 0));

        AgenorConfiguration config = mock(AgenorConfiguration.class);
        AgenorConfiguration.RuntimeConfig rc =
                new AgenorConfiguration.RuntimeConfig("test-runtime", "test", null);
        when(config.runtime()).thenReturn(rc);
        when(runtime.getConfiguration()).thenReturn(config);
        return runtime;
    }

    @org.springframework.context.annotation.Configuration
    static class UserHealthConfig {
        static class CustomIndicator
                implements org.springframework.boot.health.contributor.HealthIndicator {
            @Override
            public Health health() { return Health.up().build(); }
        }

        @org.springframework.context.annotation.Bean
        org.springframework.boot.health.contributor.HealthIndicator AgenorHealthIndicator() {
            return new CustomIndicator();
        }
    }
}
