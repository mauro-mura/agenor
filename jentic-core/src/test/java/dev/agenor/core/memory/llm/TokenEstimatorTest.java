package dev.agenor.core.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TokenEstimator interface and default methods.
 */
class TokenEstimatorTest {

    private TestTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new TestTokenEstimator();
    }

    // ========== BASIC ESTIMATION TESTS ==========

    @Test
    void shouldEstimateTokensForText() {
        // When
        int tokens = estimator.estimateTokens("Hello, world!");

        // Then
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void shouldReturnZeroForNullText() {
        // When
        int tokens = estimator.estimateTokens((String) null);

        // Then
        assertThat(tokens).isEqualTo(0);
    }

    @Test
    void shouldReturnZeroForEmptyText() {
        // When
        int tokens = estimator.estimateTokens("");

        // Then
        assertThat(tokens).isEqualTo(0);
    }

    @Test
    void shouldEstimateTokensForMessage() {
        // Given
        LLMMessage message = LLMMessage.user("What's the weather?");

        // When
        int tokens = estimator.estimateTokens(message);

        // Then
        assertThat(tokens).isGreaterThan(0);
        // Should include overhead
        assertThat(tokens).isGreaterThan(estimator.estimateTokens(message.content()));
    }

    @Test
    void shouldReturnZeroForNullMessage() {
        // When
        int tokens = estimator.estimateTokens((LLMMessage) null);

        // Then
        assertThat(tokens).isEqualTo(0);
    }

    @Test
    void shouldEstimateTokensForMessageList() {
        // Given
        List<LLMMessage> messages = List.of(
            LLMMessage.system("You are helpful"),
            LLMMessage.user("Hi"),
            LLMMessage.assistant("Hello!")
        );

        // When
        int tokens = estimator.estimateTokens(messages);

        // Then
        assertThat(tokens).isGreaterThan(0);
        // Should be sum of individual messages
        int sum = messages.stream()
            .mapToInt(estimator::estimateTokens)
            .sum();
        assertThat(tokens).isEqualTo(sum);
    }

    @Test
    void shouldReturnZeroForNullMessageList() {
        // When
        int tokens = estimator.estimateTokens((List<LLMMessage>) null);

        // Then
        assertThat(tokens).isEqualTo(0);
    }

    @Test
    void shouldReturnZeroForEmptyMessageList() {
        // When
        int tokens = estimator.estimateTokens(List.<LLMMessage>of());

        // Then
        assertThat(tokens).isEqualTo(0);
    }

    // ========== CONTEXT WINDOW TESTS ==========

    @Test
    void shouldGetContextWindowSize() {
        // When
        int size = estimator.getContextWindowSize("gpt-4");

        // Then
        assertThat(size).isGreaterThan(0);
    }

    @Test
    void shouldReturnNegativeForUnknownModel() {
        // When
        int size = estimator.getContextWindowSize("unknown-model");

        // Then
        assertThat(size).isEqualTo(-1);
    }

    @Test
    void shouldValidateModelParameter() {
        // When/Then
        assertThatThrownBy(() ->
            estimator.getContextWindowSize(null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    // ========== DEFAULT METHOD TESTS ==========

    @Test
    void shouldCheckIfFitsInContextWindow() {
        // Given
        String model = "gpt-4";
        int windowSize = estimator.getContextWindowSize(model);

        // When/Then
        assertThat(estimator.fitsInContextWindow(model, windowSize - 100)).isTrue();
        assertThat(estimator.fitsInContextWindow(model, windowSize)).isTrue();
        assertThat(estimator.fitsInContextWindow(model, windowSize + 100)).isFalse();
    }

    @Test
    void shouldHandleUnknownModelInFitsCheck() {
        // Given
        String unknownModel = "unknown";

        // When
        boolean fits = estimator.fitsInContextWindow(unknownModel, 1000);

        // Then - should return true when window size unknown
        assertThat(fits).isTrue();
    }

    @Test
    void shouldCalculateRemainingTokens() {
        // Given
        String model = "gpt-4";
        int windowSize = estimator.getContextWindowSize(model);
        int used = 1000;

        // When
        int remaining = estimator.getRemainingTokens(model, used);

        // Then
        assertThat(remaining).isEqualTo(windowSize - used);
    }

    @Test
    void shouldReturnZeroRemainingWhenExceeded() {
        // Given
        String model = "gpt-4";
        int windowSize = estimator.getContextWindowSize(model);
        int used = windowSize + 1000;

        // When
        int remaining = estimator.getRemainingTokens(model, used);

        // Then
        assertThat(remaining).isEqualTo(0);
    }

    @Test
    void shouldReturnNegativeRemainingForUnknownModel() {
        // Given
        String unknownModel = "unknown";

        // When
        int remaining = estimator.getRemainingTokens(unknownModel, 1000);

        // Then
        assertThat(remaining).isEqualTo(-1);
    }

    @Test
    void shouldValidateUsedTokensInGetRemaining() {
        // When/Then
        assertThatThrownBy(() ->
            estimator.getRemainingTokens("gpt-4", -1)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("usedTokens cannot be negative");
    }

    // ========== METADATA TESTS ==========

    @Test
    void shouldHaveName() {
        // When
        String name = estimator.getName();

        // Then
        assertThat(name).isNotNull();
        assertThat(name).isNotEmpty();
    }

    @Test
    void shouldHaveAccuracyLevel() {
        // When
        String accuracy = estimator.getAccuracyLevel();

        // Then
        assertThat(accuracy).isNotNull();
        assertThat(accuracy).isIn("exact", "high", "medium", "low");
    }

    // ========== PRACTICAL USAGE TESTS ==========

    @Test
    void shouldEstimateConversationAccurately() {
        // Given
        List<LLMMessage> conversation = List.of(
            LLMMessage.system("You are a helpful assistant"),
            LLMMessage.user("What's 2+2?"),
            LLMMessage.assistant("2+2 equals 4"),
            LLMMessage.user("Thanks!"),
            LLMMessage.assistant("You're welcome!")
        );

        // When
        int totalTokens = estimator.estimateTokens(conversation);

        // Then - should be reasonable
        assertThat(totalTokens).isGreaterThan(10);  // At least some tokens
        assertThat(totalTokens).isLessThan(1000);   // But not excessive
    }

    @Test
    void shouldHandleLongMessages() {
        // Given
        String longContent = "word ".repeat(500);  // 2500 chars
        LLMMessage longMessage = LLMMessage.user(longContent);

        // When
        int tokens = estimator.estimateTokens(longMessage);

        // Then - should scale with content
        assertThat(tokens).isGreaterThan(100);
    }

    @Test
    void shouldHandleSpecialCharacters() {
        // Given
        LLMMessage message = LLMMessage.user("Hello! 你好! Привет! 🌍");

        // When
        int tokens = estimator.estimateTokens(message);

        // Then - should handle gracefully
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void shouldProvideConsistentEstimates() {
        // Given
        String text = "This is a test message";
        LLMMessage message = LLMMessage.user(text);

        // When - estimate multiple times
        int tokens1 = estimator.estimateTokens(message);
        int tokens2 = estimator.estimateTokens(message);
        int tokens3 = estimator.estimateTokens(message);

        // Then - should be consistent
        assertThat(tokens1).isEqualTo(tokens2);
        assertThat(tokens2).isEqualTo(tokens3);
    }

    // ========== HELPER CLASS ==========

    /**
     * Test implementation of TokenEstimator.
     */
    private static class TestTokenEstimator implements TokenEstimator {

        @Override
        public int estimateTokens(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            // Simple estimation: ~1 token per 4 characters
            return Math.max(1, text.length() / 4);
        }

        @Override
        public int estimateTokens(LLMMessage message) {
            if (message == null) {
                return 0;
            }
            int contentTokens = estimateTokens(message.content());
            int overhead = 3;  // Fixed overhead for role, formatting

            // Extra overhead for function calls
            if (message.hasFunctionCalls()) {
                overhead += 10;
            }

            return contentTokens + overhead;
        }

        @Override
        public int estimateTokens(List<LLMMessage> messages) {
            if (messages == null || messages.isEmpty()) {
                return 0;
            }
            return messages.stream()
                .mapToInt(this::estimateTokens)
                .sum();
        }

        @Override
        public int getContextWindowSize(String model) {
            if (model == null) {
                throw new IllegalArgumentException("Model cannot be null");
            }

            return switch (model) {
                case "gpt-3.5-turbo" -> 16385;
                case "gpt-4" -> 8192;
                case "gpt-4-turbo" -> 128000;
                case "claude-3-opus" -> 200000;
                case "claude-3-sonnet" -> 200000;
                default -> -1;  // Unknown
            };
        }

        @Override
        public String getName() {
            return "TestTokenEstimator";
        }

        @Override
        public String getAccuracyLevel() {
            return "low";
        }
    }
}
