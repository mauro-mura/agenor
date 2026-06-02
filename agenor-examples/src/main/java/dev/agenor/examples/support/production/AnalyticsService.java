package dev.agenor.examples.support.production;

import dev.agenor.examples.support.model.SupportIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Analytics service for tracking chatbot metrics.
 * Provides real-time and historical statistics.
 */
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    // Counters
    private final LongAdder totalQueries = new LongAdder();
    private final LongAdder totalEscalations = new LongAdder();
    private final LongAdder totalSatisfactionResponses = new LongAdder();
    private final LongAdder satisfactionSum = new LongAdder();

    // Response time tracking
    private final List<Long> responseTimesMs = Collections.synchronizedList(new ArrayList<>());
    private final int maxResponseTimeSamples = 10000;

    // Per-intent metrics
    private final Map<SupportIntent, IntentMetrics> intentMetrics = new ConcurrentHashMap<>();

    // Hourly buckets for time-series
    private final Map<String, HourlyBucket> hourlyBuckets = new ConcurrentHashMap<>();

    // Session tracking
    private final Map<String, SessionMetrics> sessionMetrics = new ConcurrentHashMap<>();

    private final Instant startTime = Instant.now();

    public AnalyticsService() {
        for (SupportIntent intent : SupportIntent.values()) {
            intentMetrics.put(intent, new IntentMetrics(intent));
        }
        log.info("Analytics service initialized");
    }

    /**
     * Records a query and its response.
     */
    public void recordQuery(String sessionId, SupportIntent intent, long responseTimeMs, double confidence) {
        totalQueries.increment();

        // Response time
        if (responseTimesMs.size() < maxResponseTimeSamples) {
            responseTimesMs.add(responseTimeMs);
        }

        // Intent metrics
        IntentMetrics metrics = intentMetrics.get(intent);
        if (metrics != null) {
            metrics.recordQuery(responseTimeMs, confidence);
        }

        // Hourly bucket
        String hourKey = getHourKey(Instant.now());
        hourlyBuckets.computeIfAbsent(hourKey, k -> new HourlyBucket(hourKey))
            .recordQuery(intent, responseTimeMs);

        // Session metrics
        sessionMetrics.computeIfAbsent(sessionId, k -> new SessionMetrics(sessionId))
            .recordQuery(intent, responseTimeMs);
    }

    /**
     * Records an escalation event.
     */
    public void recordEscalation(String sessionId, String reason) {
        totalEscalations.increment();

        SessionMetrics session = sessionMetrics.get(sessionId);
        if (session != null) {
            session.setEscalated(true);
        }

        String hourKey = getHourKey(Instant.now());
        hourlyBuckets.computeIfAbsent(hourKey, k -> new HourlyBucket(hourKey))
            .recordEscalation();
    }

    /**
     * Records satisfaction rating (1-5).
     */
    public void recordSatisfaction(String sessionId, int rating) {
        if (rating >= 1 && rating <= 5) {
            totalSatisfactionResponses.increment();
            satisfactionSum.add(rating);

            SessionMetrics session = sessionMetrics.get(sessionId);
            if (session != null) {
                session.setSatisfactionRating(rating);
            }
        }
    }

    // ========== AGGREGATE METRICS ==========

    /**
     * Gets overall statistics.
     */
    public OverallStats getOverallStats() {
        long queries = totalQueries.sum();
        long escalations = totalEscalations.sum();
        long satisfactionResponses = totalSatisfactionResponses.sum();

        double avgSatisfaction = satisfactionResponses > 0
            ? (double) satisfactionSum.sum() / satisfactionResponses
            : 0.0;

        double escalationRate = queries > 0
            ? (double) escalations / queries * 100
            : 0.0;

        double avgResponseTime = responseTimesMs.isEmpty()
            ? 0.0
            : responseTimesMs.stream().mapToLong(Long::longValue).average().orElse(0);

        long p95ResponseTime = calculatePercentile(95);
        long p99ResponseTime = calculatePercentile(99);

        Duration uptime = Duration.between(startTime, Instant.now());

        return new OverallStats(
            queries,
            escalations,
            escalationRate,
            avgSatisfaction,
            satisfactionResponses,
            avgResponseTime,
            p95ResponseTime,
            p99ResponseTime,
            uptime,
            sessionMetrics.size()
        );
    }

    /**
     * Gets per-intent breakdown.
     */
    public Map<SupportIntent, IntentStats> getIntentStats() {
        return intentMetrics.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().getStats()
            ));
    }

    /**
     * Gets hourly statistics for the last N hours.
     */
    public List<HourlyStats> getHourlyStats(int hours) {
        Instant now = Instant.now();
        List<HourlyStats> stats = new ArrayList<>();

        for (int i = hours - 1; i >= 0; i--) {
            Instant hour = now.minus(Duration.ofHours(i));
            String hourKey = getHourKey(hour);
            HourlyBucket bucket = hourlyBuckets.get(hourKey);

            if (bucket != null) {
                stats.add(bucket.getStats());
            } else {
                stats.add(new HourlyStats(hourKey, 0, 0, 0, Map.of()));
            }
        }

        return stats;
    }

    /**
     * Gets top intents by query count.
     */
    public List<Map.Entry<SupportIntent, Long>> getTopIntents(int limit) {
        return intentMetrics.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().getQueryCount()))
            .sorted(Map.Entry.<SupportIntent, Long>comparingByValue().reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Generates a text report of current metrics.
     */
    public String generateReport() {
        OverallStats overall = getOverallStats();
        StringBuilder sb = new StringBuilder();

        sb.append("📊 **Support Chatbot Analytics Report**\n\n");

        sb.append("**Overall Metrics**\n");
        sb.append(String.format("• Total Queries: %,d\n", overall.totalQueries()));
        sb.append(String.format("• Total Sessions: %,d\n", overall.totalSessions()));
        sb.append(String.format("• Uptime: %s\n", formatDuration(overall.uptime())));
        sb.append("\n");

        sb.append("**Response Performance**\n");
        sb.append(String.format("• Avg Response Time: %.0f ms\n", overall.avgResponseTimeMs()));
        sb.append(String.format("• P95 Response Time: %d ms\n", overall.p95ResponseTimeMs()));
        sb.append(String.format("• P99 Response Time: %d ms\n", overall.p99ResponseTimeMs()));
        sb.append("\n");

        sb.append("**Quality Metrics**\n");
        sb.append(String.format("• Escalation Rate: %.1f%%\n", overall.escalationRate()));
        sb.append(String.format("• Avg Satisfaction: %.1f/5 (%d responses)\n",
            overall.avgSatisfaction(), overall.satisfactionResponses()));
        sb.append("\n");

        sb.append("**Top Intents**\n");
        getTopIntents(5).forEach(e ->
            sb.append(String.format("• %s: %,d queries\n", e.getKey().name(), e.getValue())));

        return sb.toString();
    }

    // ========== HELPER METHODS ==========

    private String getHourKey(Instant instant) {
        return instant.toString().substring(0, 13); // YYYY-MM-DDTHH
    }

    private long calculatePercentile(int percentile) {
        if (responseTimesMs.isEmpty()) return 0;

        List<Long> sorted = new ArrayList<>(responseTimesMs);
        Collections.sort(sorted);

        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();

        if (hours > 24) {
            long days = hours / 24;
            hours = hours % 24;
            return String.format("%dd %dh %dm", days, hours, minutes);
        }
        return String.format("%dh %dm", hours, minutes);
    }

    // ========== DATA CLASSES ==========

    public record OverallStats(
        long totalQueries,
        long totalEscalations,
        double escalationRate,
        double avgSatisfaction,
        long satisfactionResponses,
        double avgResponseTimeMs,
        long p95ResponseTimeMs,
        long p99ResponseTimeMs,
        Duration uptime,
        int totalSessions
    ) {}

    public record IntentStats(
        SupportIntent intent,
        long queryCount,
        double avgResponseTimeMs,
        double avgConfidence
    ) {}

    public record HourlyStats(
        String hour,
        long queryCount,
        long escalationCount,
        double avgResponseTimeMs,
        Map<SupportIntent, Long> intentBreakdown
    ) {}

    // ========== INTERNAL TRACKING CLASSES ==========

    private static class IntentMetrics {
        private final SupportIntent intent;
        private final LongAdder queryCount = new LongAdder();
        private final LongAdder totalResponseTime = new LongAdder();
        private final LongAdder totalConfidence = new LongAdder();
        private final LongAdder confidenceCount = new LongAdder();

        IntentMetrics(SupportIntent intent) {
            this.intent = intent;
        }

        void recordQuery(long responseTimeMs, double confidence) {
            queryCount.increment();
            totalResponseTime.add(responseTimeMs);
            totalConfidence.add((long) (confidence * 100));
            confidenceCount.increment();
        }

        long getQueryCount() {
            return queryCount.sum();
        }

        IntentStats getStats() {
            long count = queryCount.sum();
            double avgTime = count > 0 ? (double) totalResponseTime.sum() / count : 0;
            double avgConf = confidenceCount.sum() > 0
                ? (double) totalConfidence.sum() / confidenceCount.sum() / 100
                : 0;
            return new IntentStats(intent, count, avgTime, avgConf);
        }
    }

    private static class HourlyBucket {
        private final String hour;
        private final LongAdder queryCount = new LongAdder();
        private final LongAdder escalationCount = new LongAdder();
        private final LongAdder totalResponseTime = new LongAdder();
        private final Map<SupportIntent, LongAdder> intentCounts = new ConcurrentHashMap<>();

        HourlyBucket(String hour) {
            this.hour = hour;
        }

        void recordQuery(SupportIntent intent, long responseTimeMs) {
            queryCount.increment();
            totalResponseTime.add(responseTimeMs);
            intentCounts.computeIfAbsent(intent, k -> new LongAdder()).increment();
        }

        void recordEscalation() {
            escalationCount.increment();
        }

        HourlyStats getStats() {
            long count = queryCount.sum();
            double avgTime = count > 0 ? (double) totalResponseTime.sum() / count : 0;

            Map<SupportIntent, Long> breakdown = intentCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));

            return new HourlyStats(hour, count, escalationCount.sum(), avgTime, breakdown);
        }
    }

    private static class SessionMetrics {
        private final String sessionId;
        private final Instant startTime = Instant.now();
        private final List<SupportIntent> intents = new ArrayList<>();
        private final LongAdder queryCount = new LongAdder();
        private final LongAdder totalResponseTime = new LongAdder();
        private boolean escalated;
        private int satisfactionRating;

        SessionMetrics(String sessionId) {
            this.sessionId = sessionId;
        }

        void recordQuery(SupportIntent intent, long responseTimeMs) {
            queryCount.increment();
            totalResponseTime.add(responseTimeMs);
            intents.add(intent);
        }

        void setEscalated(boolean escalated) {
            this.escalated = escalated;
        }

        void setSatisfactionRating(int rating) {
            this.satisfactionRating = rating;
        }
    }
}
