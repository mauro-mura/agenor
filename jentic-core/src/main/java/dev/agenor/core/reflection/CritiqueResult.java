package dev.agenor.core.reflection;

/**
 * Immutable result produced by a {@link ReflectionStrategy} after critiquing an output.
 *
 * <p>The {@code score} field is normalised to {@code [0.0, 1.0]}, where {@code 1.0}
 * represents a perfect output. {@code shouldRevise} should be {@code true} when the
 * score is below the configured threshold and further iteration may improve quality.
 *
 * <p>Example:
 * <pre>{@code
 * CritiqueResult result = new CritiqueResult(
 *     "The answer lacks concrete examples.",
 *     true,
 *     0.6);
 *
 * if (result.shouldRevise()) {
 *     // re-run the generation with result.feedback() injected into the prompt
 * }
 * }</pre>
 *
 * @param feedback     human-readable critique that can be appended to the next prompt;
 *                     never {@code null}, may be empty when {@code shouldRevise} is {@code false}
 * @param shouldRevise {@code true} if the output should be regenerated using {@code feedback}
 * @param score        quality score in {@code [0.0, 1.0]}; higher is better
 *
 * @see ReflectionStrategy
 * @see ReflectionConfig
 *
 * @since 0.12.0
 */
public record CritiqueResult(
        String feedback,
        boolean shouldRevise,
        double score) {

    /**
     * Compact constructor — validates the score range.
     */
    public CritiqueResult {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(
                    "score must be in [0.0, 1.0] but was: " + score);
        }
        if (feedback == null) {
            throw new IllegalArgumentException("feedback must not be null");
        }
    }

    /**
     * Convenience factory for an accepted result (no revision needed).
     *
     * @param score quality score in {@code [0.0, 1.0]}
     * @return a {@code CritiqueResult} with {@code shouldRevise = false} and empty feedback
     */
    public static CritiqueResult accepted(double score) {
        return new CritiqueResult("", false, score);
    }

    /**
     * Convenience factory for a result that requires revision.
     *
     * @param feedback critique to inject into the next prompt
     * @param score    quality score in {@code [0.0, 1.0]}
     * @return a {@code CritiqueResult} with {@code shouldRevise = true}
     */
    public static CritiqueResult revise(String feedback, double score) {
        return new CritiqueResult(feedback, true, score);
    }
}
