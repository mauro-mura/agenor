package dev.agenor.core.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract tests for ContextWindowStrategy interface.
 *
 * <p>These tests verify the interface contract without depending on
 * concrete implementations from agenor-runtime.
 *
 * <p><b>Note:</b> These are minimal contract tests. For comprehensive
 * implementation tests, see {@code agenor-runtime/test/.../ContextWindowStrategiesTest}.
 */
class ContextWindowStrategyTest {

    @Test
    void shouldDefineSelectMessagesMethod() {
        // Given - minimal test implementation
        ContextWindowStrategy strategy = new TestStrategy();
        TokenEstimator estimator = new SimpleTestEstimator();

        List<LLMMessage> messages = List.of(
            LLMMessage.user("Test message")
        );

        // When
        List<LLMMessage> result = strategy.selectMessages(messages, 100, estimator);

        // Then
        assertThat(result).isNotNull();
    }

    @Test
    void shouldHaveGetNameMethod() {
        // Given
        ContextWindowStrategy strategy = new TestStrategy();

        // When
        String name = strategy.getName();

        // Then
        assertThat(name).isNotNull();
        assertThat(name).isNotEmpty();
    }

    @Test
    void shouldHaveRequiresLLMDefaultMethod() {
        // Given
        ContextWindowStrategy strategy = new TestStrategy();

        // When
        boolean requiresLLM = strategy.requiresLLM();

        // Then - default is false
        assertThat(requiresLLM).isFalse();
    }

    @Test
    void shouldHaveGetOverheadTokensDefaultMethod() {
        // Given
        ContextWindowStrategy strategy = new TestStrategy();

        // When
        int overhead = strategy.getOverheadTokens();

        // Then - default is 0
        assertThat(overhead).isEqualTo(0);
    }

    @Test
    void shouldAllowCustomRequiresLLM() {
        // Given - strategy that requires LLM
        ContextWindowStrategy strategy = new ContextWindowStrategy() {
            @Override
            public List<LLMMessage> selectMessages(List<LLMMessage> allMessages, int maxTokens, TokenEstimator estimator) {
                return List.of();
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public boolean requiresLLM() {
                return true;  // Override default
            }
        };

        // Then
        assertThat(strategy.requiresLLM()).isTrue();
    }

    @Test
    void shouldAllowCustomOverheadTokens() {
        // Given - strategy with overhead
        ContextWindowStrategy strategy = new ContextWindowStrategy() {
            @Override
            public List<LLMMessage> selectMessages(List<LLMMessage> allMessages, int maxTokens, TokenEstimator estimator) {
                return List.of();
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public int getOverheadTokens() {
                return 100;  // Override default
            }
        };

        // Then
        assertThat(strategy.getOverheadTokens()).isEqualTo(100);
    }

    @Test
    void shouldAcceptNullSafeImplementations() {
        // Given
        ContextWindowStrategy strategy = new TestStrategy();
        TokenEstimator estimator = new SimpleTestEstimator();

        // When/Then - should handle edge cases
        assertThatCode(() ->
            strategy.selectMessages(List.of(), 100, estimator)
        ).doesNotThrowAnyException();
    }

    // ========== TEST HELPERS ==========

    /**
     * Minimal test implementation for contract testing.
     */
    private static class TestStrategy implements ContextWindowStrategy {

        @Override
        public List<LLMMessage> selectMessages(
            List<LLMMessage> allMessages,
            int maxTokens,
            TokenEstimator estimator
        ) {
            // Minimal implementation for testing
            if (allMessages == null || allMessages.isEmpty()) {
                return List.of();
            }

            List<LLMMessage> selected = new ArrayList<>();
            int currentTokens = 0;

            for (LLMMessage msg : allMessages) {
                int msgTokens = estimator.estimateTokens(msg);
                if (currentTokens + msgTokens <= maxTokens) {
                    selected.add(msg);
                    currentTokens += msgTokens;
                } else {
                    break;
                }
            }

            return selected;
        }

        @Override
        public String getName() {
            return "test";
        }
    }

    /**
     * Simple token estimator for testing.
     */
    private static class SimpleTestEstimator implements TokenEstimator {

        @Override
        public int estimateTokens(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return Math.max(1, text.length() / 4);
        }

        @Override
        public int estimateTokens(LLMMessage message) {
            if (message == null) {
                return 0;
            }
            return estimateTokens(message.content()) + 3;
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
            return 4096;
        }

        @Override
        public String getName() {
            return "SimpleTestEstimator";
        }
    }
}
