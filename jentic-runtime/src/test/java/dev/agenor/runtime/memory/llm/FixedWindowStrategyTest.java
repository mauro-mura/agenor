package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FixedWindowStrategyTest {

    private FixedWindowStrategy strategy;
    private SimpleTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        strategy = new FixedWindowStrategy();
        estimator = new SimpleTokenEstimator();
    }

    // ========== METADATA ==========

    @Test
    void getName_shouldReturnFixed() {
        assertThat(strategy.getName()).isEqualTo("fixed");
    }

    @Test
    void requiresLLM_shouldReturnFalse() {
        assertThat(strategy.requiresLLM()).isFalse();
    }

    @Test
    void getOverheadTokens_shouldReturnZero() {
        assertThat(strategy.getOverheadTokens()).isEqualTo(0);
    }

    // ========== VALIDATION ==========

    @Test
    void selectMessages_shouldThrowForNullMessages() {
        assertThatThrownBy(() -> strategy.selectMessages(null, 100, estimator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allMessages cannot be null");
    }

    @Test
    void selectMessages_shouldThrowForZeroMaxTokens() {
        assertThatThrownBy(() -> strategy.selectMessages(List.of(), 0, estimator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxTokens must be positive");
    }

    @Test
    void selectMessages_shouldThrowForNegativeMaxTokens() {
        assertThatThrownBy(() -> strategy.selectMessages(List.of(), -1, estimator))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectMessages_shouldThrowForNullEstimator() {
        assertThatThrownBy(() -> strategy.selectMessages(List.of(), 100, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("estimator cannot be null");
    }

    // ========== EMPTY LIST ==========

    @Test
    void selectMessages_shouldReturnEmptyForEmptyInput() {
        List<LLMMessage> result = strategy.selectMessages(List.of(), 100, estimator);
        assertThat(result).isEmpty();
    }

    // ========== SELECTION LOGIC ==========

    @Test
    void selectMessages_shouldReturnAllWhenBudgetIsSufficient() {
        List<LLMMessage> messages = List.of(
            LLMMessage.user("Hi"),
            LLMMessage.assistant("Hello"),
            LLMMessage.user("How are you?")
        );
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);
        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyElementsOf(messages);
    }

    @Test
    void selectMessages_shouldSelectMostRecentWhenBudgetIsSmall() {
        List<LLMMessage> messages = List.of(
            LLMMessage.user("First old message"),
            LLMMessage.user("Second old message"),
            LLMMessage.user("Third old message"),
            LLMMessage.user("Recent")
        );

        // "Recent" has fewest tokens
        int smallBudget = estimator.estimateTokens(LLMMessage.user("Recent"));
        List<LLMMessage> result = strategy.selectMessages(messages, smallBudget, estimator);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("Recent");
    }

    @Test
    void selectMessages_shouldMaintainChronologicalOrder() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(LLMMessage.user("Message " + i));
        }

        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);

        // Order must be maintained
        for (int i = 0; i < result.size() - 1; i++) {
            int idxA = messages.indexOf(result.get(i));
            int idxB = messages.indexOf(result.get(i + 1));
            assertThat(idxA).isLessThan(idxB);
        }
    }

    @Test
    void selectMessages_shouldStopWhenBudgetExceeded() {
        // Create messages with known token costs
        List<LLMMessage> messages = List.of(
            LLMMessage.user("A"),
            LLMMessage.user("B"),
            LLMMessage.user("C")
        );

        int tokenPerMessage = estimator.estimateTokens(LLMMessage.user("A"));
        // Allow only 2 messages worth of tokens
        int budget = tokenPerMessage * 2;

        List<LLMMessage> result = strategy.selectMessages(messages, budget, estimator);

        assertThat(result.size()).isLessThanOrEqualTo(2);
        // All selected must be from the end (most recent)
        assertThat(result).allMatch(msg -> msg.content().equals("B") || msg.content().equals("C"));
    }

    @Test
    void selectMessages_shouldReturnSingleMessageWhenBudgetIsVeryTight() {
        List<LLMMessage> messages = List.of(
            LLMMessage.user("First"),
            LLMMessage.user("Last")
        );

        int minBudget = estimator.estimateTokens(LLMMessage.user("Last"));
        List<LLMMessage> result = strategy.selectMessages(messages, minBudget, estimator);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("Last");
    }
}
