package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.llm.ModelTokenLimits;
import dev.agenor.core.memory.llm.TokenEstimator;

import java.util.List;
import java.util.Objects;

/**
 * Simple character-based token estimator.
 *
 * <p>This implementation uses a simple heuristic to estimate token counts:
 * <ul>
 *   <li>~1 token per 4 characters for English text</li>
 *   <li>Message overhead: +3 tokens per message</li>
 *   <li>Function call overhead: +10 tokens</li>
 * </ul>
 *
 * <p><b>Accuracy:</b> This is a rough approximation (±20%). For exact token counts,
 * use a tokenizer library like tiktoken for OpenAI models or the Claude tokenizer.
 *
 * <p><b>Performance:</b> Very fast - O(n) where n is text length.
 *
 * <p><b>Thread Safety:</b> This class is stateless and thread-safe.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * TokenEstimator estimator = new SimpleTokenEstimator();
 *
 * // Estimate tokens for text
 * int tokens = estimator.estimateTokens("Hello, world!");  // ~3 tokens
 *
 * // Estimate tokens for message
 * LLMMessage msg = LLMMessage.user("What's the weather?");
 * int msgTokens = estimator.estimateTokens(msg);  // ~8 tokens
 *
 * // Check if fits in model
 * boolean fits = estimator.fitsInContextWindow("gpt-4", msgTokens);  // true
 * }</pre>
 *
 * @since 0.6.0
 */
public class SimpleTokenEstimator implements TokenEstimator {

    /**
     * Characters per token (approximate).
     */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Message overhead tokens (role, formatting).
     */
    private static final int MESSAGE_OVERHEAD = 3;

    /**
     * Function call overhead tokens.
     */
    private static final int FUNCTION_CALL_OVERHEAD = 10;

    /**
     * Create a new simple token estimator.
     */
    public SimpleTokenEstimator() {
        // Stateless - no initialization needed
    }

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // Simple heuristic: 1 token per 4 characters
        // This works reasonably well for English text
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }

    @Override
    public int estimateTokens(LLMMessage message) {
        if (message == null) {
            return 0;
        }

        // Start with content tokens
        int contentTokens = estimateTokens(message.content());

        // Add message overhead (role, formatting)
        int overhead = MESSAGE_OVERHEAD;

        // Add function call overhead if present
        if (message.hasFunctionCalls()) {
            overhead += FUNCTION_CALL_OVERHEAD;

            // Add tokens for function name and arguments
            // This is approximate - actual overhead varies
            String funcName = message.functionCalls().getFirst().name();
            String funcArgs = message.functionCalls().getFirst().arguments();
            contentTokens += estimateTokens(funcName);
            contentTokens += estimateTokens(funcArgs);
        }

        return contentTokens + overhead;
    }

    @Override
    public int estimateTokens(List<LLMMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        // Sum of individual message tokens
        return messages.stream()
            .mapToInt(this::estimateTokens)
            .sum();
    }

    @Override
    public int getContextWindowSize(String model) {
        Objects.requireNonNull(model, "Model cannot be null");

        // Delegate to ModelTokenLimits registry
        return ModelTokenLimits.getLimit(model);
    }

    @Override
    public String getName() {
        return "SimpleTokenEstimator";
    }

    @Override
    public String getAccuracyLevel() {
        return "low";  // ±20% accuracy
    }

    /**
     * Estimate tokens for text with better accuracy for code.
     *
     * <p>Code typically has more tokens per character due to
     * special characters and syntax.
     *
     * @param text the text (likely code)
     * @return estimated token count
     */
    public int estimateTokensForCode(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // Code has ~1 token per 3 characters (more dense)
        return Math.max(1, text.length() / 3);
    }

    /**
     * Estimate tokens with language-specific adjustment.
     *
     * <p>Different languages have different token densities:
     * <ul>
     *   <li>English: ~1 token / 4 chars</li>
     *   <li>Chinese: ~1 token / 2 chars</li>
     *   <li>Code: ~1 token / 3 chars</li>
     * </ul>
     *
     * @param text the text
     * @param language language hint ("en", "zh", "code", etc.)
     * @return estimated token count
     */
    public int estimateTokensWithLanguage(String text, String language) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int divisor = switch (language.toLowerCase()) {
            case "en", "english" -> 4;
            case "zh", "chinese", "ja", "japanese" -> 2;
            case "code", "programming" -> 3;
            default -> 4;  // Default to English
        };

        return Math.max(1, text.length() / divisor);
    }
}
