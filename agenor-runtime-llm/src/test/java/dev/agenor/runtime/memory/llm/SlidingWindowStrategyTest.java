package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SlidingWindowStrategyTest {

    private SlidingWindowStrategy strategy;
    private SimpleTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        strategy = new SlidingWindowStrategy();
        estimator = new SimpleTokenEstimator();
    }

    // ========== METADATA ==========

    @Test
    void getName_shouldReturnSliding() {
        assertThat(strategy.getName()).isEqualTo("sliding");
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

    // ========== FEW MESSAGES (fall back to fixed) ==========

    @Test
    void selectMessages_shouldUseFallbackForFewMessages() {
        // <= 5 messages → falls back to FixedWindowStrategy
        List<LLMMessage> messages = List.of(
            LLMMessage.user("1"),
            LLMMessage.user("2"),
            LLMMessage.user("3")
        );
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);
        assertThat(result).hasSize(3);
    }

    // ========== MANY MESSAGES ==========

    @Test
    void selectMessages_shouldAlwaysIncludeMostRecentMessages() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(LLMMessage.user("Old message " + i));
        }
        LLMMessage recent1 = LLMMessage.user("Recent 1");
        LLMMessage recent2 = LLMMessage.user("Recent 2");
        LLMMessage recent3 = LLMMessage.user("Recent 3");
        LLMMessage recent4 = LLMMessage.user("Recent 4");
        LLMMessage recent5 = LLMMessage.user("Recent 5");
        messages.add(recent1);
        messages.add(recent2);
        messages.add(recent3);
        messages.add(recent4);
        messages.add(recent5);

        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);

        // The last 5 should always be included
        assertThat(result).contains(recent1, recent2, recent3, recent4, recent5);
    }

    @Test
    void selectMessages_shouldRespectTokenBudget() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(LLMMessage.user("Message " + i));
        }

        int budget = 50;
        List<LLMMessage> result = strategy.selectMessages(messages, budget, estimator);

        int totalTokens = result.stream()
            .mapToInt(msg -> estimator.estimateTokens(msg))
            .sum();
        assertThat(totalTokens).isLessThanOrEqualTo(budget);
    }

    private List<LLMMessage> longMessages(int count) {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(LLMMessage.user(i + " " + "word ".repeat(100)));
        }
        return messages;
    }

    private int tokens(List<LLMMessage> messages) {
        return messages.stream().mapToInt(estimator::estimateTokens).sum();
    }

    @Test
    void selectMessages_shouldStayInBudgetWhenTheRecentMessagesAloneExceedIt() {
        // Given - each about 128 tokens, so the five most recent need about 640
        List<LLMMessage> messages = longMessages(30);

        // When
        List<LLMMessage> result = strategy.selectMessages(messages, 500, estimator);

        // Then - the newest that fit, without a gap and in order
        assertThat(tokens(result)).isLessThanOrEqualTo(500);
        assertThat(result).containsExactlyElementsOf(messages.subList(27, 30));
    }

    @Test
    void selectMessages_shouldStillAddAnOlderMessageThatFitsBesideTruncatedRecentOnes() {
        // Given
        List<LLMMessage> messages = new ArrayList<>();
        LLMMessage prompt = LLMMessage.system("You are a support agent.");
        messages.add(prompt);
        messages.addAll(longMessages(30));

        // When
        List<LLMMessage> result = strategy.selectMessages(messages, 500, estimator);

        // Then
        assertThat(tokens(result)).isLessThanOrEqualTo(500);
        assertThat(result.get(0)).isEqualTo(prompt);
        assertThat(result.get(result.size() - 1)).isEqualTo(messages.get(30));
    }

    @Test
    void selectMessages_shouldReturnNothingOverBudgetWhenTheNewestMessageAloneExceedsIt() {
        // Given - the same budget a FixedWindowStrategy would return nothing for
        List<LLMMessage> messages = longMessages(30);

        // When/Then
        assertThat(strategy.selectMessages(messages, 50, estimator)).isEmpty();
    }

    @Test
    void selectMessages_shouldMaintainChronologicalOrder() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            messages.add(LLMMessage.user("Message " + i));
        }

        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);

        for (int i = 0; i < result.size() - 1; i++) {
            int idxA = messages.indexOf(result.get(i));
            int idxB = messages.indexOf(result.get(i + 1));
            assertThat(idxA).isLessThan(idxB);
        }
    }

    @Test
    void selectMessages_shouldPrioritizeSystemMessagesOverRegular() {
        List<LLMMessage> messages = new ArrayList<>();
        // Add system message first
        LLMMessage systemMsg = LLMMessage.system("System instruction");
        messages.add(systemMsg);
        // Add many user messages to fill the list beyond recent window
        for (int i = 0; i < 20; i++) {
            messages.add(LLMMessage.user("User message " + i));
        }

        // Budget that allows system + recent messages
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);

        // System message should be included due to high importance score
        assertThat(result).contains(systemMsg);
    }

    @Test
    void selectMessages_shouldHandleExactlyFiveMessages() {
        List<LLMMessage> messages = List.of(
            LLMMessage.user("1"),
            LLMMessage.user("2"),
            LLMMessage.user("3"),
            LLMMessage.user("4"),
            LLMMessage.user("5")
        );
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);
        assertThat(result).hasSize(5);
    }

    @Test
    void selectMessages_shouldHandleSixMessages() {
        List<LLMMessage> messages = List.of(
            LLMMessage.user("1"),
            LLMMessage.user("2"),
            LLMMessage.user("3"),
            LLMMessage.user("4"),
            LLMMessage.user("5"),
            LLMMessage.user("6")
        );
        // With enough budget, all 6 should be included
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);
        assertThat(result).hasSize(6);
    }
}
