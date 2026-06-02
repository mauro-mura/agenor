package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.memory.llm.ContextWindowStrategy;
import dev.agenor.core.memory.llm.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Summarization strategy - includes recent messages plus LLM-generated summary of old conversation.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Include most recent messages (last 10)</li>
 *   <li>If older messages exist, create summary via LLM call</li>
 *   <li>Insert summary as system message prepended to the result</li>
 *   <li>Adjust token budget accordingly</li>
 * </ol>
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li><b>Pros:</b> Preserves overall context, continuous narrative</li>
 *   <li><b>Cons:</b> Requires LLM call for summarization, slower (~1-2s)</li>
 *   <li><b>Best For:</b> Very long conversations where context is critical</li>
 *   <li><b>Performance:</b> O(n) time + LLM call, O(n) space</li>
 *   <li><b>Requires LLM:</b> Yes (falls back to placeholder when no provider is set)</li>
 *   <li><b>Overhead:</b> ~200 tokens for summary</li>
 * </ul>
 *
 * <p><b>Example (with LLM):</b>
 * <pre>{@code
 * LLMProvider provider = LLMProviderFactory.anthropic()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .build();
 *
 * ContextWindowStrategy strategy = new SummarizationStrategy(provider, "claude-3-5-sonnet-20241022");
 *
 * List<LLMMessage> selected = strategy.selectMessages(
 *     allMessages,
 *     2000,  // Token budget
 *     estimator
 * );
 * }</pre>
 *
 * <p><b>Example (placeholder mode via factory):</b>
 * <pre>{@code
 * import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.SUMMARIZED;
 * List<LLMMessage> selected = SUMMARIZED.selectMessages(allMessages, 2000, estimator);
 * }</pre>
 *
 * <p><b>Thread Safety:</b> Instances constructed with {@link #SummarizationStrategy()} are
 * stateless and thread-safe. Instances constructed with
 * {@link #SummarizationStrategy(LLMProvider, String)} inherit the thread-safety of the
 * provided {@link LLMProvider}.
 *
 * @since 0.6.0
 * @see ContextWindowStrategy
 * @see ContextWindowStrategies
 */
public class SummarizationStrategy implements ContextWindowStrategy {

    /**
     * Number of recent messages to keep unsummarized.
     */
    private static final int RECENT_MESSAGE_COUNT = 10;

    /**
     * Estimated token count for a typical summary.
     */
    private static final int ESTIMATED_SUMMARY_TOKENS = 200;

    /**
     * System prompt used when requesting a summary from the LLM.
     */
    private static final String SUMMARY_SYSTEM_PROMPT =
            "Summarize the following conversation concisely, preserving key facts and context.";

    /**
     * LLM provider used to generate summaries. {@code null} in placeholder mode.
     */
    private final LLMProvider llmProvider;

    /**
     * Model identifier passed to the provider. {@code null} in placeholder mode.
     */
    private final String model;

    /**
     * Create a summarization strategy backed by a real LLM.
     *
     * <p>Use this constructor when actual summarization is needed.
     * The provider is called synchronously (via {@code CompletableFuture#join}) inside
     * {@link #selectMessages} because that method is synchronous.
     *
     * @param llmProvider provider used to generate the summary; must not be {@code null}
     * @param model       model identifier passed to the provider; must not be {@code null}
     */
    public SummarizationStrategy(LLMProvider llmProvider, String model) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
    }

    /**
     * Create a summarization strategy in placeholder mode (no LLM call).
     *
     * <p>Used by {@link ContextWindowStrategies#SUMMARIZED}. Summary text will be a
     * descriptive placeholder string instead of a real LLM-generated summary.
     */
    public SummarizationStrategy() {
        this.llmProvider = null;
        this.model = null;
    }

    /**
     * Select messages to fit within the token budget, prepending an LLM-generated summary
     * of older messages when the conversation exceeds {@value #RECENT_MESSAGE_COUNT} messages.
     *
     * <p>The returned list always starts with a {@code SYSTEM} message containing the summary
     * (real or placeholder), followed by the most recent messages that fit in the remaining
     * token budget.
     *
     * <p>If the summary message alone exceeds {@code maxTokens}, falls back to
     * {@link FixedWindowStrategy} to ensure a non-empty result is always returned.
     *
     * @param allMessages complete conversation history; must not be {@code null}
     * @param maxTokens   maximum token budget; must be positive
     * @param estimator   token estimator; must not be {@code null}
     * @return selected messages fitting within {@code maxTokens}, never {@code null}
     * @throws IllegalArgumentException if any parameter fails validation
     */
    @Override
    public List<LLMMessage> selectMessages(
            List<LLMMessage> allMessages,
            int maxTokens,
            TokenEstimator estimator
    ) {
        // Validate parameters
        if (allMessages == null) {
            throw new IllegalArgumentException("allMessages cannot be null");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (estimator == null) {
            throw new IllegalArgumentException("estimator cannot be null");
        }

        // Handle empty list
        if (allMessages.isEmpty()) {
            return List.of();
        }

        // If few messages, use fixed strategy (no summarization needed)
        if (allMessages.size() <= RECENT_MESSAGE_COUNT) {
            return new FixedWindowStrategy().selectMessages(allMessages, maxTokens, estimator);
        }

        int recentStart = allMessages.size() - RECENT_MESSAGE_COUNT;
        List<LLMMessage> oldMessages = allMessages.subList(0, recentStart);

        String summaryText = generateSummary(oldMessages);
        LLMMessage summaryMessage = LLMMessage.system(
                "Summary of earlier conversation: " + summaryText
        );

        int summaryTokens = estimator.estimateTokens(summaryMessage);

        // Guard: if summary alone exceeds budget, fall back to fixed strategy
        if (summaryTokens > maxTokens) {
            return new FixedWindowStrategy().selectMessages(allMessages, maxTokens, estimator);
        }

        List<LLMMessage> selected = new ArrayList<>();
        selected.add(summaryMessage);
        int currentTokens = summaryTokens;

        // Add recent messages that fit in remaining budget
        for (int i = recentStart; i < allMessages.size(); i++) {
            LLMMessage msg = allMessages.get(i);
            int msgTokens = estimator.estimateTokens(msg);
            if (currentTokens + msgTokens <= maxTokens) {
                selected.add(msg);
                currentTokens += msgTokens;
            } else {
                // Budget exhausted, stop adding messages
                break;
            }
        }

        return selected;
    }

    @Override
    public String getName() {
        return "summarized";
    }

    @Override
    public boolean requiresLLM() {
        return true;
    }

    @Override
    public int getOverheadTokens() {
        return ESTIMATED_SUMMARY_TOKENS;
    }

    /**
     * Generate a summary of the provided messages.
     *
     * <p>When a {@link LLMProvider} is available, issues a blocking LLM call with
     * temperature {@code 0.3} and a max token budget of {@value #ESTIMATED_SUMMARY_TOKENS}.
     * The request is built as:
     * <ol>
     *   <li>A system message with {@link #SUMMARY_SYSTEM_PROMPT}</li>
     *   <li>The {@code oldMessages} to summarize</li>
     * </ol>
     *
     * <p>On LLM failure, or when running in placeholder mode (no provider configured),
     * returns a descriptive fallback string so that {@link #selectMessages} can always
     * produce a result.
     *
     * <p><b>Note:</b> {@link LLMProvider#chat} returns a {@link java.util.concurrent.CompletableFuture};
     * this method calls {@code .join()} because {@link #selectMessages} is synchronous.
     *
     * @param oldMessages messages to summarize; must not be {@code null}
     * @return summary text, never {@code null}
     */
    private String generateSummary(List<LLMMessage> oldMessages) {
        if (llmProvider == null) {
            return String.format("[Summary of %d earlier messages]", oldMessages.size());
        }

        List<LLMMessage> requestMessages = new ArrayList<>();
        requestMessages.add(LLMMessage.system(SUMMARY_SYSTEM_PROMPT));
        requestMessages.addAll(oldMessages);

        LLMRequest summaryRequest = LLMRequest.builder().model(model)
                .messages(requestMessages)
                .maxTokens(ESTIMATED_SUMMARY_TOKENS)
                .temperature(0.3)
                .build();

        try {
            LLMResponse response = llmProvider.chat(summaryRequest).join();
            String content = response.content();
            return (content != null && !content.isBlank())
                    ? content
                    : String.format("[Summary of %d earlier messages]", oldMessages.size());
        } catch (Exception e) {
            return String.format(
                    "[Summary of %d earlier messages - generation failed: %s]",
                    oldMessages.size(),
                    e.getMessage()
            );
        }
    }
}
