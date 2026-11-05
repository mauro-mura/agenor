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

    // ========== SYSTEM MESSAGES (ADR-010) ==========

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
    void selectMessages_shouldKeepTheSystemPromptWhenTheHistoryOutgrowsTheBudget() {
        LLMMessage prompt = LLMMessage.system("You are a support agent.");
        List<LLMMessage> messages = new ArrayList<>();
        messages.add(prompt);
        messages.addAll(longMessages(30));

        List<LLMMessage> result = strategy.selectMessages(messages, 2000, estimator);

        assertThat(result.get(0)).isEqualTo(prompt);
        assertThat(tokens(result)).isLessThanOrEqualTo(2000);
        // The rest is the newest messages, without a gap
        List<LLMMessage> recent = result.subList(1, result.size());
        assertThat(recent).isNotEmpty();
        assertThat(recent).containsExactlyElementsOf(
            messages.subList(messages.size() - recent.size(), messages.size()));
    }

    @Test
    void selectMessages_shouldTakeTheRoomOfASystemMessageFromTheRecentOnes() {
        // Given - a prompt as large as the messages, in a budget for exactly three of them
        LLMMessage prompt = LLMMessage.system("0 " + "word ".repeat(100));
        List<LLMMessage> history = longMessages(30);
        List<LLMMessage> messages = new ArrayList<>();
        messages.add(prompt);
        messages.addAll(history);
        int budget = tokens(history.subList(27, 30));

        List<LLMMessage> result = strategy.selectMessages(messages, budget, estimator);

        assertThat(result).containsExactly(prompt, history.get(28), history.get(29));
    }

    @Test
    void selectMessages_shouldKeepASystemMessageAddedMidConversationInItsPlace() {
        LLMMessage instruction = LLMMessage.system("From now on, answer in Italian.");
        List<LLMMessage> history = longMessages(30);
        List<LLMMessage> messages = new ArrayList<>(history.subList(0, 10));
        messages.add(instruction);
        messages.addAll(history.subList(10, 30));

        List<LLMMessage> result = strategy.selectMessages(messages, 500, estimator);

        assertThat(result.get(0)).isEqualTo(instruction);
        assertThat(result.get(result.size() - 1)).isEqualTo(history.get(29));
        assertThat(tokens(result)).isLessThanOrEqualTo(500);
    }

    @Test
    void selectMessages_shouldPreferTheOldestSystemMessageWhenNotAllFit() {
        LLMMessage first = LLMMessage.system("First " + "word ".repeat(100));
        LLMMessage second = LLMMessage.system("Second " + "word ".repeat(100));
        int budget = estimator.estimateTokens(first);

        assertThat(strategy.selectMessages(List.of(first, second), budget, estimator))
            .containsExactly(first);
    }

    @Test
    void selectMessages_shouldNotExceedTheBudgetForASystemMessageLargerThanIt() {
        LLMMessage prompt = LLMMessage.system("word ".repeat(1000));
        LLMMessage last = LLMMessage.user("Last");

        assertThat(strategy.selectMessages(List.of(prompt, last), 100, estimator))
            .containsExactly(last);
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
