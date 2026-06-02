package dev.agenor.runtime.behavior.advanced;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.*;

class CronExpressionTest {

    // ========== PARSING TESTS ==========

    @Test
    void parse_shouldAcceptValidSixFieldExpression() {
        CronExpression cron = CronExpression.parse("0 0 12 * * *");
        assertThat(cron).isNotNull();
        assertThat(cron.getExpression()).isEqualTo("0 0 12 * * *");
    }

    @Test
    void parse_shouldTrimWhitespace() {
        CronExpression cron = CronExpression.parse("  0 0 12 * * *  ");
        assertThat(cron).isNotNull();
    }

    @Test
    void parse_shouldThrowForNullExpression() {
        assertThatThrownBy(() -> CronExpression.parse(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null or empty");
    }

    @Test
    void parse_shouldThrowForEmptyExpression() {
        assertThatThrownBy(() -> CronExpression.parse(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null or empty");
    }

    @Test
    void parse_shouldThrowForBlankExpression() {
        assertThatThrownBy(() -> CronExpression.parse("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null or empty");
    }

    @Test
    void parse_shouldThrowForWrongFieldCount() {
        assertThatThrownBy(() -> CronExpression.parse("0 0 12 * *"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("6 fields");
    }

    @Test
    void parse_shouldThrowForTooManyFields() {
        assertThatThrownBy(() -> CronExpression.parse("0 0 12 * * * *"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("6 fields");
    }

    // ========== FIELD TYPES ==========

    @Test
    void parse_shouldHandleWildcardFields() {
        CronExpression cron = CronExpression.parse("* * * * * *");
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC")).withNano(0);
        assertThat(cron.matches(now)).isTrue();
    }

    @Test
    void parse_shouldHandleSpecificValues() {
        // Every day at exactly 12:30:00 on the 15th of June
        CronExpression cron = CronExpression.parse("0 30 12 15 6 *");
        ZonedDateTime matching = ZonedDateTime.of(2024, 6, 15, 12, 30, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime notMatching = ZonedDateTime.of(2024, 6, 15, 12, 31, 0, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(matching)).isTrue();
        assertThat(cron.matches(notMatching)).isFalse();
    }

    @Test
    void parse_shouldHandleRangeFields() {
        // Every second from 10 to 15
        CronExpression cron = CronExpression.parse("10-15 * * * * *");
        ZonedDateTime at10 = ZonedDateTime.of(2024, 1, 1, 0, 0, 10, 0, ZoneId.of("UTC"));
        ZonedDateTime at15 = ZonedDateTime.of(2024, 1, 1, 0, 0, 15, 0, ZoneId.of("UTC"));
        ZonedDateTime at9  = ZonedDateTime.of(2024, 1, 1, 0, 0, 9, 0, ZoneId.of("UTC"));
        ZonedDateTime at16 = ZonedDateTime.of(2024, 1, 1, 0, 0, 16, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(at10)).isTrue();
        assertThat(cron.matches(at15)).isTrue();
        assertThat(cron.matches(at9)).isFalse();
        assertThat(cron.matches(at16)).isFalse();
    }

    @Test
    void parse_shouldHandleListFields() {
        // At seconds 0, 15, 30, 45
        CronExpression cron = CronExpression.parse("0,15,30,45 * * * * *");
        ZonedDateTime at0  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime at15 = ZonedDateTime.of(2024, 1, 1, 0, 0, 15, 0, ZoneId.of("UTC"));
        ZonedDateTime at30 = ZonedDateTime.of(2024, 1, 1, 0, 0, 30, 0, ZoneId.of("UTC"));
        ZonedDateTime at45 = ZonedDateTime.of(2024, 1, 1, 0, 0, 45, 0, ZoneId.of("UTC"));
        ZonedDateTime at1  = ZonedDateTime.of(2024, 1, 1, 0, 0, 1, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(at0)).isTrue();
        assertThat(cron.matches(at15)).isTrue();
        assertThat(cron.matches(at30)).isTrue();
        assertThat(cron.matches(at45)).isTrue();
        assertThat(cron.matches(at1)).isFalse();
    }

    @Test
    void parse_shouldHandleStepWithWildcard() {
        // Every 10 seconds: 0,10,20,30,40,50
        CronExpression cron = CronExpression.parse("*/10 * * * * *");
        ZonedDateTime at0  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime at10 = ZonedDateTime.of(2024, 1, 1, 0, 0, 10, 0, ZoneId.of("UTC"));
        ZonedDateTime at20 = ZonedDateTime.of(2024, 1, 1, 0, 0, 20, 0, ZoneId.of("UTC"));
        ZonedDateTime at5  = ZonedDateTime.of(2024, 1, 1, 0, 0, 5, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(at0)).isTrue();
        assertThat(cron.matches(at10)).isTrue();
        assertThat(cron.matches(at20)).isTrue();
        assertThat(cron.matches(at5)).isFalse();
    }

    @Test
    void parse_shouldHandleStepWithRange() {
        // From second 10 to 30, every 5 seconds
        CronExpression cron = CronExpression.parse("10-30/5 * * * * *");
        ZonedDateTime at10 = ZonedDateTime.of(2024, 1, 1, 0, 0, 10, 0, ZoneId.of("UTC"));
        ZonedDateTime at15 = ZonedDateTime.of(2024, 1, 1, 0, 0, 15, 0, ZoneId.of("UTC"));
        ZonedDateTime at11 = ZonedDateTime.of(2024, 1, 1, 0, 0, 11, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(at10)).isTrue();
        assertThat(cron.matches(at15)).isTrue();
        assertThat(cron.matches(at11)).isFalse();
    }

    @Test
    void parse_shouldHandleMonthAliases() {
        // Every January 1st at 00:00:00
        CronExpression cron = CronExpression.parse("0 0 0 1 JAN *");
        ZonedDateTime jan1 = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime feb1 = ZonedDateTime.of(2024, 2, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(jan1)).isTrue();
        assertThat(cron.matches(feb1)).isFalse();
    }

    @Test
    void parse_shouldHandleDayOfWeekAliases() {
        // Every Sunday at 00:00:00
        CronExpression cron = CronExpression.parse("0 0 0 * * SUN");
        // January 7, 2024 is a Sunday
        ZonedDateTime sunday = ZonedDateTime.of(2024, 1, 7, 0, 0, 0, 0, ZoneId.of("UTC"));
        // January 8, 2024 is a Monday
        ZonedDateTime monday = ZonedDateTime.of(2024, 1, 8, 0, 0, 0, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(sunday)).isTrue();
        assertThat(cron.matches(monday)).isFalse();
    }

    // ========== VALIDATION ERRORS ==========

    @Test
    void parse_shouldThrowForOutOfRangeSecond() {
        assertThatThrownBy(() -> CronExpression.parse("60 * * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForOutOfRangeMinute() {
        assertThatThrownBy(() -> CronExpression.parse("0 60 * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForOutOfRangeHour() {
        assertThatThrownBy(() -> CronExpression.parse("0 0 24 * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForInvalidRangeStartGtEnd() {
        assertThatThrownBy(() -> CronExpression.parse("30-10 * * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForInvalidRangeFormat() {
        assertThatThrownBy(() -> CronExpression.parse("1-2-3 * * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForZeroStep() {
        assertThatThrownBy(() -> CronExpression.parse("*/0 * * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForNonNumericField() {
        assertThatThrownBy(() -> CronExpression.parse("abc * * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldThrowForInvalidStepFormat() {
        assertThatThrownBy(() -> CronExpression.parse("1/2/3 * * * * *"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== MATCHES TESTS ==========

    @Test
    void matches_shouldReturnTrueForMatchingTime() {
        CronExpression cron = CronExpression.parse("0 0 12 1 1 *");
        ZonedDateTime time = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(time)).isTrue();
    }

    @Test
    void matches_shouldReturnFalseForNonMatchingTime() {
        CronExpression cron = CronExpression.parse("0 0 12 1 1 *");
        ZonedDateTime time = ZonedDateTime.of(2024, 1, 1, 13, 0, 0, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(time)).isFalse();
    }

    // ========== getNextExecution TESTS ==========

    @Test
    void getNextExecution_shouldReturnTimeAfterGiven() {
        // Every second
        CronExpression cron = CronExpression.parse("* * * * * *");
        ZonedDateTime now = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime next = cron.getNextExecution(now);
        assertThat(next).isAfter(now);
    }

    @Test
    void getNextExecution_shouldReturnNextMatchingTime() {
        // At 12:00:00 every day
        CronExpression cron = CronExpression.parse("0 0 12 * * *");
        ZonedDateTime from = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime next = cron.getNextExecution(from);
        assertThat(next).isNotNull();
        ZonedDateTime expected = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("UTC"));
        assertThat(next).isEqualTo(expected);
    }

    @Test
    void getNextExecution_shouldSkipCurrentSecond() {
        // Every second
        CronExpression cron = CronExpression.parse("* * * * * *");
        ZonedDateTime now = ZonedDateTime.of(2024, 1, 1, 0, 0, 5, 0, ZoneId.of("UTC"));
        ZonedDateTime next = cron.getNextExecution(now);
        // Should be second 6, not second 5
        ZonedDateTime expected = ZonedDateTime.of(2024, 1, 1, 0, 0, 6, 0, ZoneId.of("UTC"));
        assertThat(next).isEqualTo(expected);
    }

    // ========== timeUntilNextExecution TESTS ==========

    @Test
    void timeUntilNextExecution_shouldReturnPositiveDuration() {
        // At 12:00:00 every day
        CronExpression cron = CronExpression.parse("0 0 12 * * *");
        ZonedDateTime from = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC"));
        Duration duration = cron.timeUntilNextExecution(from);
        assertThat(duration).isNotNull();
        assertThat(duration.isNegative()).isFalse();
        assertThat(duration).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void timeUntilNextExecution_shouldNotThrow() {
        // Every second, so next execution is always within a second
        CronExpression cron = CronExpression.parse("* * * * * *");
        Duration duration = cron.timeUntilNextExecution();
        assertThat(duration).isNotNull();
    }

    // ========== toString TESTS ==========

    @Test
    void toString_shouldContainExpression() {
        CronExpression cron = CronExpression.parse("0 0 12 * * *");
        assertThat(cron.toString()).contains("0 0 12 * * *");
        assertThat(cron.toString()).contains("CronExpression");
    }

    // ========== MONTH ALIASES TESTS ==========

    @ParameterizedTest
    @ValueSource(strings = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"})
    void parse_shouldHandleAllMonthAliases(String month) {
        assertThatCode(() -> CronExpression.parse("0 0 0 1 " + month + " *"))
            .doesNotThrowAnyException();
    }

    // ========== DAY OF WEEK TESTS ==========

    @ParameterizedTest
    @ValueSource(strings = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"})
    void parse_shouldHandleAllDayOfWeekAliases(String dow) {
        assertThatCode(() -> CronExpression.parse("0 0 0 * * " + dow))
            .doesNotThrowAnyException();
    }

    // ========== STEP WITH SINGLE BASE VALUE ==========

    @Test
    void parse_shouldHandleStepWithSingleBaseValue() {
        // From second 5, step 1 (only second 5)
        CronExpression cron = CronExpression.parse("5/1 * * * * *");
        ZonedDateTime at5 = ZonedDateTime.of(2024, 1, 1, 0, 0, 5, 0, ZoneId.of("UTC"));
        assertThat(cron.matches(at5)).isTrue();
    }
}
