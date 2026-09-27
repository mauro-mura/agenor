package dev.agenor.runtime.memory.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.llm.ModelTokenLimits;
import dev.agenor.core.memory.llm.TokenEstimator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SimpleTokenEstimator.
 */
class SimpleTokenEstimatorTest {

    private static final String TEST_MODEL = "test-estimator-model";

    private SimpleTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new SimpleTokenEstimator();
        // Register a test model so token-budget tests don't rely on vendor registrations
        ModelTokenLimits.register(TEST_MODEL, 8_192);
    }

    @AfterEach
    void cleanup() {
        ModelTokenLimits.unregister(TEST_MODEL);
    }

    // ========== BASIC ESTIMATION TESTS ==========

    @Test
    void estimateTokens_shouldReturnZeroForNullText() {
        assertThat(estimator.estimateTokens((String) null)).isZero();
    }

    @Test
    void estimateTokens_shouldReturnZeroForEmptyText() {
        assertThat(estimator.estimateTokens("")).isZero();
    }

    @Test
    void estimateTokens_shouldEstimateBasedOnCharacterCount() {
        // "Hello, world!" = 13 chars → ~1 token per 4 chars = ~3 tokens
        assertThat(estimator.estimateTokens("Hello, world!")).isBetween(2, 4);
    }

    @Test
    void estimateTokens_shouldReturnAtLeastOneForNonEmptyText() {
        assertThat(estimator.estimateTokens("Hi")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void estimateTokens_shouldHandleLongerText() {
        String text = "This is a longer piece of text that contains multiple sentences. " +
                      "It should estimate more tokens based on the character count.";
        int expected = text.length() / 4;
        assertThat(estimator.estimateTokens(text)).isBetween(expected - 5, expected + 5);
    }

    // ========== MESSAGE ESTIMATION TESTS ==========

    @Test
    void estimateTokens_shouldReturnZeroForNullMessage() {
        assertThat(estimator.estimateTokens((LLMMessage) null)).isZero();
    }

    @Test
    void estimateTokens_shouldIncludeMessageOverhead() {
        LLMMessage message = LLMMessage.user("Test");
        int contentTokens = estimator.estimateTokens("Test");
        assertThat(estimator.estimateTokens(message)).isGreaterThan(contentTokens);
    }

    @Test
    void estimateTokens_shouldHandleUserMessage() {
        LLMMessage message = LLMMessage.user("What's the weather?");
        assertThat(estimator.estimateTokens(message)).isPositive();
    }

    @Test
    void estimateTokens_shouldHandleSystemMessage() {
        LLMMessage message = LLMMessage.system("You are a helpful assistant.");
        assertThat(estimator.estimateTokens(message)).isPositive();
    }

    @Test
    void estimateTokens_shouldHandleAssistantMessage() {
        LLMMessage message = LLMMessage.assistant("I can help with that.");
        assertThat(estimator.estimateTokens(message)).isPositive();
    }

    // ========== MESSAGE LIST ESTIMATION TESTS ==========

    @Test
    void estimateTokens_shouldReturnZeroForNullList() {
        assertThat(estimator.estimateTokens((List<LLMMessage>) null)).isZero();
    }

    @Test
    void estimateTokens_shouldReturnZeroForEmptyList() {
        assertThat(estimator.estimateTokens(List.of())).isZero();
    }

    @Test
    void estimateTokens_shouldSumTokensAcrossMessages() {
        List<LLMMessage> messages = List.of(
                LLMMessage.system("You are helpful"),
                LLMMessage.user("Hi"),
                LLMMessage.assistant("Hello!")
        );
        int total = estimator.estimateTokens(messages);
        int sum = messages.stream().mapToInt(estimator::estimateTokens).sum();
        assertThat(total).isEqualTo(sum);
    }

    @Test
    void estimateTokens_shouldScaleWithMessageCount() {
        LLMMessage msg = LLMMessage.user("Same content");
        int single = estimator.estimateTokens(List.of(msg));
        int triple = estimator.estimateTokens(List.of(msg, msg, msg));
        assertThat(triple).isGreaterThan(single);
    }

    // ========== IMPLEMENTS TOKENESTIMATOR ==========

    @Test
    void shouldImplementTokenEstimatorInterface() {
        assertThat(estimator).isInstanceOf(TokenEstimator.class);
    }

    // ========== CONTEXT LIMIT INTEGRATION ==========

    @Test
    void estimateTokens_shouldFitWithinRegisteredModelLimit() {
        // Verify that a short conversation is well within the test model's context window
        List<LLMMessage> messages = List.of(
                LLMMessage.system("You are helpful"),
                LLMMessage.user("Hello"),
                LLMMessage.assistant("Hi!")
        );
        int contextLimit = ModelTokenLimits.getLimit(TEST_MODEL); // 8_192
        assertThat(estimator.estimateTokens(messages)).isLessThan(contextLimit);
    }

    @Test
    void estimateTokens_shouldUseGetLimitOrDefaultForUnknownModel() {
        int budget = ModelTokenLimits.getLimitOrDefault("non-existent-model", 16_384);
        List<LLMMessage> messages = List.of(LLMMessage.user("test"));
        assertThat(estimator.estimateTokens(messages)).isLessThan(budget);
    }

    // ========== UNKNOWN-MODEL WARNING ==========

    @Test
    void getContextWindowSize_shouldWarnOnceForAnUnregisteredModel() {
        String unknownModel = "warn-once-unregistered-model";
        assertThat(ModelTokenLimits.isKnown(unknownModel)).isFalse();

        List<String> warnings = captureWarnings(() -> {
            estimator.getContextWindowSize(unknownModel);
            estimator.getContextWindowSize(unknownModel);
            estimator.getContextWindowSize(unknownModel);
        });

        assertThat(warnings)
            .as("three calls for the same unknown model must produce exactly one warning")
            .hasSize(1);
        assertThat(warnings.get(0)).contains(unknownModel).contains("ModelTokenLimits.register");
    }

    @Test
    void getContextWindowSize_shouldNotWarnForARegisteredModel() {
        List<String> warnings = captureWarnings(() -> estimator.getContextWindowSize(TEST_MODEL));
        assertThat(warnings).isEmpty();
    }

    @Test
    void getContextWindowSize_shouldStillReturnDefaultLimitForUnknownModel() {
        assertThat(estimator.getContextWindowSize("another-unregistered-model"))
            .isEqualTo(ModelTokenLimits.DEFAULT_LIMIT);
    }

    private List<String> captureWarnings(Runnable action) {
        var logger = (Logger) LoggerFactory.getLogger(SimpleTokenEstimator.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
