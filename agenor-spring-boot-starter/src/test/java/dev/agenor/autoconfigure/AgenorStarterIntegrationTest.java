package dev.agenor.autoconfigure;

import dev.agenor.core.llm.LLMProvider;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the full auto-configuration wiring.
 *
 * <p>Each test simulates a realistic application scenario using
 * {@link ApplicationContextRunner} with the real {@link AgenorAutoConfiguration}.
 * No mocks of Agenor internals — only the LLMProvider (external dependency) is mocked
 * where needed.
 */
class AgenorStarterIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    // -----------------------------------------------------------------------
    // Scenario 1: minimal application — no YAML config, zero-boilerplate
    // -----------------------------------------------------------------------

    @Test
    void minimalApp_allBeansPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AgenorRuntime.class);
            assertThat(ctx).hasSingleBean(AgenorProperties.class);
            assertThat(ctx).hasSingleBean(AgenorHealthIndicator.class);
            assertThat(ctx).doesNotHaveBean(LLMProvider.class);
        });
    }

    @Test
    void minimalApp_runtimeStartedAndHealthyAfterContextRefresh() {
        runner.run(ctx -> {
            AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
            SmartLifecycle lifecycle =
                    (SmartLifecycle) ctx.getBean("AgenorRuntimeLifecycle");

            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(runtime.isRunning()).isTrue();

            Health health = ctx.getBean(AgenorHealthIndicator.class).health();
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
                "agenor.runtime.name=my-prod-system",
                "agenor.runtime.environment=production"
            )
            .run(ctx -> {
                AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
                assertThat(runtime.getConfiguration().runtime().name())
                        .isEqualTo("my-prod-system");
                assertThat(runtime.getConfiguration().runtime().environment())
                        .isEqualTo("production");

                // Health indicator must reflect the name
                Health health = ctx.getBean(AgenorHealthIndicator.class).health();
                assertThat(health.getDetails()).containsEntry("runtime.name", "my-prod-system");
            });
    }

    // -----------------------------------------------------------------------
    // Scenario 3: base-package set via YAML
    // -----------------------------------------------------------------------

    @Test
    void basePackage_reachesRuntimeConfiguration() {
        runner
            .withPropertyValues("agenor.agents.base-package=com.example.agents")
            .run(ctx -> {
                AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
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
            .withPropertyValues("agenor.scheduler.thread-pool-size=20")
            .run(ctx -> {
                AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
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
            AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
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
            Health health = ctx.getBean(AgenorHealthIndicator.class).health();
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
                "agenor.llm.provider=ollama",
                "agenor.llm.model=llama3.2"
            )
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(LLMProvider.class);
                assertThat(ctx).hasSingleBean(AgenorRuntime.class);
                // Runtime must still start cleanly with the provider wired
                assertThat(ctx.getBean(AgenorRuntime.class).isRunning()).isTrue();
            });
    }
}
