package dev.agenor.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MemoryStats Tests")
class MemoryStatsTest {

    @Test
    @DisplayName("Should create stats with builder")
    void testBuilderCreation() {
        Instant now = Instant.now();

        MemoryStats stats = MemoryStats.builder()
            .shortTermCount(10)
            .longTermCount(20)
            .estimatedTokens(5000)
            .lastUpdated(now)
            .estimatedSizeBytes(1024 * 1024)
            .build();

        assertThat(stats.shortTermCount()).isEqualTo(10);
        assertThat(stats.longTermCount()).isEqualTo(20);
        assertThat(stats.estimatedTokens()).isEqualTo(5000);
        assertThat(stats.lastUpdated()).isEqualTo(now);
        assertThat(stats.estimatedSizeBytes()).isEqualTo(1024 * 1024);
    }

    @Test
    @DisplayName("Should create empty stats")
    void testEmptyStats() {
        MemoryStats stats = MemoryStats.empty();

        assertThat(stats.shortTermCount()).isZero();
        assertThat(stats.longTermCount()).isZero();
        assertThat(stats.estimatedTokens()).isZero();
        assertThat(stats.estimatedSizeBytes()).isZero();
        assertThat(stats.lastUpdated()).isNotNull();
        assertThat(stats.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Should calculate total count")
    void testTotalCount() {
        MemoryStats stats = MemoryStats.builder()
            .shortTermCount(15)
            .longTermCount(25)
            .build();

        assertThat(stats.totalCount()).isEqualTo(40);
    }

    @Test
    @DisplayName("Should check if empty")
    void testIsEmpty() {
        MemoryStats empty = MemoryStats.builder().build();
        assertThat(empty.isEmpty()).isTrue();

        MemoryStats notEmpty = MemoryStats.builder()
            .shortTermCount(1)
            .build();
        assertThat(notEmpty.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("Should calculate age")
    void testAge() throws InterruptedException {
        Instant past = Instant.now().minus(Duration.ofSeconds(2));

        MemoryStats stats = MemoryStats.builder()
            .lastUpdated(past)
            .build();

        Duration age = stats.age();
        assertThat(age.getSeconds()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Should check if stale")
    void testIsStale() {
        Instant old = Instant.now().minus(Duration.ofMinutes(5));

        MemoryStats stats = MemoryStats.builder()
            .lastUpdated(old)
            .build();

        assertThat(stats.isStale(Duration.ofMinutes(1))).isTrue();
        assertThat(stats.isStale(Duration.ofMinutes(10))).isFalse();
    }

    @Test
    @DisplayName("Should calculate size in KB")
    void testEstimatedSizeKB() {
        MemoryStats stats = MemoryStats.builder()
            .estimatedSizeBytes(2048)
            .build();

        assertThat(stats.estimatedSizeKB()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should calculate size in MB")
    void testEstimatedSizeMB() {
        MemoryStats stats = MemoryStats.builder()
            .estimatedSizeBytes(2 * 1024 * 1024)
            .build();

        assertThat(stats.estimatedSizeMB()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should reject negative counts")
    void testNegativeCounts() {
        assertThatThrownBy(() ->
            MemoryStats.builder()
                .shortTermCount(-1)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Short-term count cannot be negative");

        assertThatThrownBy(() ->
            MemoryStats.builder()
                .longTermCount(-1)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Long-term count cannot be negative");
    }

    @Test
    @DisplayName("Should reject negative tokens")
    void testNegativeTokens() {
        assertThatThrownBy(() ->
            MemoryStats.builder()
                .estimatedTokens(-1)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Estimated tokens cannot be negative");
    }

    @Test
    @DisplayName("Should reject negative size")
    void testNegativeSize() {
        assertThatThrownBy(() ->
            MemoryStats.builder()
                .estimatedSizeBytes(-1)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Estimated size cannot be negative");
    }

    @Test
    @DisplayName("Should use current time if lastUpdated is null")
    void testDefaultTimestamp() {
        Instant before = Instant.now();

        MemoryStats stats = MemoryStats.builder().build();

        Instant after = Instant.now();

        assertThat(stats.lastUpdated()).isBetween(before, after);
    }

    @Test
    @DisplayName("Should support direct constructor")
    void testDirectConstructor() {
        Instant now = Instant.now();

        MemoryStats stats = new MemoryStats(5, 10, 1000, now, 2048);

        assertThat(stats.shortTermCount()).isEqualTo(5);
        assertThat(stats.longTermCount()).isEqualTo(10);
        assertThat(stats.estimatedTokens()).isEqualTo(1000);
        assertThat(stats.lastUpdated()).isEqualTo(now);
        assertThat(stats.estimatedSizeBytes()).isEqualTo(2048);
    }
}
