package dev.agenor.runtime.agent;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.reflection.CritiqueResult;
import dev.agenor.core.reflection.ReflectionConfig;
import dev.agenor.core.reflection.ReflectionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for the reflection integration added to {@link LLMAgent}.
 */
@ExtendWith(MockitoExtension.class)
class LLMAgentReflectionTest {

    @Mock
    private LLMProvider mockProvider;

    // -------------------------------------------------------------------------
    // Minimal concrete subclass for testing
    // -------------------------------------------------------------------------

    /** Minimal concrete LLMAgent for testing — exposes protected helpers. */
    private static class TestAgent extends LLMAgent {
        TestAgent() { super("test-agent"); }

        void registerProvider(LLMProvider p) { setLLMProvider(p); }

        CompletableFuture<CritiqueResult> doReflect(String output, String task) {
            return reflect(output, task);
        }

        CompletableFuture<CritiqueResult> doReflect(
                String output, String task, ReflectionConfig config) {
            return reflect(output, task, config);
        }
    }

    // -------------------------------------------------------------------------
    // Default state — no reflection configured
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("No reflection configured")
    class NoReflectionConfigured {

        @Test
        @DisplayName("hasReflection() returns false")
        void hasReflection_false() {
            TestAgent agent = new TestAgent();
            assertThat(agent.hasReflection()).isFalse();
        }

        @Test
        @DisplayName("reflect() throws IllegalStateException")
        void reflect_throwsIllegalState() {
            TestAgent agent = new TestAgent();
            assertThatThrownBy(() -> agent.doReflect("output", "task").join())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("setReflectionStrategy")
                    .hasMessageContaining("setLLMProvider");
        }

        @Test
        @DisplayName("getReflectionStrategy() returns null")
        void getReflectionStrategy_null() {
            TestAgent agent = new TestAgent();
            assertThat(agent.getReflectionStrategy()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Explicit ReflectionStrategy set
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Explicit ReflectionStrategy configured")
    class ExplicitStrategy {

        @Test
        @DisplayName("hasReflection() returns true")
        void hasReflection_true() {
            TestAgent agent = new TestAgent();
            agent.setReflectionStrategy(fixedStrategy(CritiqueResult.accepted(0.9)));
            assertThat(agent.hasReflection()).isTrue();
        }

        @Test
        @DisplayName("reflect() delegates to the configured strategy")
        void reflect_usesConfiguredStrategy() {
            TestAgent agent = new TestAgent();
            agent.setReflectionStrategy(fixedStrategy(CritiqueResult.accepted(0.9)));

            CritiqueResult result = agent.doReflect("output", "task").join();

            assertThat(result.shouldRevise()).isFalse();
            assertThat(result.score()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("setReflectionStrategy(null) reverts to no-strategy state")
        void setNull_revertsToNoStrategy() {
            TestAgent agent = new TestAgent();
            agent.setReflectionStrategy(fixedStrategy(CritiqueResult.accepted(0.9)));
            agent.setReflectionStrategy(null);

            assertThat(agent.hasReflection()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Auto-default via setLLMProvider
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Auto-default via LLMProvider")
    class AutoDefaultProvider {

        @Test
        @DisplayName("hasReflection() returns true after setLLMProvider")
        void hasReflection_trueAfterProvider() {
            TestAgent agent = new TestAgent();
            agent.registerProvider(mockProvider);
            assertThat(agent.hasReflection()).isTrue();
        }

        @Test
        @DisplayName("reflect() auto-creates DefaultReflectionStrategy")
        void reflect_autoCreatesDefaultStrategy() {
            mockProviderResponse("Needs improvement.\nscore: 0.5");

            TestAgent agent = new TestAgent();
            agent.registerProvider(mockProvider);

            CritiqueResult result = agent.doReflect("weak output", "task").join();

            assertThat(result.shouldRevise()).isTrue();
            assertThat(result.score()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("explicit strategy takes precedence over provider")
        void explicitStrategyTakesPrecedence() {
            TestAgent agent = new TestAgent();
            agent.registerProvider(mockProvider);
            agent.setReflectionStrategy(fixedStrategy(CritiqueResult.accepted(0.95)));

            CritiqueResult result = agent.doReflect("output", "task").join();

            assertThat(result.score()).isEqualTo(0.95);
        }
    }

    // -------------------------------------------------------------------------
    // reflect() overload with explicit config
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reflect(output, task, config) uses the provided ReflectionConfig")
    void reflect_withConfig_usesConfig() {
        ReflectionConfig customConfig = new ReflectionConfig(3, 0.95, null);
        TestAgent agent = new TestAgent();
        // strategy ignores config but receives it
        ReflectionStrategy capturingStrategy = (output, task, config) -> {
            assertThat(config.maxIterations()).isEqualTo(3);
            assertThat(config.scoreThreshold()).isEqualTo(0.95);
            return CompletableFuture.completedFuture(CritiqueResult.accepted(0.96));
        };
        agent.setReflectionStrategy(capturingStrategy);

        CritiqueResult result = agent.doReflect("output", "task", customConfig).join();
        assertThat(result.score()).isEqualTo(0.96);
    }

    // -------------------------------------------------------------------------
    // Backward compatibility — agent without reflection still works
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LLMAgent without reflection configured does not throw on construction")
    void noReflection_noExceptionOnConstruction() {
        // Must not throw — existing agents are unaffected
        TestAgent agent = new TestAgent();
        assertThat(agent).isNotNull();
        assertThat(agent.hasReflection()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ReflectionStrategy fixedStrategy(CritiqueResult fixed) {
        return (output, task, config) -> CompletableFuture.completedFuture(fixed);
    }

    private void mockProviderResponse(String content) {
        LLMResponse response = LLMResponse.builder("mock-id", "test-model")
                .content(content)
                .role(LLMMessage.Role.ASSISTANT)
                .build();
        when(mockProvider.chat(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
}
