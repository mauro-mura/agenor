package dev.agenor.tools.metrics;

import dev.agenor.runtime.AgenorRuntime;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Metrics collection service for Jentic runtime.
 *
 * <p>Collects and aggregates metrics including:
 * <ul>
 *   <li>Agent metrics (count, status, behaviors)</li>
 *   <li>Message metrics (sent, received, throughput)</li>
 *   <li>JVM metrics (memory, threads, GC)</li>
 *   <li>Custom application metrics</li>
 * </ul>
 */
public class MetricsService {

    private final AgenorRuntime runtime;
    private final Instant startTime;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();

    public MetricsService(AgenorRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "Runtime cannot be null");
        this.startTime = Instant.now();
        registerDefaultGauges();
    }

    private void registerDefaultGauges() {
        // Agent gauges
        registerGauge("agents.total", () -> runtime.getAgents().size());
        registerGauge("agents.running", () ->
                runtime.getAgents().stream().filter(a -> a.isRunning()).count());

        // JVM gauges
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        registerGauge("jvm.memory.used", () -> memory.getHeapMemoryUsage().getUsed());
        registerGauge("jvm.memory.max", () -> memory.getHeapMemoryUsage().getMax());
        registerGauge("jvm.memory.committed", () -> memory.getHeapMemoryUsage().getCommitted());

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        registerGauge("jvm.threads.count", threads::getThreadCount);
        registerGauge("jvm.threads.peak", threads::getPeakThreadCount);
        registerGauge("jvm.threads.daemon", threads::getDaemonThreadCount);
    }

    // === Counter Operations ===

    public Counter counter(String name) {
        return counters.computeIfAbsent(name, k -> new Counter());
    }

    public void increment(String name) {
        counter(name).increment();
    }

    public void increment(String name, long delta) {
        counter(name).increment(delta);
    }

    // === Gauge Operations ===

    public void registerGauge(String name, Gauge gauge) {
        gauges.put(name, gauge);
    }

    // === Timer Operations ===

    public Timer timer(String name) {
        return timers.computeIfAbsent(name, k -> new Timer());
    }

    public Timer.Context startTimer(String name) {
        return timer(name).start();
    }

    // === Histogram Operations ===

    public Histogram histogram(String name) {
        return histograms.computeIfAbsent(name, k -> new Histogram());
    }

    public void record(String name, long value) {
        histogram(name).record(value);
    }

    // === Metrics Snapshot ===

    public MetricsSnapshot snapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // Runtime info
        metrics.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        metrics.put("runtimeRunning", runtime.isRunning());

        // Counters
        Map<String, Long> counterValues = new LinkedHashMap<>();
        counters.forEach((name, counter) -> counterValues.put(name, counter.get()));
        if (!counterValues.isEmpty()) {
            metrics.put("counters", counterValues);
        }

        // Gauges
        Map<String, Object> gaugeValues = new LinkedHashMap<>();
        gauges.forEach((name, gauge) -> {
            try {
                gaugeValues.put(name, gauge.getValue());
            } catch (Exception e) {
                gaugeValues.put(name, "error: " + e.getMessage());
            }
        });
        metrics.put("gauges", gaugeValues);

        // Timers
        Map<String, Map<String, Object>> timerValues = new LinkedHashMap<>();
        timers.forEach((name, timer) -> timerValues.put(name, timer.toMap()));
        if (!timerValues.isEmpty()) {
            metrics.put("timers", timerValues);
        }

        // Histograms
        Map<String, Map<String, Object>> histogramValues = new LinkedHashMap<>();
        histograms.forEach((name, hist) -> histogramValues.put(name, hist.toMap()));
        if (!histogramValues.isEmpty()) {
            metrics.put("histograms", histogramValues);
        }

        // GC stats
        metrics.put("gc", getGCStats());

        return new MetricsSnapshot(metrics, Instant.now());
    }

    private Map<String, Object> getGCStats() {
        Map<String, Object> gcStats = new LinkedHashMap<>();
        long totalCount = 0;
        long totalTime = 0;

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count >= 0) {
                totalCount += count;
                totalTime += time;
                gcStats.put(gc.getName() + ".count", count);
                gcStats.put(gc.getName() + ".time", time);
            }
        }

        gcStats.put("total.count", totalCount);
        gcStats.put("total.time", totalTime);
        return gcStats;
    }

    // === Inner Classes ===

    @FunctionalInterface
    public interface Gauge {
        Object getValue();
    }

    public static class Counter {
        private final AtomicLong value = new AtomicLong(0);

        public void increment() {
            value.incrementAndGet();
        }

        public void increment(long delta) {
            value.addAndGet(delta);
        }

        public long get() {
            return value.get();
        }

        public void reset() {
            value.set(0);
        }
    }

    public static class Timer {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(0);

        public Context start() {
            return new Context(this, System.nanoTime());
        }

        void record(long nanos) {
            count.incrementAndGet();
            totalTime.addAndGet(nanos);
            updateMin(nanos);
            updateMax(nanos);
        }

        private void updateMin(long value) {
            long current;
            do {
                current = min.get();
                if (value >= current) return;
            } while (!min.compareAndSet(current, value));
        }

        private void updateMax(long value) {
            long current;
            do {
                current = max.get();
                if (value <= current) return;
            } while (!max.compareAndSet(current, value));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            long c = count.get();
            map.put("count", c);
            map.put("totalMs", totalTime.get() / 1_000_000);
            map.put("meanMs", c > 0 ? totalTime.get() / c / 1_000_000.0 : 0);
            map.put("minMs", min.get() == Long.MAX_VALUE ? 0 : min.get() / 1_000_000.0);
            map.put("maxMs", max.get() / 1_000_000.0);
            return map;
        }

        public record Context(Timer timer, long startNanos) implements AutoCloseable {
            public long stop() {
                long elapsed = System.nanoTime() - startNanos;
                timer.record(elapsed);
                return elapsed;
            }

            @Override
            public void close() {
                stop();
            }
        }
    }

    public static class Histogram {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong sum = new AtomicLong(0);
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(0);

        public void record(long value) {
            count.incrementAndGet();
            sum.addAndGet(value);
            updateMin(value);
            updateMax(value);
        }

        private void updateMin(long value) {
            long current;
            do {
                current = min.get();
                if (value >= current) return;
            } while (!min.compareAndSet(current, value));
        }

        private void updateMax(long value) {
            long current;
            do {
                current = max.get();
                if (value <= current) return;
            } while (!max.compareAndSet(current, value));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            long c = count.get();
            map.put("count", c);
            map.put("sum", sum.get());
            map.put("mean", c > 0 ? (double) sum.get() / c : 0);
            map.put("min", min.get() == Long.MAX_VALUE ? 0 : min.get());
            map.put("max", max.get());
            return map;
        }
    }

    public record MetricsSnapshot(
            Map<String, Object> metrics,
            Instant timestamp
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>(metrics);
            map.put("timestamp", timestamp.toString());
            return map;
        }
    }
}
