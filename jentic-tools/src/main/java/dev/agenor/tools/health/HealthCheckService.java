package dev.agenor.tools.health;

import dev.agenor.runtime.JenticRuntime;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Health check service for monitoring runtime health.
 *
 * <p>Provides comprehensive health checks including:
 * <ul>
 *   <li>Runtime status (up/down)</li>
 *   <li>Agent health checks</li>
 *   <li>Memory usage</li>
 *   <li>Thread health</li>
 *   <li>Custom health indicators</li>
 * </ul>
 */
public class HealthCheckService {

    private final JenticRuntime runtime;
    private final Map<String, HealthIndicator> indicators = new ConcurrentHashMap<>();
    private final Instant startTime;

    public HealthCheckService(JenticRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "Runtime cannot be null");
        this.startTime = Instant.now();
        registerDefaultIndicators();
    }

    private void registerDefaultIndicators() {
        registerIndicator("runtime", this::checkRuntime);
        registerIndicator("memory", this::checkMemory);
        registerIndicator("agents", this::checkAgents);
        registerIndicator("threads", this::checkThreads);
    }

    public void registerIndicator(String name, HealthIndicator indicator) {
        indicators.put(name, indicator);
    }

    public void unregisterIndicator(String name) {
        indicators.remove(name);
    }

    /**
     * Perform all health checks.
     */
    public HealthReport check() {
        Map<String, HealthStatus> results = new LinkedHashMap<>();
        HealthStatus overall = HealthStatus.up();

        for (Map.Entry<String, HealthIndicator> entry : indicators.entrySet()) {
            try {
                HealthStatus status = entry.getValue().check();
                results.put(entry.getKey(), status);

                if (status.isDown()) {
                    overall = HealthStatus.down(null);
                } else if (status.isDegraded() && !overall.isDown()) {
                    overall = HealthStatus.degraded(null);
                }
            } catch (Exception e) {
                results.put(entry.getKey(), HealthStatus.down("Check failed: " + e.getMessage()));
                overall = HealthStatus.down(null);
            }
        }

        return new HealthReport(overall, results, Duration.between(startTime, Instant.now()));
    }

    /**
     * Check specific indicator.
     */
    public HealthStatus check(String name) {
        HealthIndicator indicator = indicators.get(name);
        if (indicator == null) {
            return HealthStatus.unknown("Indicator not found: " + name);
        }
        try {
            return indicator.check();
        } catch (Exception e) {
            return HealthStatus.down("Check failed: " + e.getMessage());
        }
    }

    // === Default Health Checks ===

    private HealthStatus checkRuntime() {
        if (!runtime.isRunning()) {
            return HealthStatus.down("Runtime is not running");
        }
        return HealthStatus.up()
                .withDetail("running", true)
                .withDetail("agents", runtime.getAgents().size());
    }

    private HealthStatus checkMemory() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long used = memory.getHeapMemoryUsage().getUsed();
        long max = memory.getHeapMemoryUsage().getMax();
        double usagePercent = max > 0 ? (double) used / max * 100 : 0;

        HealthStatus status;
        if (usagePercent > 90) {
            status = HealthStatus.down("Memory critical: " + (int) usagePercent + "%");
        } else if (usagePercent > 75) {
            status = HealthStatus.degraded("Memory high: " + (int) usagePercent + "%");
        } else {
            status = HealthStatus.up();
        }

        return status
                .withDetail("used", used)
                .withDetail("max", max)
                .withDetail("usagePercent", usagePercent);
    }

    private HealthStatus checkAgents() {
        var agents = runtime.getAgents();
        long running = agents.stream().filter(a -> a.isRunning()).count();
        long total = agents.size();

        if (total == 0) {
            return HealthStatus.up()
                    .withDetail("message", "No agents registered")
                    .withDetail("total", 0);
        }

        if (running == 0) {
            return HealthStatus.degraded("No agents running")
                    .withDetail("running", 0)
                    .withDetail("total", total);
        }

        return HealthStatus.up()
                .withDetail("running", running)
                .withDetail("total", total);
    }

    private HealthStatus checkThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        int count = threads.getThreadCount();
        int peak = threads.getPeakThreadCount();
        long[] deadlocked = threads.findDeadlockedThreads();

        if (deadlocked != null && deadlocked.length > 0) {
            return HealthStatus.down("Deadlock detected")
                    .withDetail("deadlockedThreads", deadlocked.length);
        }

        return HealthStatus.up()
                .withDetail("count", count)
                .withDetail("peak", peak);
    }

    // === Inner Classes ===

    @FunctionalInterface
    public interface HealthIndicator {
        HealthStatus check();
    }

    public record HealthReport(
            HealthStatus status,
            Map<String, HealthStatus> components,
            Duration uptime
    ) {
        public boolean isHealthy() {
            return status.isUp();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status.state().name());
            map.put("uptime", uptime.toSeconds() + "s");

            Map<String, Object> componentMap = new LinkedHashMap<>();
            for (var entry : components.entrySet()) {
                Map<String, Object> comp = new LinkedHashMap<>();
                comp.put("status", entry.getValue().state().name());
                if (entry.getValue().message() != null) {
                    comp.put("message", entry.getValue().message());
                }
                comp.putAll(entry.getValue().details());
                componentMap.put(entry.getKey(), comp);
            }
            map.put("components", componentMap);

            return map;
        }
    }

    public record HealthStatus(
            State state,
            String message,
            Map<String, Object> details
    ) {
        public enum State { UP, DOWN, DEGRADED, UNKNOWN }

        public static HealthStatus up() {
            return new HealthStatus(State.UP, null, new LinkedHashMap<>());
        }

        public static HealthStatus down(String message) {
            return new HealthStatus(State.DOWN, message, new LinkedHashMap<>());
        }

        public static HealthStatus degraded(String message) {
            return new HealthStatus(State.DEGRADED, message, new LinkedHashMap<>());
        }

        public static HealthStatus unknown(String message) {
            return new HealthStatus(State.UNKNOWN, message, new LinkedHashMap<>());
        }

        public HealthStatus withDetail(String key, Object value) {
            Map<String, Object> newDetails = new LinkedHashMap<>(details);
            newDetails.put(key, value);
            return new HealthStatus(state, message, newDetails);
        }

        public boolean isUp() { return state == State.UP; }
        public boolean isDown() { return state == State.DOWN; }
        public boolean isDegraded() { return state == State.DEGRADED; }
    }
}
