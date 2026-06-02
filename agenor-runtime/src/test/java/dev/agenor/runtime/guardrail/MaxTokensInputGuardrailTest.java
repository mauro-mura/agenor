package dev.agenor.runtime.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.memory.llm.TokenEstimator;
import dev.agenor.runtime.memory.llm.SimpleTokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static dev.agenor.runtime.guardrail.MaxTokensInputGuardrail.TruncationStrategy.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("MaxTokensInputGuardrail")
class MaxTokensInputGuardrailTest {

    private static final GuardrailContext CTX = GuardrailContext.of("test-agent");

    /** SimpleTokenEstimator: tokens = max(1, chars / 4). */
    private static final TokenEstimator EST = new SimpleTokenEstimator();

    // -------------------------------------------------------------------------
    // Helper: generate a string of exactly `chars` characters
    // -------------------------------------------------------------------------
    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }

    // -------------------------------------------------------------------------
    // Within budget → Passed
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Input within token limit")
    class WithinBudgetTests {

        @Test
        @DisplayName("exactly at limit → Passed")
        void exactlyAtLimit_passed() {
            // 40 chars → 10 tokens; limit = 10
            String input = repeat('a', 40);
            var g = new MaxTokensInputGuardrail(10);
            assertThat(g.evaluate(input)).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("well below limit → Passed")
        void belowLimit_passed() {
            var g = new MaxTokensInputGuardrail(100);
            assertThat(g.evaluate("Hello!")).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("null input → Passed (treated as empty)")
        void nullInput_passed() {
            var g = new MaxTokensInputGuardrail(10);
            assertThat(g.evaluate(null)).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("empty string → Passed")
        void emptyInput_passed() {
            var g = new MaxTokensInputGuardrail(10);
            assertThat(g.evaluate("")).isInstanceOf(GuardrailResult.Passed.class);
        }
    }

    // -------------------------------------------------------------------------
    // Over budget → Modified with truncated content
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Input over token limit → Modified")
    class OverBudgetTests {

        @Test
        @DisplayName("over limit → Modified")
        void overLimit_modified() {
            // 200 chars → 50 tokens; limit = 10
            String input = repeat('x', 200);
            var g = new MaxTokensInputGuardrail(10);
            var result = g.evaluate(input);
            assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
        }

        @Test
        @DisplayName("Modified content is strictly shorter than original")
        void modifiedContent_shorterThanOriginal() {
            String input = repeat('x', 200);
            var g = new MaxTokensInputGuardrail(10);
            String truncated = ((GuardrailResult.Modified) g.evaluate(input)).newContent();
            assertThat(truncated.length()).isLessThan(input.length());
        }

        @Test
        @DisplayName("Modified content token count is within limit")
        void modifiedContent_withinTokenLimit() {
            String input = repeat('a', 400); // 100 tokens
            var g = new MaxTokensInputGuardrail(20);
            String truncated = ((GuardrailResult.Modified) g.evaluate(input)).newContent();
            int tokens = EST.estimateTokens(truncated);
            assertThat(tokens).isLessThanOrEqualTo(20);
        }
    }

    // -------------------------------------------------------------------------
    // Strategy: END
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TruncationStrategy.END")
    class EndStrategyTests {

        @Test
        @DisplayName("END keeps beginning, drops tail")
        void end_keepsBeginning() {
            // input = "AAAA...BBBB..." — 200 A's then 200 B's, limit = 25 tokens (100 chars)
            String input = repeat('A', 200) + repeat('B', 200);
            String truncated = MaxTokensInputGuardrail.truncate(input, 25, END, EST);
            assertThat(truncated).startsWith("AAAA");
            assertThat(truncated).doesNotContain("B");
        }

        @Test
        @DisplayName("END: truncated content starts with the beginning of original")
        void end_prefixPreserved() {
            String input = "Hello World " + repeat('x', 200);
            String truncated = MaxTokensInputGuardrail.truncate(input, 5, END, EST);
            assertThat(input).startsWith(truncated.substring(0, Math.min(5, truncated.length())));
        }

        @Test
        @DisplayName("END and START produce different results on asymmetric input")
        void end_differsFromStart() {
            String input = "BEGIN" + repeat('x', 200) + "END";
            String endResult   = MaxTokensInputGuardrail.truncate(input, 10, END, EST);
            String startResult = MaxTokensInputGuardrail.truncate(input, 10, START, EST);
            assertThat(endResult).isNotEqualTo(startResult);
        }
    }

    // -------------------------------------------------------------------------
    // Strategy: START
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TruncationStrategy.START")
    class StartStrategyTests {

        @Test
        @DisplayName("START keeps end, drops head")
        void start_keepsEnd() {
            String input = repeat('A', 200) + repeat('B', 200);
            String truncated = MaxTokensInputGuardrail.truncate(input, 25, START, EST);
            assertThat(truncated).endsWith("BBBB");
            assertThat(truncated).doesNotContain("A");
        }

        @Test
        @DisplayName("START: truncated content ends with the end of original")
        void start_suffixPreserved() {
            String input = repeat('x', 200) + "END_MARKER";
            String truncated = MaxTokensInputGuardrail.truncate(input, 5, START, EST);
            assertThat(truncated).endsWith(input.substring(
                    Math.max(0, input.length() - truncated.length())));
        }
    }

    // -------------------------------------------------------------------------
    // Strategy: MIDDLE
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TruncationStrategy.MIDDLE")
    class MiddleStrategyTests {

        @Test
        @DisplayName("MIDDLE keeps beginning and end, drops centre")
        void middle_keepsBothEnds() {
            // 10 A's + 180 X's + 10 B's → should drop the X's
            String input = repeat('A', 10) + repeat('X', 180) + repeat('B', 10);
            String truncated = MaxTokensInputGuardrail.truncate(input, 10, MIDDLE, EST);
            assertThat(truncated).startsWith("A");
            assertThat(truncated).endsWith("B");
        }

        @Test
        @DisplayName("MIDDLE: truncated length is within budget")
        void middle_lengthWithinBudget() {
            String input = repeat('z', 400); // 100 tokens
            String truncated = MaxTokensInputGuardrail.truncate(input, 20, MIDDLE, EST);
            int tokens = EST.estimateTokens(truncated);
            assertThat(tokens).isLessThanOrEqualTo(20);
        }

        @Test
        @DisplayName("all three strategies produce different results")
        void threeStrategies_produceDifferentOutputs() {
            String input = "START" + repeat('M', 200) + "END";
            String endR    = MaxTokensInputGuardrail.truncate(input, 10, END,    EST);
            String startR  = MaxTokensInputGuardrail.truncate(input, 10, START,  EST);
            String middleR = MaxTokensInputGuardrail.truncate(input, 10, MIDDLE, EST);

            // All three should differ (asymmetric input ensures this)
            assertThat(endR).isNotEqualTo(startR);
            assertThat(endR).isNotEqualTo(middleR);
            assertThat(startR).isNotEqualTo(middleR);
        }
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("maxTokens = 0 throws IllegalArgumentException")
        void zeroMaxTokens_throws() {
            assertThatThrownBy(() -> new MaxTokensInputGuardrail(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative maxTokens throws IllegalArgumentException")
        void negativeMaxTokens_throws() {
            assertThatThrownBy(() -> new MaxTokensInputGuardrail(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null strategy throws NullPointerException")
        void nullStrategy_throws() {
            assertThatThrownBy(() -> new MaxTokensInputGuardrail(10, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null estimator throws NullPointerException")
        void nullEstimator_throws() {
            assertThatThrownBy(() -> new MaxTokensInputGuardrail(10, END, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("default constructor uses END strategy")
        void defaultConstructor_usesEndStrategy() {
            var g = new MaxTokensInputGuardrail(10);
            assertThat(g.strategy()).isEqualTo(END);
        }
    }

    // -------------------------------------------------------------------------
    // Async contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("apply() returns CompletableFuture with Passed when within limit")
    void asyncContract_passed() throws Exception {
        var g = new MaxTokensInputGuardrail(100);
        var result = g.apply("short input", CTX).get();
        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
    }

    @Test
    @DisplayName("apply() returns CompletableFuture with Modified when over limit")
    void asyncContract_modified() throws Exception {
        var g = new MaxTokensInputGuardrail(2);
        var result = g.apply(repeat('x', 200), CTX).get();
        assertThat(result).isInstanceOf(GuardrailResult.Modified.class);
    }
}
