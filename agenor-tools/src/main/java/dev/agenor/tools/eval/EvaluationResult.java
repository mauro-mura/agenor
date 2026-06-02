package dev.agenor.tools.eval;

import dev.agenor.tools.health.HealthCheckService.HealthReport;
import dev.agenor.tools.metrics.MetricsService.MetricsSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating a scenario against an agent or system.
 *
 * <p>Contains all information about the evaluation including timing,
 * metrics, health status, and individual assertion results.
 *
 * @param scenarioId identifier of the evaluated scenario
 * @param status overall evaluation status
 * @param executionTime total time taken
 * @param metrics collected metrics during execution
 * @param healthReport health check results
 * @param assertions individual assertion results
 * @param errorMessage error message if status is ERROR
 * @param timestamp when evaluation completed
 *
 * @since 0.5.0
 */
public record EvaluationResult(
    String scenarioId,
    Status status,
    Duration executionTime,
    MetricsSnapshot metrics,
    HealthReport healthReport,
    List<AssertionResult> assertions,
    String errorMessage,
    Instant timestamp
) {

    /**
     * Evaluation status.
     */
    public enum Status {
        /** All assertions passed */
        PASSED,
        /** One or more assertions failed */
        FAILED,
        /** An error occurred during evaluation */
        ERROR,
        /** Evaluation timed out */
        TIMEOUT,
        /** Evaluation was skipped */
        SKIPPED
    }

    /**
     * Compact constructor with validation.
     */
    public EvaluationResult {
        Objects.requireNonNull(scenarioId, "Scenario ID cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
        Objects.requireNonNull(executionTime, "Execution time cannot be null");
        Objects.requireNonNull(assertions, "Assertions cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        assertions = List.copyOf(assertions);
    }

    /**
     * Creates a passed result.
     *
     * @param scenarioId scenario ID
     * @param executionTime time taken
     * @param metrics collected metrics
     * @param healthReport health results
     * @param assertions assertion results (all passed)
     * @return passed result
     */
    public static EvaluationResult passed(
            String scenarioId,
            Duration executionTime,
            MetricsSnapshot metrics,
            HealthReport healthReport,
            List<AssertionResult> assertions) {
        return new EvaluationResult(
            scenarioId, Status.PASSED, executionTime,
            metrics, healthReport, assertions,
            null, Instant.now()
        );
    }

    /**
     * Creates a failed result.
     *
     * @param scenarioId scenario ID
     * @param executionTime time taken
     * @param metrics collected metrics
     * @param healthReport health results
     * @param assertions assertion results (some failed)
     * @return failed result
     */
    public static EvaluationResult failed(
            String scenarioId,
            Duration executionTime,
            MetricsSnapshot metrics,
            HealthReport healthReport,
            List<AssertionResult> assertions) {
        return new EvaluationResult(
            scenarioId, Status.FAILED, executionTime,
            metrics, healthReport, assertions,
            null, Instant.now()
        );
    }

    /**
     * Creates an error result.
     *
     * @param scenarioId scenario ID
     * @param executionTime time taken before error
     * @param errorMessage error description
     * @return error result
     */
    public static EvaluationResult error(
            String scenarioId,
            Duration executionTime,
            String errorMessage) {
        return new EvaluationResult(
            scenarioId, Status.ERROR, executionTime,
            null, null, List.of(),
            errorMessage, Instant.now()
        );
    }

    /**
     * Creates a timeout result.
     *
     * @param scenarioId scenario ID
     * @param timeout configured timeout
     * @return timeout result
     */
    public static EvaluationResult timeout(String scenarioId, Duration timeout) {
        return new EvaluationResult(
            scenarioId, Status.TIMEOUT, timeout,
            null, null, List.of(),
            "Scenario timed out after " + timeout.toMillis() + "ms",
            Instant.now()
        );
    }

    /**
     * Creates a skipped result.
     *
     * @param scenarioId scenario ID
     * @param reason skip reason
     * @return skipped result
     */
    public static EvaluationResult skipped(String scenarioId, String reason) {
        return new EvaluationResult(
            scenarioId, Status.SKIPPED, Duration.ZERO,
            null, null, List.of(),
            reason, Instant.now()
        );
    }

    // === Convenience Methods ===

    /**
     * Checks if evaluation passed.
     *
     * @return true if all assertions passed
     */
    public boolean passed() {
        return status == Status.PASSED;
    }

    /**
     * Checks if evaluation failed.
     *
     * @return true if any assertion failed
     */
    public boolean failed() {
        return status == Status.FAILED;
    }

    /**
     * Gets the count of passed assertions.
     *
     * @return number of passed assertions
     */
    public long passedCount() {
        return assertions.stream().filter(AssertionResult::passed).count();
    }

    /**
     * Gets the count of failed assertions.
     *
     * @return number of failed assertions
     */
    public long failedCount() {
        return assertions.stream().filter(AssertionResult::failed).count();
    }

    /**
     * Gets the success rate as a percentage.
     *
     * @return success rate (0.0 to 100.0)
     */
    public double successRate() {
        if (assertions.isEmpty()) {
            return status == Status.PASSED ? 100.0 : 0.0;
        }
        return (double) passedCount() / assertions.size() * 100.0;
    }

    /**
     * Gets only the failed assertions.
     *
     * @return list of failed assertions
     */
    public List<AssertionResult> failedAssertions() {
        return assertions.stream()
            .filter(AssertionResult::failed)
            .toList();
    }

    /**
     * Formats the result for display.
     *
     * @return formatted result string
     */
    public String format() {
        StringBuilder sb = new StringBuilder();

        String icon = switch (status) {
            case PASSED -> "✅";
            case FAILED -> "❌";
            case ERROR -> "💥";
            case TIMEOUT -> "⏱️";
            case SKIPPED -> "⏭️";
        };

        sb.append(icon).append(" ").append(scenarioId);
        sb.append(" [").append(status).append("]");
        sb.append(" (").append(executionTime.toMillis()).append("ms)");

        if (!assertions.isEmpty()) {
            sb.append("\n   Assertions: ")
              .append(passedCount()).append("/").append(assertions.size()).append(" passed");
        }

        if (errorMessage != null) {
            sb.append("\n   Error: ").append(errorMessage);
        }

        // Show failed assertions
        for (AssertionResult assertion : failedAssertions()) {
            sb.append("\n   └─ ").append(assertion.format());
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
