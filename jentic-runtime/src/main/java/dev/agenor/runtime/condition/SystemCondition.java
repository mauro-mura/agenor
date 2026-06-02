package dev.agenor.runtime.condition;

import dev.agenor.core.condition.Condition;
import dev.agenor.core.condition.SystemMetrics;

/**
 * Pre-built conditions for common system metrics checks
 */
public class SystemCondition {

    /**
     * Check if CPU usage is below a threshold
     */
    public static Condition cpuBelow(double threshold) {
        return agent -> {
            SystemMetrics metrics = SystemMetrics.current();
            return metrics.cpuUsage() <= threshold;
        };
    }

    /**
     * Check if CPU usage is above a threshold
     */
    public static Condition cpuAbove(double threshold) {
        return agent -> {
            SystemMetrics metrics = SystemMetrics.current();
            return metrics.cpuUsage() > threshold;
        };
    }

    /**
     * Check if memory usage is below a threshold (percentage)
     */
    public static Condition memoryBelow(double threshold) {
        return agent -> {
            SystemMetrics metrics = SystemMetrics.current();
            return metrics.memoryUsage() <= threshold;
        };
    }

    /**
     * Check if memory usage is above a threshold (percentage)
     */
    public static Condition memoryAbove(double threshold) {
        return agent -> {
            SystemMetrics metrics = SystemMetrics.current();
            return metrics.memoryUsage() > threshold;
        };
    }

    /**
     * Check if available memory is above a threshold (bytes)
     */
    public static Condition availableMemoryAbove(long bytes) {
        return agent -> {
            SystemMetrics metrics = SystemMetrics.current();
            return metrics.availableMemory() > bytes;
        };
    }

    /**
     * Check if active threads are below a threshold
     */
    public static Condition threadsBelow(int threshold) {
        return agent -> {
            SystemMetrics metrics = SystemMetrics.current();
            return metrics.activeThreads() < threshold;
        };
    }

    /**
     * System is healthy (CPU {@literal <} 80%, Memory {@literal <} 80%)
     */
    public static Condition systemHealthy() {
        return cpuBelow(80.0).and(memoryBelow(80.0));
    }

    /**
     * System is under load (CPU {@literal >} 70% OR Memory {@literal >} 70%)
     */
    public static Condition systemUnderLoad() {
        return cpuAbove(70.0).or(memoryAbove(70.0));
    }
}
