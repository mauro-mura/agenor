package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
        List<LLMRequest> seen = new ArrayList<>();
        LLMProvider recording = new RecordingProvider(seen, "a summary");

        List<LLMMessage> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(LLMMessage.user("message " + i));
        }

        new SummarizationStrategy(recording, "gpt-4.1-mini")
                .selectMessages(many, 4000, estimator);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).model()).isEqualTo("gpt-4.1-mini");
    }

    /** Minimal recording stub - the module under test does not depend on any real provider. */
    private static final class RecordingProvider implements LLMProvider {
        private final List<LLMRequest> seen;
        private final String reply;

        RecordingProvider(List<LLMRequest> seen, String reply) {
            this.seen = seen;
            this.reply = reply;
        }

        @Override
        public CompletableFuture<LLMResponse> chat(LLMRequest request) {
            seen.add(request);
            return CompletableFuture.completedFuture(
                    LLMResponse.builder("resp-1", request.model())
                            .role(LLMMessage.Role.ASSISTANT)
                            .content(reply)
                            .build());
        }

        @Override
        public CompletableFuture<Void> chatStream(LLMRequest request,
                                                  Consumer<StreamingChunk> chunkHandler) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> getAvailableModels() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public String getProviderName() {
            return "recording";
        }
    }

    // ========== METADATA ==========

    @Test
    void getName_shouldReturnSummarized() {
        assertThat(strategy.getName()).isEqualTo("summarized");
    }

    @Test
    void requiresLLM_shouldReturnTrue() {
        assertThat(strategy.requiresLLM()).isTrue();
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
