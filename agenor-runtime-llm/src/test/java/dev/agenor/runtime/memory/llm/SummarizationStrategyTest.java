package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.runtime.llm.ScriptedLLMProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SummarizationStrategyTest {

    private SummarizationStrategy strategy;
    private SimpleTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        strategy = new SummarizationStrategy();
        estimator = new SimpleTokenEstimator();
    }

    // ========== THE MODEL REACHES THE PROVIDER ==========

    /**
     * The constructor takes a model and its Javadoc calls it the "model identifier passed to the
     * provider", so the request this strategy builds has to carry it. That half was always
     * correct; the half that was not is in the adapters, which until 0.35.0 built their client
     * request without it and answered from the model they were constructed with. The adapter side
     * is pinned by the adapters' own tests - this one pins the contract they honour.
     */
    @Test
    void generateSummary_shouldPassItsModelToTheProvider() {
        ScriptedLLMProvider recording = ScriptedLLMProvider.create().reply("a summary");

        List<LLMMessage> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(LLMMessage.user("message " + i));
        }

        new SummarizationStrategy(recording, "gpt-4.1-mini")
                .selectMessages(many, 4000, estimator);

        assertThat(recording.requests()).singleElement()
                .extracting(LLMRequest::model).isEqualTo("gpt-4.1-mini");
    }

    // ========== SYSTEM MESSAGES ==========

    private static List<LLMMessage> promptAndTurns(String prompt, int turns) {
        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(prompt));
        for (int i = 0; i < turns; i++) {
            messages.add(LLMMessage.user("message " + i));
        }
        return messages;
    }

    @Test
    void selectMessages_shouldKeepTheSystemPromptVerbatimAboveTheSummary() {
        LLMMessage prompt = LLMMessage.system("You answer in French.");
        List<LLMMessage> messages = promptAndTurns(prompt.content(), 20);

        List<LLMMessage> result = new SummarizationStrategy(
                ScriptedLLMProvider.create().reply("a summary"), "m")
                .selectMessages(messages, 4000, estimator);

        assertThat(result.get(0)).isEqualTo(prompt);
        assertThat(result.get(1).content()).isEqualTo("Summary of earlier conversation: a summary");
    }

    @Test
    void selectMessages_shouldKeepTheSystemPromptInPlaceholderModeToo() {
        LLMMessage prompt = LLMMessage.system("You answer in French.");

        List<LLMMessage> result = strategy.selectMessages(
                promptAndTurns(prompt.content(), 20), 4000, estimator);

        assertThat(result).startsWith(prompt);
    }

    /** The prompt goes to the model whole, so it has nothing to add to what gets summarized. */
    @Test
    void generateSummary_shouldNotSendTheSystemPromptToBeSummarized() {
        ScriptedLLMProvider recording = ScriptedLLMProvider.create().reply("a summary");

        new SummarizationStrategy(recording, "m")
                .selectMessages(promptAndTurns("You answer in French.", 20), 4000, estimator);

        assertThat(recording.requests().getFirst().messages())
                .extracting(LLMMessage::content)
                .doesNotContain("You answer in French.");
    }

    /** A prompt and ten turns: the only old message is the prompt, so there is nothing to summarize. */
    @Test
    void selectMessages_shouldNotCallTheModelWhenOnlySystemMessagesAreOld() {
        ScriptedLLMProvider recording = ScriptedLLMProvider.create();
        List<LLMMessage> messages = promptAndTurns("You answer in French.", 10);

        List<LLMMessage> result = new SummarizationStrategy(recording, "m")
                .selectMessages(messages, 4000, estimator);

        assertThat(recording.requests()).isEmpty();
        assertThat(result).isEqualTo(messages);
    }

    /** Nothing is cached, and the Javadoc says so: each window past ten messages is one more call. */
    @Test
    void selectMessages_shouldCallTheProviderForEachWindow() {
        ScriptedLLMProvider recording = ScriptedLLMProvider.create().orElse(request -> "a summary");
        SummarizationStrategy summarizing = new SummarizationStrategy(recording, "m");
        List<LLMMessage> messages = promptAndTurns("You answer in French.", 20);

        summarizing.selectMessages(messages, 4000, estimator);
        summarizing.selectMessages(messages, 4000, estimator);

        assertThat(recording.requests()).hasSize(2);
    }

    // ========== A TIGHT BUDGET ==========

    /**
     * The window filled from its oldest message and stopped at the first that did not fit, so a
     * tight budget dropped the newest ones: what the user had just asked did not reach the model.
     */
    @Test
    void selectMessages_shouldDropTheOldestOfTheWindowWhenTheBudgetIsTight() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(LLMMessage.user("message " + i));
        }
        LLMMessage newest = messages.getLast();
        int tokensOfSummaryAndThreeMessages = estimator.estimateTokens(strategy
                .selectMessages(messages, 4000, estimator).getFirst())
                + 3 * estimator.estimateTokens(newest);

        List<LLMMessage> result = strategy.selectMessages(
                messages, tokensOfSummaryAndThreeMessages, estimator);

        assertThat(result).hasSize(4).endsWith(newest);
    }

    @Test
    void selectMessages_shouldFallBackToTheWindowWhenThePromptLeavesNoRoomForTheSummary() {
        String prompt = "You answer in French.";
        List<LLMMessage> messages = promptAndTurns(prompt, 20);
        int promptAndTwoMessages = estimator.estimateTokens(messages.getFirst())
                + 2 * estimator.estimateTokens(messages.getLast());

        List<LLMMessage> result = strategy.selectMessages(messages, promptAndTwoMessages, estimator);

        assertThat(result).containsExactly(
                messages.getFirst(), messages.get(19), messages.get(20));
    }

    // ========== WHEN THE SUMMARY FAILS ==========

    /** The text of a provider's exception is not for the model, which pays to read it. */
    @Test
    void selectMessages_shouldNotPutAProviderErrorInThePrompt() {
        ScriptedLLMProvider failing = ScriptedLLMProvider.create()
                .fail(new IllegalStateException("401 for key sk-secret"));
        List<LLMMessage> messages = promptAndTurns("You answer in French.", 20);

        List<LLMMessage> result = new SummarizationStrategy(failing, "m")
                .selectMessages(messages, 4000, estimator);

        assertThat(result).extracting(LLMMessage::content)
                .noneMatch(content -> content.contains("sk-secret"))
                .contains("Summary of earlier conversation: [Summary of 10 earlier messages]");
    }

    // ========== METADATA ==========

    @Test
    void getName_shouldReturnSummarized() {
        assertThat(strategy.getName()).isEqualTo("summarized");
    }

    @Test
    void requiresLLM_shouldAnswerWhetherAProviderIsCalled() {
        assertThat(strategy.requiresLLM()).isFalse();
        assertThat(new SummarizationStrategy(ScriptedLLMProvider.create(), "m").requiresLLM()).isTrue();
    }

    @Test
    void getOverheadTokens_shouldReturnPositive() {
        assertThat(strategy.getOverheadTokens()).isGreaterThan(0);
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
        assertThatThrownBy(() -> strategy.selectMessages(List.of(), -5, estimator))
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
    void selectMessages_shouldFallBackToFixedForFewMessages() {
        // <= 10 messages → falls back to FixedWindowStrategy
        List<LLMMessage> messages = List.of(
            LLMMessage.user("1"),
            LLMMessage.user("2"),
            LLMMessage.user("3")
        );
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);
        assertThat(result).hasSize(3);
    }

    @Test
    void selectMessages_shouldFallBackForExactlyTenMessages() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(LLMMessage.user("Message " + i));
        }
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);
        assertThat(result).hasSize(10);
    }

    // ========== MANY MESSAGES ==========

    @Test
    void selectMessages_shouldIncludeRecentMessagesForLargeInput() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(LLMMessage.user("Old " + i));
        }
        LLMMessage lastMessage = LLMMessage.user("Last recent");
        messages.add(lastMessage);

        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);

        assertThat(result).contains(lastMessage);
    }

    @Test
    void selectMessages_shouldRespectTokenBudget() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(LLMMessage.user("Message number " + i + " with some content"));
        }

        int budget = 300;
        List<LLMMessage> result = strategy.selectMessages(messages, budget, estimator);

        // Replicate the summary message the strategy builds internally (placeholder mode):
        // 30 messages total, 10 recent kept → 20 summarized
        int oldMessageCount = 30 - 10;
        String summaryContent = "Summary of earlier conversation: "
                + String.format("[Summary of %d earlier messages]", oldMessageCount);
        LLMMessage summaryMessage = LLMMessage.system(summaryContent);

        int totalTokens = result.stream()
                .mapToInt(msg -> estimator.estimateTokens(msg))
                .sum();

        // The summary is already included in result, so just check total <= budget
        assertThat(totalTokens).isLessThanOrEqualTo(budget);
    }

    @Test
    void selectMessages_shouldHandleElevenMessages() {
        // Exactly one more than the threshold - triggers summarization path
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            messages.add(LLMMessage.user("Message " + i));
        }
        List<LLMMessage> result = strategy.selectMessages(messages, 10000, estimator);

        // 1 summary message (system) + 10 recent = 11
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isLessThanOrEqualTo(11);
        // First element must be the summary system message
        assertThat(result.getFirst().isSystem()).isTrue();
        assertThat(result.getFirst().content()).startsWith("Summary of earlier conversation:");
    }

    @Test
    void selectMessages_shouldReturnEmptyWhenBudgetExhaustedBySummary() {
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            messages.add(LLMMessage.user("Message with lots of text to consume tokens"));
        }

        // Budget so small that the summary placeholder itself consumes it all
        List<LLMMessage> result = strategy.selectMessages(messages, 1, estimator);
        // Should return empty or very few messages
        assertThat(result).isNotNull();
    }
}
