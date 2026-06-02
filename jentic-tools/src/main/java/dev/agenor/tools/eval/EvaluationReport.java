package dev.agenor.tools.eval;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Generates reports from evaluation results.
 *
 * <p>Supports multiple output formats including console, Markdown,
 * JSON, and HTML. Reports include summary statistics and detailed
 * results for each scenario.
 *
 * <p>Example usage:
 * <pre>{@code
 * List<EvaluationResult> results = runner.runAll(scenarios);
 * EvaluationReport report = new EvaluationReport(results);
 *
 * // Print to console
 * report.printSummary();
 *
 * // Export to files
 * report.toMarkdown(Path.of("report.md"));
 * report.toJson(Path.of("report.json"));
 * }</pre>
 *
 * @since 0.5.0
 */
public class EvaluationReport {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<EvaluationResult> results;
    private final Instant generatedAt;

    /**
     * Creates a report from evaluation results.
     *
     * @param results list of evaluation results
     */
    public EvaluationReport(List<EvaluationResult> results) {
        this.results = List.copyOf(Objects.requireNonNull(results, "Results cannot be null"));
        this.generatedAt = Instant.now();
    }

    // === Statistics ===

    /**
     * Gets total number of scenarios.
     *
     * @return scenario count
     */
    public int totalScenarios() {
        return results.size();
    }

    /**
     * Gets count of passed scenarios.
     *
     * @return passed count
     */
    public long passedCount() {
        return results.stream().filter(EvaluationResult::passed).count();
    }

    /**
     * Gets count of failed scenarios.
     *
     * @return failed count
     */
    public long failedCount() {
        return results.stream()
            .filter(r -> r.status() == EvaluationResult.Status.FAILED)
            .count();
    }

    /**
     * Gets count of error scenarios.
     *
     * @return error count
     */
    public long errorCount() {
        return results.stream()
            .filter(r -> r.status() == EvaluationResult.Status.ERROR)
            .count();
    }

    /**
     * Gets overall success rate.
     *
     * @return success rate as percentage (0.0 to 100.0)
     */
    public double successRate() {
        if (results.isEmpty()) return 0.0;
        return (double) passedCount() / results.size() * 100.0;
    }

