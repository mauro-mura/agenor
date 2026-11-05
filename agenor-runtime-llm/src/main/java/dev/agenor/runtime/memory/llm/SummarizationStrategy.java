package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.memory.llm.ContextWindowStrategy;
import dev.agenor.core.memory.llm.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Summarization strategy - includes system messages, a summary of the older ones, and recent messages.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Up to 10 messages, select as {@link FixedWindowStrategy} does</li>
 *   <li>Past 10, older system messages stay verbatim and the other older messages are summarized</li>
 *   <li>The window is the system messages, then the summary as a system message, then the last 10</li>
 *   <li>Select from it as {@link FixedWindowStrategy} does: a tight budget drops the oldest of the
 *       last 10 first. If the summary does not fit, select from the whole history without it</li>
 * </ol>
 *
 * <p><b>Only {@link #SummarizationStrategy(LLMProvider, String)} summarizes.</b> Without a provider,
 * which is what {@link ContextWindowStrategies#SUMMARIZED} is, the summary is a count of the messages
 * and holds none of their content, so the model cannot see what was said in them.
 *
 * <p><b>The provider is called every time a window is selected past 10 messages,</b> and is sent every
 * older message: nothing is cached. With {@code LLMAgent} that is one more model call for each prompt,
 * blocking until it answers. If it fails, the summary is the same count, and a WARN says why.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li><b>Pros:</b> Preserves overall context, continuous narrative</li>
 *   <li><b>Cons:</b> One more LLM call for each window, slower (~1-2s)</li>
 *   <li><b>Best For:</b> Very long conversations where context is critical</li>
 *   <li><b>Performance:</b> O(n) time + LLM call, O(n) space</li>
 *   <li><b>Requires LLM:</b> To summarize, yes. Without a provider the summary is only a count</li>
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

    private static final Logger log = LoggerFactory.getLogger(SummarizationStrategy.class);

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
     * <p>Used by {@link ContextWindowStrategies#SUMMARIZED}. The summary is a count of the
     * older messages, holding none of their content.
     */
    public SummarizationStrategy() {
        this.llmProvider = null;
        this.model = null;
    }

    /**
     * Select messages to fit within the token budget, with a summary of older messages when the
     * conversation exceeds {@value #RECENT_MESSAGE_COUNT} messages.
     *
     * <p>Older system messages are never summarized: they come first and verbatim, followed by the
     * summary (real or a count) and the most recent messages that fit in the remaining budget. If
     * only system messages are older, there is nothing to summarize and no provider call.
     *
     * <p>If the summary does not fit beside the system messages, selects from the whole history
     * with {@link FixedWindowStrategy}, without it.
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

        FixedWindowStrategy fixed = new FixedWindowStrategy();

        int recentStart = allMessages.size() - RECENT_MESSAGE_COUNT;
        List<LLMMessage> systemMessages = new ArrayList<>();
        List<LLMMessage> oldMessages = new ArrayList<>();
        for (LLMMessage msg : allMessages.subList(0, recentStart)) {
            (msg.isSystem() ? systemMessages : oldMessages).add(msg);
        }

        // Only instructions are old, and those go to the model whole
        if (oldMessages.isEmpty()) {
            return fixed.selectMessages(allMessages, maxTokens, estimator);
        }

        LLMMessage summaryMessage = LLMMessage.system(
                "Summary of earlier conversation: " + generateSummary(oldMessages)
        );

        List<LLMMessage> window = new ArrayList<>(systemMessages);
        window.add(summaryMessage);
        window.addAll(allMessages.subList(recentStart, allMessages.size()));

        List<LLMMessage> selected = fixed.selectMessages(window, maxTokens, estimator);
        return selected.contains(summaryMessage)
                ? selected
                : fixed.selectMessages(allMessages, maxTokens, estimator);
    }

    @Override
    public String getName() {
        return "summarized";
    }

    @Override
    public boolean requiresLLM() {
        return llmProvider != null;
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
     * <p>On LLM failure, or when no provider is configured, returns a count of the messages so
     * that {@link #selectMessages} can always produce a result. A failure's text goes to the log,
     * not into a prompt.
     *
     * <p><b>Note:</b> {@link LLMProvider#chat} returns a {@link java.util.concurrent.CompletableFuture};
     * this method calls {@code .join()} because {@link #selectMessages} is synchronous.
     *
     * @param oldMessages messages to summarize; must not be {@code null}
     * @return summary text, never {@code null}
     */
    private String generateSummary(List<LLMMessage> oldMessages) {
        String count = String.format("[Summary of %d earlier messages]", oldMessages.size());
        if (llmProvider == null) {
            return count;
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
            return (content != null && !content.isBlank()) ? content : count;
        } catch (Exception e) {
            log.warn("Could not summarize {} messages with model {}: the prompt holds a count of them instead",
                    oldMessages.size(), model, e);
            return count;
        }
    }
}
