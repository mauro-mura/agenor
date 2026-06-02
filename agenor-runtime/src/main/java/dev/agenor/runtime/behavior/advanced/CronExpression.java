package dev.agenor.runtime.behavior.advanced;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Simple Cron Expression Parser for ScheduledBehavior.
 *
 * <p>Supports standard cron format with 6 fields:
 * <pre>
 * ┌───────────── second (0-59)
 * │ ┌─────────── minute (0-59)
 * │ │ ┌───────── hour (0-23)
 * │ │ │ ┌─────── day of month (1-31)
 * │ │ │ │ ┌───── month (1-12)
 * │ │ │ │ │ ┌─── day of week (0-6, 0=Sunday)
 * │ │ │ │ │ │
 * * * * * * *
 * </pre>
 *
 * <p>Supported special characters:
 * <ul>
 *   <li>{@code *} - Any value</li>
 *   <li>{@code ,} - Value list separator</li>
 *   <li>{@code -} - Range of values</li>
 *   <li>{@code /} - Step values</li>
 * </ul>
 *
 */
public class CronExpression {

    private final String expression;
    private final Set<Integer> seconds;
    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;

    private static final Map<String, Integer> DAY_OF_WEEK_MAP = Map.of(
        "SUN", 0, "MON", 1, "TUE", 2, "WED", 3,
        "THU", 4, "FRI", 5, "SAT", 6
    );

    private static final Map<String, Integer> MONTH_MAP = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3),
            Map.entry("APR", 4), Map.entry("MAY", 5), Map.entry("JUN", 6),
            Map.entry("JUL", 7), Map.entry("AUG", 8), Map.entry("SEP", 9),
            Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12)
    );

    /**
     * Parse a cron expression.
     *
     * @param expression the cron expression (6 fields: second minute hour day month dayOfWeek)
     * @return parsed CronExpression
     * @throws IllegalArgumentException if expression is invalid
     */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("Cron expression cannot be null or empty");
        }

        return new CronExpression(expression.trim());
    }

    private CronExpression(String expression) {
        this.expression = expression;

        String[] parts = expression.split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                "Invalid cron expression. Expected 6 fields, got " + parts.length +
                ". Format: second minute hour dayOfMonth month dayOfWeek"
            );
        }

        this.seconds = parseField(parts[0], 0, 59);
        this.minutes = parseField(parts[1], 0, 59);
        this.hours = parseField(parts[2], 0, 23);
        this.daysOfMonth = parseField(parts[3], 1, 31);
        this.months = parseField(parts[4], 1, 12, MONTH_MAP);
        this.daysOfWeek = parseField(parts[5], 0, 6, DAY_OF_WEEK_MAP);
    }

    /**
     * Calculate the next execution time after the given time.
     *
     * @param after the time after which to find the next execution
     * @return the next execution time, or null if no future execution is possible
     */
    public ZonedDateTime getNextExecution(ZonedDateTime after) {
        ZonedDateTime candidate = after.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);

        // Safety limit: don't search more than 4 years into the future
        ZonedDateTime limit = after.plusYears(4);

        while (candidate.isBefore(limit)) {
            if (matches(candidate)) {
                return candidate;
            }
            candidate = candidate.plusSeconds(1);
        }

        return null; // No matching time found within reasonable future
    }

    /**
     * Check if the given time matches this cron expression.
     *
     * @param time the time to check
     * @return true if the time matches the cron expression
     */
    public boolean matches(ZonedDateTime time) {
        return seconds.contains(time.getSecond()) &&
               minutes.contains(time.getMinute()) &&
               hours.contains(time.getHour()) &&
               daysOfMonth.contains(time.getDayOfMonth()) &&
               months.contains(time.getMonthValue()) &&
               daysOfWeek.contains(time.getDayOfWeek().getValue() % 7); // Convert to 0-6
    }

    /**
     * Calculate time until next execution from now.
     *
     * @return duration until next execution
     */
    public Duration timeUntilNextExecution() {
        return timeUntilNextExecution(ZonedDateTime.now());
    }

    /**
     * Calculate time until next execution from a given time.
     *
     * @param from the reference time
     * @return duration until next execution, or null if no future execution
     */
    public Duration timeUntilNextExecution(ZonedDateTime from) {
        ZonedDateTime next = getNextExecution(from);
        if (next == null) {
            return null;
        }
        return Duration.between(from, next);
    }

    /**
     * Get the original cron expression string.
     *
     * @return the cron expression
     */
    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return "CronExpression{" + expression + "}";
    }

    // ========== PRIVATE HELPER METHODS ==========

    private Set<Integer> parseField(String field, int min, int max) {
        return parseField(field, min, max, null);
    }

    private Set<Integer> parseField(String field, int min, int max, Map<String, Integer> aliases) {
        Set<Integer> values = new HashSet<>();

        // Replace aliases if provided
        if (aliases != null) {
            for (Map.Entry<String, Integer> entry : aliases.entrySet()) {
                field = field.replaceAll("(?i)" + entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        // Handle wildcard
        if (field.equals("*")) {
            for (int i = min; i <= max; i++) {
                values.add(i);
            }
            return values;
        }

        // Handle step values (e.g., */5, 10-20/2)
        if (field.contains("/")) {
            return parseStepValues(field, min, max);
        }

        // Handle lists (e.g., 1,3,5)
        if (field.contains(",")) {
            String[] parts = field.split(",");
            for (String part : parts) {
                values.addAll(parseField(part.trim(), min, max, null));
            }
            return values;
        }

        // Handle ranges (e.g., 10-20)
        if (field.contains("-")) {
            return parseRange(field, min, max);
        }

        // Handle single value
        try {
            int value = Integer.parseInt(field);
            if (value < min || value > max) {
                throw new IllegalArgumentException(
                    "Value " + value + " out of range [" + min + "-" + max + "]"
                );
            }
            values.add(value);
            return values;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cron field: " + field, e);
        }
    }

    private Set<Integer> parseRange(String field, int min, int max) {
        Set<Integer> values = new HashSet<>();
        String[] parts = field.split("-");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid range: " + field);
        }

        try {
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());

            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException(
                    "Invalid range " + field + " for bounds [" + min + "-" + max + "]"
                );
            }

            for (int i = start; i <= end; i++) {
                values.add(i);
            }

            return values;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid range: " + field, e);
        }
    }

    private Set<Integer> parseStepValues(String field, int min, int max) {
        Set<Integer> values = new HashSet<>();
        String[] parts = field.split("/");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid step expression: " + field);
        }

        try {
            int step = Integer.parseInt(parts[1].trim());
            if (step <= 0) {
                throw new IllegalArgumentException("Step must be positive: " + step);
            }

            Set<Integer> baseValues;
            if (parts[0].equals("*")) {
                baseValues = new HashSet<>();
                for (int i = min; i <= max; i++) {
                    baseValues.add(i);
                }
            } else if (parts[0].contains("-")) {
                baseValues = parseRange(parts[0], min, max);
            } else {
                baseValues = Set.of(Integer.parseInt(parts[0].trim()));
            }

            // Apply step
            List<Integer> sorted = new ArrayList<>(baseValues);
            Collections.sort(sorted);

            for (int i = 0; i < sorted.size(); i += step) {
                values.add(sorted.get(i));
            }

            return values;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid step expression: " + field, e);
        }
    }
}
