package dev.agenor.runtime.reflection;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.reflection.CritiqueResult;
import dev.agenor.core.reflection.ReflectionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReflectionStrategyTest {

    @Mock
    private LLMProvider llmProvider;

    private DefaultReflectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DefaultReflectionStrategy(llmProvider);
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects null LLMProvider")
    void constructor_nullProvider_throwsNpe() {
        assertThatThrownBy(() -> new DefaultReflectionStrategy(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("llmProvider");
    }

    // -------------------------------------------------------------------------
    // Acceptable output (score >= threshold)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Acceptable output")
    class AcceptableOutput {

        @Test
        @DisplayName("score >= threshold → shouldRevise=false")
        void acceptableOutput_shouldNotRevise() {
            mockLlmResponse("The output is clear and accurate.\nscore: 0.9");

            CritiqueResult result = strategy
                    .critique("Great answer", "Explain AI", ReflectionConfig.defaults())
                    .join();

            assertThat(result.shouldRevise()).isFalse();
            assertThat(result.score()).isCloseTo(0.9, within(0.001));
            assertThat(result.feedback()).contains("score: 0.9");
        }

        @Test
        @DisplayName("score exactly at threshold → shouldRevise=false")
        void scoreAtThreshold_shouldNotRevise() {
            mockLlmResponse("Acceptable quality. score: 0.8");

            CritiqueResult result = strategy
                    .critique("output", "task", ReflectionConfig.defaults())
                    .join();

            assertThat(result.shouldRevise()).isFalse();
            assertThat(result.score()).isCloseTo(0.8, within(0.001));
        }
    }

    // -------------------------------------------------------------------------
    // Poor output (score < threshold)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Poor output")
    class PoorOutput {

        @Test
        @DisplayName("score < threshold → shouldRevise=true")
        void poorOutput_shouldRevise() {
            mockLlmResponse("The answer lacks examples and is too vague.\nscore: 0.5");

            CritiqueResult result = strategy
                    .critique("Vague answer", "Explain AI", ReflectionConfig.defaults())
                    .join();

            assertThat(result.shouldRevise()).isTrue();
            assertThat(result.score()).isCloseTo(0.5, within(0.001));
        }

        @Test
        @DisplayName("score = 0.0 → shouldRevise=true")
        void zeroScore_shouldRevise() {
            mockLlmResponse("Completely off-topic. score: 0.0");

            CritiqueResult result = strategy
                    .critique("wrong output", "task", ReflectionConfig.defaults())
                    .join();

            assertThat(result.shouldRevise()).isTrue();
            assertThat(result.score()).isCloseTo(0.0, within(0.001));
        }
    }

    // -------------------------------------------------------------------------
    // Score parsing edge cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Score parsing")
    class ScoreParsing {

        @Test
        @DisplayName("no score in response → fallback 0.5, shouldRevise=true")
        void noScoreInResponse_usesFallback() {
            mockLlmResponse("The output is fine but could be improved.");

            CritiqueResult result = strategy
                    .critique("output", "task", ReflectionConfig.defaults())
                    .join();

            assertThat(result.shouldRevise()).isTrue();
            assertThat(result.score()).isCloseTo(0.5, within(0.001));
        }

        @Test
        @DisplayName("score > 1.0 in response → clamped to 1.0")
        void scoreAboveOne_clamped() {
            assertThat(DefaultReflectionStrategy.extractScore("score: 1.5")).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("score < 0.0 in response → clamped to 0.0")
        void scoreBelowZero_clamped() {
            assertThat(DefaultReflectionStrategy.extractScore("score: -0.3")).isCloseTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("SCORE: uppercase pattern is recognised")
        void caseInsensitiveScore_parsed() {
            assertThat(DefaultReflectionStrategy.extractScore("SCORE: 0.7")).isCloseTo(0.7, within(0.001));
        }

        @Test
        @DisplayName("integer score (no decimal) is parsed correctly")
        void integerScore_parsed() {
            assertThat(DefaultReflectionStrategy.extractScore("score: 1")).isCloseTo(1.0, within(0.001));
        }
    }

    // -------------------------------------------------------------------------
    // Custom critique prompt
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("custom critiquePrompt is forwarded to LLM as-is")
    void customPrompt_usedVerbatim() {
        ReflectionConfig config = new ReflectionConfig(2, 0.8, "Custom prompt for evaluation.");
        mockLlmResponse("Looks good. score: 0.9");

        CritiqueResult result = strategy.critique("output", "task", config).join();

        assertThat(result.shouldRevise()).isFalse();
    }

    // -------------------------------------------------------------------------
    // LLMProvider exception propagation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LLMProvider exception propagates as CompletionException")
    void llmProviderThrows_exceptionPropagated() {
        when(llmProvider.chat(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("network error")));

        CompletableFuture<CritiqueResult> future = strategy
                .critique("output", "task", ReflectionConfig.defaults());

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasMessageContaining("network error");
    }

    // -------------------------------------------------------------------------
    // Null argument validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null originalOutput → NullPointerException")
    void nullOriginalOutput_throwsNpe() {
        assertThatThrownBy(() ->
                strategy.critique(null, "task", ReflectionConfig.defaults()).join())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null task → NullPointerException")
    void nullTask_throwsNpe() {
        assertThatThrownBy(() ->
                strategy.critique("output", null, ReflectionConfig.defaults()).join())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null config → NullPointerException")
    void nullConfig_throwsNpe() {
        assertThatThrownBy(() ->
                strategy.critique("output", "task", null).join())
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void mockLlmResponse(String content) {
        LLMResponse response = LLMResponse.builder("mock-id", "test-model")
                .content(content)
                .role(LLMMessage.Role.ASSISTANT)
                .build();
        when(llmProvider.chat(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
}
