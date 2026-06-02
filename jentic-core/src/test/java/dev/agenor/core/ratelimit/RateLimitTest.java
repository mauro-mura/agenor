package dev.agenor.core.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for RateLimit record
 */
class RateLimitTest {

    @Test
    void shouldCreateRateLimitWithAllFields() {
        // Given
        int maxRequests = 100;
        Duration period = Duration.ofMinutes(1);
        int burstCapacity = 150;

        // When
        RateLimit rateLimit = new RateLimit(maxRequests, period, burstCapacity);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(maxRequests);
        assertThat(rateLimit.period()).isEqualTo(period);
        assertThat(rateLimit.burstCapacity()).isEqualTo(burstCapacity);
    }

    @Test
    void shouldCreatePerSecondRateLimit() {
        // When
        RateLimit rateLimit = RateLimit.perSecond(10);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(10);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofSeconds(1));
        assertThat(rateLimit.burstCapacity()).isEqualTo(10);
    }

    @Test
    void shouldCreatePerMinuteRateLimit() {
        // When
        RateLimit rateLimit = RateLimit.perMinute(60);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(60);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofMinutes(1));
        assertThat(rateLimit.burstCapacity()).isEqualTo(60);
    }

    @Test
    void shouldCreatePerHourRateLimit() {
        // When
        RateLimit rateLimit = RateLimit.perHour(1000);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(1000);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofHours(1));
        assertThat(rateLimit.burstCapacity()).isEqualTo(1000);
    }

    @Test
    void shouldCreateWithCustomBurstCapacity() {
        // Given
        RateLimit baseLimit = RateLimit.perSecond(10);

        // When
        RateLimit burstLimit = baseLimit.withBurst(20);

        // Then
        assertThat(burstLimit.maxRequests()).isEqualTo(10);
        assertThat(burstLimit.period()).isEqualTo(Duration.ofSeconds(1));
        assertThat(burstLimit.burstCapacity()).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10/s", "10/sec", "10/second"})
    void shouldParsePerSecondFormats(String spec) {
        // When
        RateLimit rateLimit = RateLimit.parse(spec);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(10);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100/m", "100/min", "100/minute"})
    void shouldParsePerMinuteFormats(String spec) {
        // When
        RateLimit rateLimit = RateLimit.parse(spec);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(100);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofMinutes(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000/h", "1000/hour"})
    void shouldParsePerHourFormats(String spec) {
        // When
        RateLimit rateLimit = RateLimit.parse(spec);

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(1000);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void shouldParsePerDayFormat() {
        // When
        RateLimit rateLimit = RateLimit.parse("10000/d");

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(10000);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void shouldParseWithWhitespace() {
        // When
        RateLimit rateLimit = RateLimit.parse("  50 / s  ");

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(50);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void shouldParseCaseInsensitive() {
        // When
        RateLimit rateLimit = RateLimit.parse("100/S");

        // Then
        assertThat(rateLimit.maxRequests()).isEqualTo(100);
        assertThat(rateLimit.period()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void shouldThrowExceptionForNullSpec() {
        // When/Then
        assertThatThrownBy(() -> RateLimit.parse(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be empty");
    }

    @Test
    void shouldThrowExceptionForEmptySpec() {
        // When/Then
        assertThatThrownBy(() -> RateLimit.parse(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be empty");
    }

    @Test
    void shouldThrowExceptionForMissingSlash() {
        // When/Then
        assertThatThrownBy(() -> RateLimit.parse("100s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid rate limit format");
    }

    @Test
    void shouldThrowExceptionForInvalidNumber() {
        // When/Then
        assertThatThrownBy(() -> RateLimit.parse("abc/s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid number");
    }

    @Test
    void shouldThrowExceptionForUnknownUnit() {
        // When/Then
        assertThatThrownBy(() -> RateLimit.parse("10/x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown time unit");
    }

    @Test
    void shouldSetBurstCapacityToMaxRequestsByDefault() {
        // When
        RateLimit rateLimit = RateLimit.parse("50/s");

        // Then
        assertThat(rateLimit.burstCapacity()).isEqualTo(rateLimit.maxRequests());
    }
}
