package dev.agenor.core.reflection;

/**
 * Configuration for the Generate → Critique → Revise reflection loop.
 *
 * <p>Pass an instance of this record to {@link ReflectionStrategy#critique} and to
 * {@code ReflectionBehavior} to control iteration limits, quality thresholds, and
 * the critique prompt template.
 *
 * <p>Use {@link #defaults()} for sensible out-of-the-box settings, or construct a
 * custom instance when fine-grained control is needed:
 *
 * <pre>{@code
 * // Default: 2 iterations, threshold 0.8, built-in critique prompt
 * ReflectionConfig config = ReflectionConfig.defaults();
 *
 * // Custom: stricter quality bar, more iterations, domain-specific prompt
 * ReflectionConfig config = new ReflectionConfig(
 *     3,
 *     0.9,
 *     "Evaluate the following output for factual accuracy and completeness. " +
 *     "Reply with 'score: X.X' and detailed feedback.");
 * }</pre>
 *
 * @param maxIterations  maximum number of revise cycles before returning the best result
 *                       so far; must be {@code >= 1}
 * @param scoreThreshold minimum {@link CritiqueResult#score()} for early stop;
 *                       must be in {@code [0.0, 1.0]}
 * @param critiquePrompt custom prompt template sent to the LLM during critique, or
 *                       {@code null} to use the default template defined in
 *                       {@code DefaultReflectionStrategy}
 *
 * @see ReflectionStrategy
 * @see CritiqueResult
 *
 * @since 0.12.0
 */
public record ReflectionConfig(
        int maxIterations,
        double scoreThreshold,
        String critiquePrompt) {

    /**
     * Compact constructor — validates fields.
     */
    public ReflectionConfig {
        if (maxIterations < 1) {
            throw new IllegalArgumentException(
                    "maxIterations must be >= 1 but was: " + maxIterations);
        }
        if (scoreThreshold < 0.0 || scoreThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "scoreThreshold must be in [0.0, 1.0] but was: " + scoreThreshold);
        }
    }

    /**
     * Returns a {@code ReflectionConfig} with sensible defaults:
     * <ul>
     *   <li>{@code maxIterations = 2}</li>
     *   <li>{@code scoreThreshold = 0.8}</li>
     *   <li>{@code critiquePrompt = null} (uses built-in template)</li>
     * </ul>
     *
     * @return default configuration instance
     */
    public static ReflectionConfig defaults() {
        return new ReflectionConfig(2, 0.8, null);
    }

    /**
     * Returns {@code true} if a custom critique prompt has been provided.
     *
     * @return {@code true} when {@code critiquePrompt} is non-null and non-blank
     */
    public boolean hasCustomPrompt() {
        return critiquePrompt != null && !critiquePrompt.isBlank();
    }
}
