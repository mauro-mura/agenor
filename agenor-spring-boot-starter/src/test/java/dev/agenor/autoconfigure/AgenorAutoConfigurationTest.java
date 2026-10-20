package dev.agenor.autoconfigure;

import dev.agenor.core.AgenorConfiguration;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgenorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    // --- bean creation ---

    @Test
    void runtimeBeanIsCreated() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AgenorRuntime.class));
    }

    @Test
    void lifecycleBeanIsCreated() {
        runner.run(ctx -> assertThat(ctx.getBean("AgenorRuntimeLifecycle"))
                .isInstanceOf(SmartLifecycle.class));
    }

    // --- lifecycle correctness ---

    @Test
    void lifecycleStartCallsRuntimeStart() {
        runner.run(ctx -> {
            AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
            SmartLifecycle lifecycle = (SmartLifecycle) ctx.getBean("AgenorRuntimeLifecycle");

            // start() is already called by Spring context refresh — runtime must be running
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(runtime.isRunning()).isTrue();
        });
    }

    @Test
    void lifecyclePhaseIsHighValue() {
        runner.run(ctx -> {
            SmartLifecycle lifecycle = (SmartLifecycle) ctx.getBean("AgenorRuntimeLifecycle");
            assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);
        });
    }

    // --- user override ---

    @Test
    void userRuntimeBeanOverridesAutoConfigured() {
        runner.withUserConfiguration(UserRuntimeConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(AgenorRuntime.class);
            // The user bean is the one present — confirm by checking the custom name
            AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
            assertThat(runtime.getConfiguration().runtime().name()).isEqualTo("user-runtime");
        });
    }

    @Test
    void userRuntimeBeanIsStartedOnceByTheStarterLifecycle() {
        // docs/spring-boot-starter.md used to ask for a lifecycle bean of your own next to the
        // runtime. The starter's lifecycle bean starts whatever AgenorRuntime is in the context,
        // so a second one only meant a second start().
        runner.withUserConfiguration(UserRuntimeConfig.class).run(ctx -> {
            AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
            assertThat(runtime.isRunning()).isTrue();
            assertThat(ctx.getBeansOfType(SmartLifecycle.class)).containsOnlyKeys("AgenorRuntimeLifecycle");
            verify(runtime, times(1)).start();
        });
    }

    // --- LLM wiring ---

    @Test
    void noLlmBeanInContextWhenProviderIsNone() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LLMProvider.class));
    }

    @Test
    void llmProviderBeanIsWiredIntoRuntimeWhenPresent() {
        runner.withUserConfiguration(UserLlmProviderConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(LLMProvider.class);
            // Runtime must have been built — no exception during wiring
            assertThat(ctx).hasSingleBean(AgenorRuntime.class);
        });
    }

    // --- HITL wiring ---

    @Test
    void jdbcApprovalGateActivatedWhenProviderIsJdbc() {
        runner.withPropertyValues(
                        "agenor.hitl.provider=jdbc",
                        "agenor.hitl.jdbc.url=jdbc:h2:mem:hitl_ac_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ApprovalGate.class);
                    assertThat(ctx.getBean(ApprovalGate.class))
                            .isInstanceOf(dev.agenor.adapters.persistence.hitl.JdbcApprovalGate.class);
                });
    }

    @Test
    void jdbcApprovalGateSharesTheDirectoryDatabaseWhenItHasNoUrlOfItsOwn() {
        // The documented fallback: HITL reuses agenor.directory.jdbc.url, so both schemas are
        // migrated into one database. Up to 0.37.0 the second migration failed validation.
        runner.withPropertyValues(
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.jdbc.url=jdbc:h2:mem:shared_ac_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "agenor.hitl.provider=jdbc")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(
                            dev.agenor.adapters.persistence.directory.JdbcAgentDirectory.class);
                    assertThat(ctx.getBean(ApprovalGate.class))
                            .isInstanceOf(dev.agenor.adapters.persistence.hitl.JdbcApprovalGate.class);
                });
    }

    @Test
    void inMemoryApprovalGateIsDefaultWhenHitlProviderNotSet() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ApprovalGate.class));
    }

    // --- a provider named without its module fails startup ---

    @Test
    void startupFailsWhenJdbcDirectoryIsConfiguredWithoutThePersistenceModule() {
        runner.withClassLoader(new FilteredClassLoader("dev.agenor.adapters.persistence"))
                .withPropertyValues("agenor.directory.provider=jdbc",
                        "agenor.directory.jdbc.url=jdbc:h2:mem:missing_module;DB_CLOSE_DELAY=-1")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("agenor.directory.provider=jdbc requires 'agenor-adapters-persistence'"));
    }

    @Test
    void startupFailsWhenJdbcHitlIsConfiguredWithoutThePersistenceModule() {
        runner.withClassLoader(new FilteredClassLoader("dev.agenor.adapters.persistence"))
                .withPropertyValues("agenor.hitl.provider=jdbc")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("agenor.hitl.provider=jdbc requires 'agenor-adapters-persistence'"));
    }

    @Test
    void startupFailsWhenATelemetryExporterIsConfiguredWithoutTheAdapters() {
        runner.withClassLoader(new FilteredClassLoader("dev.agenor.adapters"))
                .withPropertyValues("agenor.telemetry.exporter=otlp-http")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("agenor.telemetry.exporter=otlp-http requires 'agenor-adapters'"));
    }

    @Test
    void bothJdbcProvidersAreNamedWhenThePersistenceModuleIsMissing() {
        runner.withClassLoader(new FilteredClassLoader("dev.agenor.adapters.persistence"))
                .withPropertyValues("agenor.directory.provider=jdbc", "agenor.hitl.provider=jdbc",
                        "agenor.directory.jdbc.url=jdbc:h2:mem:missing_both;DB_CLOSE_DELAY=-1")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("agenor.directory.provider=jdbc and agenor.hitl.provider=jdbc"));
    }

    @Test
    void defaultsAndDisabledTelemetryNeedNoModule() {
        runner.withClassLoader(new FilteredClassLoader("dev.agenor.adapters", "io.lettuce"))
                .withPropertyValues("agenor.telemetry.exporter=otlp-http", "agenor.telemetry.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // --- test support configs ---

    @Configuration
    static class UserRuntimeConfig {
        @Bean
        AgenorRuntime AgenorRuntime() {
            return spy(AgenorRuntime.builder()
                    .withConfiguration(new AgenorConfiguration(
                            new AgenorConfiguration.RuntimeConfig(
                                    "user-runtime", "test", null),
                            null, null, null, null))
                    .build());
        }
    }

    @Configuration
    static class UserLlmProviderConfig {
        @Bean
        LLMProvider llmProvider() {
            LLMProvider mock = mock(LLMProvider.class);
            when(mock.getProviderName()).thenReturn("mock");
            return mock;
        }
    }
}
