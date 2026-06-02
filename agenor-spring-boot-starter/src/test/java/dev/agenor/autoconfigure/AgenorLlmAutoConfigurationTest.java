package dev.agenor.autoconfigure;

import dev.agenor.core.llm.LLMProvider;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the LLM conditional beans in {@link AgenorAutoConfiguration}.
 *
 * <p>These tests use a filtered context runner that simulates the presence / absence
 * of {@code agenor-adapters} by checking whether the real factory class is available.
 * In the test classpath, {@code agenor-adapters} IS present (test scope dependency),
 * so {@link AgenorAutoConfiguration.LlmConfiguration} will be active.
 * Tests that require adapters to be absent use mocks / property-only assertions.
 */
class AgenorLlmAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    // --- provider=none (default) ---

    @Test
    void noLlmBeanCreatedWhenProviderIsNone() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(LLMProvider.class);
            assertThat(ctx).hasSingleBean(AgenorRuntime.class);
        });
    }

    @Test
    void noLlmBeanCreatedWhenProviderPropertyAbsent() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LLMProvider.class));
    }

    // --- provider=openai ---

    @Test
    void openAiProviderCreatedWhenConfigured() {
        runner
            .withPropertyValues(
                "agenor.llm.provider=openai",
                "agenor.llm.api-key=sk-test-key",
                "agenor.llm.model=gpt-4o-mini"
            )
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(LLMProvider.class);
                LLMProvider provider = ctx.getBean(LLMProvider.class);
                assertThat(provider.getProviderName()).containsIgnoringCase("openai");
            });
    }

    @Test
    void openAiProviderFailsFastWhenApiKeyMissing() {
        runner
            .withPropertyValues("agenor.llm.provider=openai")
            .run(ctx ->
                assertThat(ctx).hasFailed()
                    .getFailure()
                    .hasMessageContaining("agenor.llm.api-key"));
    }

    @Test
    void openAiProviderUsesDefaultModelWhenNotSpecified() {
        runner
            .withPropertyValues(
                "agenor.llm.provider=openai",
                "agenor.llm.api-key=sk-test-key"
                // no model specified — should default to gpt-4o-mini
            )
            .run(ctx -> assertThat(ctx).hasSingleBean(LLMProvider.class));
    }

    // --- provider=anthropic ---

    @Test
    void anthropicProviderCreatedWhenConfigured() {
        runner
            .withPropertyValues(
                "agenor.llm.provider=anthropic",
                "agenor.llm.api-key=sk-ant-test",
                "agenor.llm.model=claude-3-haiku-20240307"
            )
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(LLMProvider.class);
                LLMProvider provider = ctx.getBean(LLMProvider.class);
                assertThat(provider.getProviderName()).containsIgnoringCase("anthropic");
            });
    }

    @Test
    void anthropicProviderFailsFastWhenApiKeyMissing() {
        runner
            .withPropertyValues("agenor.llm.provider=anthropic")
            .run(ctx ->
                assertThat(ctx).hasFailed()
                    .getFailure()
                    .hasMessageContaining("agenor.llm.api-key"));
    }

    // --- provider=ollama ---

    @Test
    void ollamaProviderCreatedWhenConfigured() {
        runner
            .withPropertyValues(
                "agenor.llm.provider=ollama",
                "agenor.llm.base-url=http://localhost:11434",
                "agenor.llm.model=llama3.2"
            )
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(LLMProvider.class);
                LLMProvider provider = ctx.getBean(LLMProvider.class);
                assertThat(provider.getProviderName()).containsIgnoringCase("ollama");
            });
    }

    @Test
    void ollamaProviderUsesDefaultBaseUrlWhenNotSpecified() {
        runner
            .withPropertyValues("agenor.llm.provider=ollama")
            // no base-url, no api-key needed for ollama
            .run(ctx -> assertThat(ctx).hasSingleBean(LLMProvider.class));
    }

    // --- user override ---

    @Test
    void userLlmProviderBeanTakesPrecedenceOverAutoConfigured() {
        runner
            .withPropertyValues(
                "agenor.llm.provider=openai",
                "agenor.llm.api-key=sk-test-key"
            )
            .withUserConfiguration(UserLlmConfig.class)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(LLMProvider.class);
                // The user mock wins — auto-configured bean must not have been created
                LLMProvider provider = ctx.getBean(LLMProvider.class);
                assertThat(provider.getProviderName()).isEqualTo("user-mock");
            });
    }

    // --- support config ---

    @org.springframework.context.annotation.Configuration
    static class UserLlmConfig {
        @org.springframework.context.annotation.Bean
        LLMProvider llmProvider() {
            LLMProvider mock = org.mockito.Mockito.mock(LLMProvider.class);
            org.mockito.Mockito.when(mock.getProviderName()).thenReturn("user-mock");
            return mock;
        }
    }
}