    /**
     * Gets total execution time across all scenarios.
     *
     * @return total duration
     */
    public Duration totalDuration() {
        return results.stream()
            .map(EvaluationResult::executionTime)
            .reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * Gets average execution time per scenario.
     *
     * @return average duration
     */
    public Duration averageDuration() {
        if (results.isEmpty()) return Duration.ZERO;
        return totalDuration().dividedBy(results.size());
    }

    /**
     * Gets total assertion count.
     *
     * @return total assertions
     */
    public long totalAssertions() {
        return results.stream()
            .mapToLong(r -> r.assertions().size())
            .sum();
    }

    /**
     * Gets passed assertion count.
     *
     * @return passed assertions
     */
    public long passedAssertions() {
        return results.stream()
            .flatMap(r -> r.assertions().stream())
            .filter(AssertionResult::passed)
            .count();
    }

    // === Output Formats ===

    /**
     * Prints summary to stdout.
     */
    public void printSummary() {
        printSummary(new PrintWriter(System.out, true));
    }

    /**
     * Prints summary to the given writer.
     *
     * @param out print writer
     */
    public void printSummary(PrintWriter out) {
        String border = "═".repeat(60);

        out.println("╔" + border + "╗");
        out.println("║" + center("AGENT EVALUATION REPORT", 60) + "║");
        out.println("╠" + border + "╣");

        // Summary line
        String summary = String.format(
            "Scenarios: %d | Passed: %d | Failed: %d | Success: %.1f%%",
            totalScenarios(), passedCount(), failedCount() + errorCount(), successRate()
        );
        out.println("║" + center(summary, 60) + "║");

        // Timing line
        String timing = String.format(
            "Total: %s | Avg per scenario: %s",
            formatDuration(totalDuration()),
            formatDuration(averageDuration())
        );
        out.println("║" + center(timing, 60) + "║");

        out.println("╠" + border + "╣");

        // Individual results
        for (EvaluationResult result : results) {
            String icon = switch (result.status()) {
                case PASSED -> "✅";
                case FAILED -> "❌";
                case ERROR -> "💥";
                case TIMEOUT -> "⏱️";
                case SKIPPED -> "⏭️";
            };

            String line = String.format(
                "%s %-35s %s %s",
                icon,
                truncate(result.scenarioId(), 35),
                result.status(),
                formatDuration(result.executionTime())
            );
            out.println("║ " + padRight(line, 58) + " ║");

            // Show failure details
            if (result.failed()) {
                for (AssertionResult assertion : result.failedAssertions()) {
                    String detail = "   └─ " + truncate(assertion.message(), 50);
                    out.println("║ " + padRight(detail, 58) + " ║");
                }
            }
            if (result.errorMessage() != null) {
                String detail = "   └─ " + truncate(result.errorMessage(), 50);
                out.println("║ " + padRight(detail, 58) + " ║");
            }
        }

        out.println("╚" + border + "╝");
        out.flush();
    }

    /**
     * Generates Markdown report.
     *
     * @return Markdown string
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        sb.append("# Agent Evaluation Report\n\n");
        sb.append("Generated: ").append(TIMESTAMP_FORMAT.format(generatedAt)).append("\n\n");

        // Summary table
        sb.append("## Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Scenarios | ").append(totalScenarios()).append(" |\n");
        sb.append("| Passed | ").append(passedCount()).append(" |\n");
        sb.append("| Failed | ").append(failedCount()).append(" |\n");
        sb.append("| Errors | ").append(errorCount()).append(" |\n");
        sb.append("| Success Rate | ").append(String.format("%.1f%%", successRate())).append(" |\n");
        sb.append("| Total Duration | ").append(formatDuration(totalDuration())).append(" |\n");
        sb.append("| Total Assertions | ").append(totalAssertions()).append(" |\n");
        sb.append("\n");

        // Results table
        sb.append("## Results\n\n");
        sb.append("| Status | Scenario | Duration | Assertions |\n");
        sb.append("|--------|----------|----------|------------|\n");

        for (EvaluationResult result : results) {
            String icon = switch (result.status()) {
                case PASSED -> "✅";
                case FAILED -> "❌";
                case ERROR -> "💥";
                case TIMEOUT -> "⏱️";
                case SKIPPED -> "⏭️";
            };

            sb.append("| ").append(icon).append(" ")
              .append(result.status()).append(" | ")
              .append(result.scenarioId()).append(" | ")
              .append(formatDuration(result.executionTime())).append(" | ")
              .append(result.passedCount()).append("/").append(result.assertions().size())
              .append(" |\n");
        }
        sb.append("\n");

        // Failed scenarios details
        List<EvaluationResult> failures = results.stream()
            .filter(r -> !r.passed())
            .toList();

        if (!failures.isEmpty()) {
            sb.append("## Failures\n\n");
            for (EvaluationResult result : failures) {
                sb.append("### ").append(result.scenarioId()).append("\n\n");

                if (result.errorMessage() != null) {
                    sb.append("**Error:** ").append(result.errorMessage()).append("\n\n");
                }

                if (!result.failedAssertions().isEmpty()) {
                    sb.append("**Failed Assertions:**\n\n");
                    for (AssertionResult assertion : result.failedAssertions()) {
                        sb.append("- ").append(assertion.name())
                          .append(": ").append(assertion.message());
                        if (assertion.expected() != null) {
                            sb.append(" (expected: ").append(assertion.expected())
                              .append(", actual: ").append(assertion.actual()).append(")");
                        }
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Writes Markdown report to file.
     *
     * @param path output file path
     * @throws IOException if writing fails
     */
    public void toMarkdown(Path path) throws IOException {
        Files.writeString(path, toMarkdown());
    }

    /**
     * Generates JSON report.
     *
     * @return JSON string
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();

        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(generatedAt).append("\",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"totalScenarios\": ").append(totalScenarios()).append(",\n");
        sb.append("    \"passed\": ").append(passedCount()).append(",\n");
        sb.append("    \"failed\": ").append(failedCount()).append(",\n");
        sb.append("    \"errors\": ").append(errorCount()).append(",\n");
        sb.append("    \"successRate\": ").append(String.format("%.2f", successRate())).append(",\n");
        sb.append("    \"totalDurationMs\": ").append(totalDuration().toMillis()).append(",\n");
        sb.append("    \"totalAssertions\": ").append(totalAssertions()).append("\n");
        sb.append("  },\n");

        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            EvaluationResult result = results.get(i);
            sb.append("    {\n");
            sb.append("      \"scenarioId\": \"").append(escapeJson(result.scenarioId())).append("\",\n");
            sb.append("      \"status\": \"").append(result.status()).append("\",\n");
            sb.append("      \"durationMs\": ").append(result.executionTime().toMillis()).append(",\n");
            sb.append("      \"passedAssertions\": ").append(result.passedCount()).append(",\n");
            sb.append("      \"totalAssertions\": ").append(result.assertions().size());

            if (result.errorMessage() != null) {
                sb.append(",\n      \"error\": \"").append(escapeJson(result.errorMessage())).append("\"");
            }

            if (!result.failedAssertions().isEmpty()) {
                sb.append(",\n      \"failures\": [\n");
                List<AssertionResult> failures = result.failedAssertions();
                for (int j = 0; j < failures.size(); j++) {
                    AssertionResult a = failures.get(j);
                    sb.append("        {\"name\": \"").append(escapeJson(a.name()))
                      .append("\", \"message\": \"").append(escapeJson(a.message())).append("\"}");
                    if (j < failures.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("      ]");
            }

            sb.append("\n    }");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Writes JSON report to file.
     *
     * @param path output file path
     * @throws IOException if writing fails
     */
    public void toJson(Path path) throws IOException {
        Files.writeString(path, toJson());
    }

    // === Helper Methods ===

    private static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else {
            return String.format("%.1fm", millis / 60000.0);
        }
    }

    private static String center(String text, int width) {
        if (text.length() >= width) return text;
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text + " ".repeat(width - text.length() - padding);
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
