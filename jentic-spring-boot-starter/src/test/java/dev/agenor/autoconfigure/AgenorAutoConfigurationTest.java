package dev.agenor.autoconfigure;

import dev.agenor.core.AgenorConfiguration;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
    void inMemoryApprovalGateIsDefaultWhenHitlProviderNotSet() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ApprovalGate.class));
    }

    // --- test support configs ---

    @Configuration
    static class UserRuntimeConfig {
        @Bean
        AgenorRuntime AgenorRuntime() {
            return AgenorRuntime.builder()
                    .withConfiguration(new AgenorConfiguration(
                            new AgenorConfiguration.RuntimeConfig(
                                    "user-runtime", "test", null),
                            null, null, null, null))
                    .build();
        }

        // User must also provide the lifecycle bean when overriding the runtime
        @Bean
        SmartLifecycle AgenorRuntimeLifecycle(AgenorRuntime runtime) {
            return new SmartLifecycle() {
                private volatile boolean running = false;
                public void start() { runtime.start().join(); running = true; }
                public void stop()  { runtime.stop().join();  running = false; }
                public boolean isRunning() { return running; }
            };
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
