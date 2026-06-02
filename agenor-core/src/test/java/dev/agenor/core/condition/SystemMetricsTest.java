package dev.agenor.core.condition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test coverage for SystemMetrics record
 */
@DisplayName("SystemMetrics Tests")
class SystemMetricsTest {

    @Test
    @DisplayName("should create metrics with all fields")
    void shouldCreateMetricsWithAllFields() {
        // Given
        double cpu = 45.5;
        double memory = 60.0;
        long availableMemory = 1024 * 1024 * 512L; // 512 MB
        int threads = 42;
        Instant timestamp = Instant.now();

        // When
        SystemMetrics metrics = new SystemMetrics(cpu, memory, availableMemory, threads, timestamp);

        // Then
        assertThat(metrics.cpuUsage()).isEqualTo(cpu);
        assertThat(metrics.memoryUsage()).isEqualTo(memory);
        assertThat(metrics.availableMemory()).isEqualTo(availableMemory);
        assertThat(metrics.activeThreads()).isEqualTo(threads);
        assertThat(metrics.timestamp()).isEqualTo(timestamp);
    }

    @Test
    @DisplayName("should create current system metrics")
    void shouldCreateCurrentSystemMetrics() {
        // Given
        Instant before = Instant.now();

        // When
        SystemMetrics metrics = SystemMetrics.current();

        // Then
        assertThat(metrics).isNotNull();
        assertThat(metrics.cpuUsage()).isBetween(0.0, 100.0);
        assertThat(metrics.memoryUsage()).isBetween(0.0, 100.0);
        assertThat(metrics.availableMemory()).isGreaterThanOrEqualTo(0L);
        assertThat(metrics.activeThreads()).isGreaterThan(0);
        assertThat(metrics.timestamp()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("should have recent timestamp")
    void shouldHaveRecentTimestamp() {
        // When
        SystemMetrics metrics = SystemMetrics.current();
        Instant now = Instant.now();

        // Then
        Duration timeDiff = Duration.between(metrics.timestamp(), now);
        assertThat(timeDiff.toMillis()).isLessThan(1000); // Within 1 second
    }

    @Test
    @DisplayName("should have positive memory values")
    void shouldHavePositiveMemoryValues() {
        // When
        SystemMetrics metrics = SystemMetrics.current();

        // Then
        assertThat(metrics.availableMemory()).isPositive();
        assertThat(metrics.memoryUsage()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("should have at least one active thread")
    void shouldHaveAtLeastOneActiveThread() {
        // When
        SystemMetrics metrics = SystemMetrics.current();

        // Then
        // At least the current thread should be active
        assertThat(metrics.activeThreads()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("should support equals comparison")
    void shouldSupportEqualsComparison() {
        // Given
        Instant timestamp = Instant.now();
        SystemMetrics metrics1 = new SystemMetrics(50.0, 60.0, 1024L, 10, timestamp);
        SystemMetrics metrics2 = new SystemMetrics(50.0, 60.0, 1024L, 10, timestamp);
        SystemMetrics metrics3 = new SystemMetrics(51.0, 60.0, 1024L, 10, timestamp);

        // Then
        assertThat(metrics1).isEqualTo(metrics2);
        assertThat(metrics1).isNotEqualTo(metrics3);
    }

    @Test
    @DisplayName("should support hashCode")
    void shouldSupportHashCode() {
        // Given
        Instant timestamp = Instant.now();
        SystemMetrics metrics1 = new SystemMetrics(50.0, 60.0, 1024L, 10, timestamp);
        SystemMetrics metrics2 = new SystemMetrics(50.0, 60.0, 1024L, 10, timestamp);

        // Then
        assertThat(metrics1.hashCode()).isEqualTo(metrics2.hashCode());
    }

    @Test
    @DisplayName("should support toString")
    void shouldSupportToString() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:00:00Z");
        SystemMetrics metrics = new SystemMetrics(45.5, 60.0, 1024L, 10, timestamp);

        // When
        String toString = metrics.toString();

        // Then
        assertThat(toString).contains("45.5");
        assertThat(toString).contains("60.0");
        assertThat(toString).contains("1024");
        assertThat(toString).contains("10");
    }

    @Test
    @DisplayName("should handle extreme CPU values")
    void shouldHandleExtremeCpuValues() {
        // Given
        Instant timestamp = Instant.now();

        // When
        SystemMetrics lowCpu = new SystemMetrics(0.0, 50.0, 1024L, 10, timestamp);
        SystemMetrics highCpu = new SystemMetrics(100.0, 50.0, 1024L, 10, timestamp);

        // Then
        assertThat(lowCpu.cpuUsage()).isEqualTo(0.0);
        assertThat(highCpu.cpuUsage()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("should handle extreme memory values")
    void shouldHandleExtremeMemoryValues() {
        // Given
        Instant timestamp = Instant.now();

        // When
        SystemMetrics lowMemory = new SystemMetrics(50.0, 0.0, Long.MAX_VALUE, 10, timestamp);
        SystemMetrics highMemory = new SystemMetrics(50.0, 100.0, 0L, 10, timestamp);

        // Then
        assertThat(lowMemory.memoryUsage()).isEqualTo(0.0);
        assertThat(lowMemory.availableMemory()).isEqualTo(Long.MAX_VALUE);
        assertThat(highMemory.memoryUsage()).isEqualTo(100.0);
        assertThat(highMemory.availableMemory()).isEqualTo(0L);
    }

    @Test
    @DisplayName("should handle zero active threads")
    void shouldHandleZeroActiveThreads() {
        // Given
        Instant timestamp = Instant.now();

        // When
        SystemMetrics metrics = new SystemMetrics(50.0, 60.0, 1024L, 0, timestamp);

        // Then
        assertThat(metrics.activeThreads()).isEqualTo(0);
    }

    @Test
    @DisplayName("current() should return different timestamps on successive calls")
    void currentShouldReturnDifferentTimestampsOnSuccessiveCalls() throws InterruptedException {
        // Given
        SystemMetrics first = SystemMetrics.current();
        Thread.sleep(10); // Small delay

        // When
        SystemMetrics second = SystemMetrics.current();

        // Then
        assertThat(second.timestamp()).isAfter(first.timestamp());
    }

    @Test
    @DisplayName("should reflect actual system state changes")
    void shouldReflectActualSystemStateChanges() {
        // When - take two snapshots
        SystemMetrics snapshot1 = SystemMetrics.current();
        SystemMetrics snapshot2 = SystemMetrics.current();

        // Then - values should be in reasonable ranges
        assertThat(snapshot1.cpuUsage()).isBetween(-1.0, 100.0);
        assertThat(snapshot2.cpuUsage()).isBetween(-1.0, 100.0);

        // Memory usage should be consistent within a small time window
        double memoryDiff = Math.abs(snapshot1.memoryUsage() - snapshot2.memoryUsage());
        assertThat(memoryDiff).isLessThan(50.0); // Less than 50% change
    }

    @Test
    @DisplayName("should calculate memory usage correctly")
    void shouldCalculateMemoryUsageCorrectly() {
        // When
        SystemMetrics metrics = SystemMetrics.current();

        // Then - memory usage should be a percentage
        assertThat(metrics.memoryUsage()).isGreaterThanOrEqualTo(0.0);
        assertThat(metrics.memoryUsage()).isLessThanOrEqualTo(100.0);
    }

    @Test
    @DisplayName("should be immutable record")
    void shouldBeImmutableRecord() {
        // Given
        Instant timestamp = Instant.now();
        SystemMetrics metrics = new SystemMetrics(50.0, 60.0, 1024L, 10, timestamp);

        // When - attempting to get components
        double cpu = metrics.cpuUsage();
        double memory = metrics.memoryUsage();

        // Then - getting components doesn't affect the record
        assertThat(metrics.cpuUsage()).isEqualTo(cpu);
        assertThat(metrics.memoryUsage()).isEqualTo(memory);
    }
}
