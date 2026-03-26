package dev.jentic.autoconfigure;

import dev.jentic.core.llm.LLMProvider;
import dev.jentic.runtime.JenticRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the full auto-configuration wiring.
 *
 * <p>Each test simulates a realistic application scenario using
 * {@link ApplicationContextRunner} with the real {@link JenticAutoConfiguration}.
 * No mocks of Jentic internals — only the LLMProvider (external dependency) is mocked
 * where needed.
 */
class JenticStarterIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JenticAutoConfiguration.class));

    // -----------------------------------------------------------------------
    // Scenario 1: minimal application — no YAML config, zero-boilerplate
    // -----------------------------------------------------------------------

    @Test
    void minimalApp_allBeansPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(JenticRuntime.class);
            assertThat(ctx).hasSingleBean(JenticProperties.class);
            assertThat(ctx).hasSingleBean(JenticHealthIndicator.class);
            assertThat(ctx).doesNotHaveBean(LLMProvider.class);
        });
    }

    @Test
    void minimalApp_runtimeStartedAndHealthyAfterContextRefresh() {
        runner.run(ctx -> {
            JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
            SmartLifecycle lifecycle =
                    (SmartLifecycle) ctx.getBean("jenticRuntimeLifecycle");

            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(runtime.isRunning()).isTrue();

            Health health = ctx.getBean(JenticHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        });
    }

    // -----------------------------------------------------------------------
    // Scenario 2: custom runtime name and environment
    // -----------------------------------------------------------------------

    @Test
    void customRuntimeName_propagatedToConfiguration() {
        runner
            .withPropertyValues(
                "jentic.runtime.name=my-prod-system",
                "jentic.runtime.environment=production"
            )
            .run(ctx -> {
                JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
                assertThat(runtime.getConfiguration().runtime().name())
                        .isEqualTo("my-prod-system");
                assertThat(runtime.getConfiguration().runtime().environment())
                        .isEqualTo("production");

                // Health indicator must reflect the name
                Health health = ctx.getBean(JenticHealthIndicator.class).health();
                assertThat(health.getDetails()).containsEntry("runtime.name", "my-prod-system");
            });
    }

    // -----------------------------------------------------------------------
    // Scenario 3: base-package set via YAML
    // -----------------------------------------------------------------------

    @Test
    void basePackage_reachesRuntimeConfiguration() {
        runner
            .withPropertyValues("jentic.agents.base-package=com.example.agents")
            .run(ctx -> {
                JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
                assertThat(runtime.getConfiguration().agents().getAllScanPackages())
                        .contains("com.example.agents");
            });
    }

    // -----------------------------------------------------------------------
    // Scenario 4: custom thread-pool-size
    // -----------------------------------------------------------------------

    @Test
    void schedulerThreadPoolSize_propagatedToConfiguration() {
        runner
            .withPropertyValues("jentic.scheduler.thread-pool-size=20")
            .run(ctx -> {
                JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
                assertThat(runtime.getConfiguration().scheduler().threadPoolSize())
                        .isEqualTo(20);
            });
    }

    // -----------------------------------------------------------------------
    // Scenario 5: lifecycle — start and stop are synchronous
    // -----------------------------------------------------------------------

    @Test
    void lifecycle_runtimeIsNotRunningAfterContextClose() {
        runner.run(ctx -> {
            JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
            assertThat(runtime.isRunning()).isTrue();

            // Closing the context triggers SmartLifecycle.stop()
            ((org.springframework.context.ConfigurableApplicationContext) ctx)
                    .close();

            assertThat(runtime.isRunning()).isFalse();
        });
    }

    // -----------------------------------------------------------------------
    // Scenario 6: health details
    // -----------------------------------------------------------------------

    @Test
    void healthIndicator_detailKeysAlwaysPresent() {
        runner.run(ctx -> {
            Health health = ctx.getBean(JenticHealthIndicator.class).health();
            assertThat(health.getDetails()).containsKeys(
                    "runtime.name", "agents.total", "agents.running");
        });
    }

    // -----------------------------------------------------------------------
    // Scenario 7: ollama provider wired end-to-end (no API key required)
    // -----------------------------------------------------------------------

    @Test
    void ollamaProvider_wiresIntoRuntime() {
        runner
            .withPropertyValues(
                "jentic.llm.provider=ollama",
                "jentic.llm.model=llama3.2"
            )
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(LLMProvider.class);
                assertThat(ctx).hasSingleBean(JenticRuntime.class);
                // Runtime must still start cleanly with the provider wired
                assertThat(ctx.getBean(JenticRuntime.class).isRunning()).isTrue();
            });
    }
}