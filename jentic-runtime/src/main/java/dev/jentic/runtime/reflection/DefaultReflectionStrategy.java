package dev.jentic.runtime.reflection;

import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.llm.LLMProvider;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.reflection.CritiqueResult;
import dev.jentic.core.reflection.ReflectionConfig;
import dev.jentic.core.reflection.ReflectionStrategy;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link ReflectionStrategy} implementation backed by an {@link LLMProvider}.
 *
 * <p>Builds a critique prompt (custom or {@link #DEFAULT_CRITIQUE_PROMPT}), calls
 * {@link LLMProvider#chat} to obtain structured feedback, then parses the quality
 * score from the response text using the pattern {@code score: X.X}.
 *
 * <p>Score parsing rules:
 * <ul>
 *   <li>Pattern {@code score:\s*([0-9]*\.?[0-9]+)} is matched case-insensitively.</li>
 *   <li>Parsed value is clamped to {@code [0.0, 1.0]}.</li>
 *   <li>If no score is found the response is treated as requiring revision
 *       with a conservative score of {@code 0.5}.</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * DefaultReflectionStrategy strategy = new DefaultReflectionStrategy(llmProvider);
 * CritiqueResult result = strategy.critique(output, task, ReflectionConfig.defaults()).join();
 * }</pre>
 * 
 * @since 0.12.0
 */
public class DefaultReflectionStrategy implements ReflectionStrategy {

    /**
     * Default critique prompt sent to the LLM when no custom prompt is configured.
     *
     * <p>The prompt instructs the model to reply with a score on the last line using
     * the format {@code score: X.X} so it can be reliably parsed.
     */
    public static final String DEFAULT_CRITIQUE_PROMPT =
            "You are a quality evaluator. Review the following output produced for the given task.\n" +
            "Provide concise, actionable feedback on how it could be improved.\n" +
            "On the last line of your response, write the quality score in the format: score: X.X\n" +
            "The score must be a decimal between 0.0 (very poor) and 1.0 (excellent).\n\n" +
            "Task: %s\n\n" +
            "Output to evaluate:\n%s";

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("score:\\s*(-?[0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE);

    private static final double FALLBACK_SCORE = 0.5;

    private final LLMProvider llmProvider;

    /**
     * Creates a {@code DefaultReflectionStrategy} using the given {@link LLMProvider}.
     *
     * @param llmProvider the provider used to call the critique LLM (non-null)
     */
    public DefaultReflectionStrategy(LLMProvider llmProvider) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds a critique prompt, calls the LLM, and parses the score from the response.
     * If the score meets or exceeds {@link ReflectionConfig#scoreThreshold()} the returned
     * {@link CritiqueResult} has {@code shouldRevise = false}; otherwise {@code true}.
     */
    @Override
    public CompletableFuture<CritiqueResult> critique(
            String originalOutput,
            String task,
            ReflectionConfig config) {

        Objects.requireNonNull(originalOutput, "originalOutput must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(config, "config must not be null");

        String promptText = buildPrompt(originalOutput, task, config);

        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user(promptText))
                .build();

        return llmProvider.chat(request)
                .thenApply(response -> parseResult(response.content(), config.scoreThreshold()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String buildPrompt(String output, String task, ReflectionConfig config) {
        if (config.hasCustomPrompt()) {
            return config.critiquePrompt();
        }
        return String.format(DEFAULT_CRITIQUE_PROMPT, task, output);
    }

    /**
     * Parses a {@link CritiqueResult} from the raw LLM response text.
     *
     * @param responseText    raw text returned by the LLM
     * @param scoreThreshold  threshold below which revision is required
     * @return parsed result
     */
    private static CritiqueResult parseResult(String responseText, double scoreThreshold) {
        if (responseText == null || responseText.isBlank()) {
            return CritiqueResult.revise("No critique received from the LLM.", FALLBACK_SCORE);
        }

        double score = extractScore(responseText);
        String feedback = responseText.trim();
        boolean shouldRevise = score < scoreThreshold;

        return new CritiqueResult(feedback, shouldRevise, score);
    }

    /**
     * Extracts the numeric score from the LLM response using {@link #SCORE_PATTERN}.
     * Returns {@link #FALLBACK_SCORE} when no match is found.
     */
    static double extractScore(String text) {
        Matcher matcher = SCORE_PATTERN.matcher(text);
        if (matcher.find()) {
            double raw = Double.parseDouble(matcher.group(1));
            return Math.max(0.0, Math.min(1.0, raw));
        }
        return FALLBACK_SCORE;
    }
}