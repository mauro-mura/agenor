package dev.agenor.tools.eval;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of a single assertion in an evaluation scenario.
 *
 * <p>Captures whether an assertion passed or failed, along with
 * contextual information for debugging failures.
 *
 * @param name descriptive name of the assertion
 * @param passed true if assertion succeeded
 * @param message description or failure reason
 * @param expected expected value (for comparison assertions)
 * @param actual actual value found
 * @param duration time taken to evaluate (optional)
 *
 * @since 0.5.0
 */
public record AssertionResult(
    String name,
    boolean passed,
    String message,
    Object expected,
    Object actual,
    Duration duration
) {

    /**
     * Compact constructor with validation.
     */
    public AssertionResult {
        Objects.requireNonNull(name, "Assertion name cannot be null");
    }

    /**
     * Creates a passed assertion result.
     *
     * @param name assertion name
     * @return passed result
     */
    public static AssertionResult pass(String name) {
        return new AssertionResult(name, true, "Passed", null, null, null);
    }

    /**
     * Creates a passed assertion with message.
     *
     * @param name assertion name
     * @param message success message
     * @return passed result
     */
    public static AssertionResult pass(String name, String message) {
        return new AssertionResult(name, true, message, null, null, null);
    }

    /**
     * Creates a failed assertion result.
     *
     * @param name assertion name
     * @param message failure reason
     * @return failed result
     */
    public static AssertionResult fail(String name, String message) {
        return new AssertionResult(name, false, message, null, null, null);
    }

    /**
     * Creates a failed assertion with expected/actual values.
     *
     * @param name assertion name
     * @param message failure reason
     * @param expected expected value
     * @param actual actual value
     * @return failed result
     */
    public static AssertionResult fail(String name, String message, Object expected, Object actual) {
        return new AssertionResult(name, false, message, expected, actual, null);
    }

    /**
     * Creates an assertion result with timing information.
     *
     * @param name assertion name
     * @param passed whether passed
     * @param message description
     * @param duration evaluation time
     * @return result with timing
     */
    public static AssertionResult withTiming(String name, boolean passed, String message, Duration duration) {
        return new AssertionResult(name, passed, message, null, null, duration);
    }

    /**
     * Checks if this assertion failed.
     *
     * @return true if failed
     */
    public boolean failed() {
        return !passed;
    }

    /**
     * Returns a formatted string for display.
     *
     * @return formatted result string
     */
    public String format() {
        String icon = passed ? "✓" : "✗";
        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(" ").append(name);

        if (message != null && !message.isEmpty()) {
            sb.append(": ").append(message);
        }

        if (!passed && expected != null) {
            sb.append(" [expected: ").append(expected);
            sb.append(", actual: ").append(actual).append("]");
        }

        if (duration != null) {
            sb.append(" (").append(duration.toMillis()).append("ms)");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
