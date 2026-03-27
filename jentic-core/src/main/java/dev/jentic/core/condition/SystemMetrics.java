package dev.jentic.core.condition;

import java.time.Instant;

/**
 * System metrics snapshot for condition evaluation
 * 
 * @param cpuUsage        system CPU usage as a percentage (0.0–100.0)
 * @param memoryUsage     JVM heap usage as a percentage of max heap (0.0–100.0)
 * @param availableMemory free JVM heap memory in bytes
 * @param activeThreads   number of currently active JVM threads
 * @param timestamp       instant at which this snapshot was captured
 * @since 0.2.0
 */
public record SystemMetrics(
    double cpuUsage,
    double memoryUsage,
    long availableMemory,
    int activeThreads,
    Instant timestamp
) {
    
    public static SystemMetrics current() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsage = (double) usedMemory / maxMemory * 100;
        
        return new SystemMetrics(
            getCpuUsage(),
            memoryUsage,
            freeMemory,
            Thread.activeCount(),
            Instant.now()
        );
    }
    
    private static double getCpuUsage() {
        // Simplified CPU usage - in production use OperatingSystemMXBean
        com.sun.management.OperatingSystemMXBean osBean = 
            (com.sun.management.OperatingSystemMXBean) 
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        double load = osBean.getCpuLoad();
        if (load < 0 || Double.isNaN(load)) {
            return 0.0; // Not yet available (returns 0 = "no load")
        }
        return load * 100;
    }
}