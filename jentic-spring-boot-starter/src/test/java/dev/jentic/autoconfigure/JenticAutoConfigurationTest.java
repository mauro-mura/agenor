package dev.jentic.autoconfigure;

import dev.jentic.core.llm.LLMProvider;
import dev.jentic.runtime.JenticRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JenticAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JenticAutoConfiguration.class));

    // --- bean creation ---

    @Test
    void runtimeBeanIsCreated() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(JenticRuntime.class));
    }

    @Test
    void lifecycleBeanIsCreated() {
        runner.run(ctx -> assertThat(ctx.getBean("jenticRuntimeLifecycle"))
                .isInstanceOf(SmartLifecycle.class));
    }

    // --- lifecycle correctness ---

    @Test
    void lifecycleStartCallsRuntimeStart() {
        runner.run(ctx -> {
            JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
            SmartLifecycle lifecycle = (SmartLifecycle) ctx.getBean("jenticRuntimeLifecycle");

            // start() is already called by Spring context refresh — runtime must be running
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(runtime.isRunning()).isTrue();
        });
    }

    @Test
    void lifecyclePhaseIsHighValue() {
        runner.run(ctx -> {
            SmartLifecycle lifecycle = (SmartLifecycle) ctx.getBean("jenticRuntimeLifecycle");
            assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);
        });
    }

    // --- user override ---

    @Test
    void userRuntimeBeanOverridesAutoConfigured() {
        runner.withUserConfiguration(UserRuntimeConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(JenticRuntime.class);
            // The user bean is the one present — confirm by checking the custom name
            JenticRuntime runtime = ctx.getBean(JenticRuntime.class);
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
            assertThat(ctx).hasSingleBean(JenticRuntime.class);
        });
    }

    // --- test support configs ---

    @Configuration
    static class UserRuntimeConfig {
        @Bean
        JenticRuntime jenticRuntime() {
            return JenticRuntime.builder()
                    .withConfiguration(new dev.jentic.core.JenticConfiguration(
                            new dev.jentic.core.JenticConfiguration.RuntimeConfig(
                                    "user-runtime", "test", null),
                            null, null, null, null))
                    .build();
        }

        // User must also provide the lifecycle bean when overriding the runtime
        @Bean
        SmartLifecycle jenticRuntimeLifecycle(JenticRuntime runtime) {
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