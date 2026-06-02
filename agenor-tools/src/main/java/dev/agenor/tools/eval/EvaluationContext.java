package dev.agenor.tools.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import dev.agenor.core.Agent;
import dev.agenor.core.Message;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.tools.health.HealthCheckService.HealthReport;
import dev.agenor.tools.health.HealthCheckService.HealthStatus;
import dev.agenor.tools.metrics.MetricsService.MetricsSnapshot;

/**
 * Context provided to scenario verification phase.
 *
 * <p>Provides access to collected metrics, health status, message history,
 * and convenient assertion methods. All assertion methods return
 * {@link AssertionResult} objects for aggregation.
 *
 * <p>Example usage:
 * <pre>{@code
 * scenario.verify(ctx -> List.of(
 *     ctx.assertAgentRunning("my-agent"),
 *     ctx.assertMessageReceived("response.topic"),
 *     ctx.assertHealthy(),
 *     ctx.assertResponseTimeUnder(Duration.ofMillis(100))
 * ));
 * }</pre>
 *
 * @since 0.5.0
 */
public class EvaluationContext {

    private final AgenorRuntime runtime;
    private final MetricsSnapshot metrics;
    private final HealthReport healthReport;
    private final List<Message> messages;
    private final Instant startTime;
    private final Instant endTime;

    /**
     * Creates a new evaluation context.
     *
     * @param runtime the Agenor runtime
     * @param metrics collected metrics snapshot
     * @param healthReport health check results
     * @param messages captured messages during execution
     * @param startTime scenario start time
     * @param endTime scenario end time
     */
    public EvaluationContext(
            AgenorRuntime runtime,
            MetricsSnapshot metrics,
            HealthReport healthReport,
            List<Message> messages,
            Instant startTime,
            Instant endTime) {
        this.runtime = Objects.requireNonNull(runtime, "Runtime cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "Metrics cannot be null");
        this.healthReport = Objects.requireNonNull(healthReport, "Health report cannot be null");
        this.messages = Objects.requireNonNull(messages, "Messages cannot be null");
        this.startTime = Objects.requireNonNull(startTime, "Start time cannot be null");
        this.endTime = Objects.requireNonNull(endTime, "End time cannot be null");
    }

    // === Accessors ===

    /**
     * Gets the Agenor runtime.
     *
     * @return the runtime
     */
    public AgenorRuntime runtime() {
        return runtime;
    }

    /**
     * Gets the metrics snapshot.
     *
     * @return metrics collected during execution
     */
    public MetricsSnapshot metrics() {
        return metrics;
    }

    /**
     * Gets the health report.
     *
     * @return health check results
     */
    public HealthReport healthReport() {
        return healthReport;
    }

    /**
     * Gets captured messages.
     *
     * @return list of messages captured during execution
     */
    public List<Message> messages() {
        return messages;
    }

    /**
     * Gets the execution duration.
     *
     * @return time between start and end
     */
    public Duration executionDuration() {
        return Duration.between(startTime, endTime);
    }

    // === Agent Assertions ===

    /**
     * Asserts that an agent exists and is running.
     *
     * @param agentId the agent ID to check
     * @return assertion result
     */
    public AssertionResult assertAgentRunning(String agentId) {
        Optional<Agent> agent = runtime.getAgents().stream()
            .filter(a -> a.getAgentId().equals(agentId))
            .findFirst();

        if (agent.isEmpty()) {
            return AssertionResult.fail(
                "agent.running." + agentId,
                "Agent not found: " + agentId
            );
        }

        if (!agent.get().isRunning()) {
            return AssertionResult.fail(
                "agent.running." + agentId,
                "Agent exists but is not running",
                "RUNNING",
                "STOPPED"
            );
        }

        return AssertionResult.pass("agent.running." + agentId);
    }

    /**
     * Asserts that an agent exists.
     *
     * @param agentId the agent ID to check
     * @return assertion result
     */
    public AssertionResult assertAgentExists(String agentId) {
        boolean exists = runtime.getAgents().stream()
            .anyMatch(a -> a.getAgentId().equals(agentId));

        if (!exists) {
            return AssertionResult.fail(
                "agent.exists." + agentId,
                "Agent not found: " + agentId
            );
        }

        return AssertionResult.pass("agent.exists." + agentId);
    }

    /**
     * Asserts the number of registered agents.
     *
     * @param expected expected agent count
     * @return assertion result
     */
    public AssertionResult assertAgentCount(int expected) {
        int actual = runtime.getAgents().size();

        if (actual != expected) {
            return AssertionResult.fail(
                "agent.count",
                "Agent count mismatch",
                expected,
                actual
            );
        }

        return AssertionResult.pass("agent.count", "Agent count: " + actual);
    }

    // === Message Assertions ===

    /**
     * Asserts that a message was received on the specified topic.
     *
     * @param topic the topic to check
     * @return assertion result
     */
    public AssertionResult assertMessageReceived(String topic) {
        boolean found = messages.stream()
            .anyMatch(m -> topic.equals(m.topic()));

        if (!found) {
            return AssertionResult.fail(
                "message.received." + topic,
                "No message received on topic: " + topic
            );
        }

        return AssertionResult.pass("message.received." + topic);
    }

    /**
     * Asserts that a message matching the predicate was received.
     *
     * @param name assertion name
     * @param predicate message matcher
     * @return assertion result
     */
    public AssertionResult assertMessageMatching(String name, Predicate<Message> predicate) {
        boolean found = messages.stream().anyMatch(predicate);

        if (!found) {
            return AssertionResult.fail(
                "message.matching." + name,
                "No message matching predicate: " + name
            );
        }

        return AssertionResult.pass("message.matching." + name);
    }

    /**
     * Asserts the number of messages received.
     *
     * @param expected expected message count
     * @return assertion result
     */
    public AssertionResult assertMessageCount(int expected) {
        int actual = messages.size();

        if (actual != expected) {
            return AssertionResult.fail(
                "message.count",
                "Message count mismatch",
                expected,
                actual
            );
        }

        return AssertionResult.pass("message.count", "Message count: " + actual);
    }

    /**
     * Asserts at least N messages were received.
     *
     * @param minCount minimum expected count
     * @return assertion result
     */
    public AssertionResult assertMessageCountAtLeast(int minCount) {
        int actual = messages.size();

        if (actual < minCount) {
            return AssertionResult.fail(
                "message.count.min",
                "Too few messages",
                ">= " + minCount,
                actual
            );
        }

        return AssertionResult.pass("message.count.min", "Message count: " + actual);
    }

    /**
     * Asserts no messages were dropped (received count equals sent count).
     *
     * @return assertion result
     */
    public AssertionResult assertNoDroppedMessages() {
        // Check metrics for dropped message counter
        Map<String, Object> metricsMap = metrics.metrics();

        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) metricsMap.get("counters");

        if (counters != null) {
            Long dropped = counters.get("messages.dropped");
            if (dropped != null && dropped > 0) {
                return AssertionResult.fail(
                    "message.dropped",
                    "Messages were dropped",
                    0,
                    dropped
                );
            }
        }

        return AssertionResult.pass("message.dropped", "No messages dropped");
    }

    // === Health Assertions ===

    /**
     * Asserts the system is healthy (all health checks pass).
     *
     * @return assertion result
     */
    public AssertionResult assertHealthy() {
        if (!healthReport.isHealthy()) {
            return AssertionResult.fail(
                "health.overall",
                "System is not healthy: " + healthReport.status().state(),
                "UP",
                healthReport.status().state()
            );
        }

        return AssertionResult.pass("health.overall");
    }

    /**
     * Asserts a specific health component is healthy.
     *
     * @param component component name (runtime, memory, agents, threads)
     * @return assertion result
     */
    public AssertionResult assertComponentHealthy(String component) {
        HealthStatus status = healthReport.components().get(component);

        if (status == null) {
            return AssertionResult.fail(
                "health.component." + component,
                "Health component not found: " + component
            );
        }

        if (!status.isUp()) {
            return AssertionResult.fail(
                "health.component." + component,
                "Component is not healthy: " + status.message(),
                "UP",
                status.state()
            );
        }

        return AssertionResult.pass("health.component." + component);
    }

    // === Timing Assertions ===

    /**
     * Asserts execution completed within the specified duration.
     *
     * @param maxDuration maximum allowed duration
     * @return assertion result
     */
    public AssertionResult assertCompletedWithin(Duration maxDuration) {
        Duration actual = executionDuration();

        if (actual.compareTo(maxDuration) > 0) {
            return AssertionResult.fail(
                "timing.duration",
                "Execution took too long",
                "<= " + maxDuration.toMillis() + "ms",
                actual.toMillis() + "ms"
            );
        }

        return AssertionResult.pass(
            "timing.duration",
            "Completed in " + actual.toMillis() + "ms"
        );
    }

    /**
     * Asserts average response time is under the specified threshold.
     *
     * @param maxResponseTime maximum allowed average response time
     * @return assertion result
     */
    public AssertionResult assertResponseTimeUnder(Duration maxResponseTime) {
        Map<String, Object> metricsMap = metrics.metrics();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> timers =
            (Map<String, Map<String, Object>>) metricsMap.get("timers");

        if (timers == null || timers.isEmpty()) {
            return AssertionResult.pass(
                "timing.response",
                "No timer data available"
            );
        }

        // Check message processing timer if available
        Map<String, Object> msgTimer = timers.get("message.processing");
        if (msgTimer != null) {
            Object meanObj = msgTimer.get("meanMs");
            if (meanObj instanceof Number) {
                double meanMs = ((Number) meanObj).doubleValue();
                if (meanMs > maxResponseTime.toMillis()) {
                    return AssertionResult.fail(
                        "timing.response",
                        "Average response time too high",
                        "<= " + maxResponseTime.toMillis() + "ms",
                        String.format("%.2fms", meanMs)
                    );
                }
            }
        }

        return AssertionResult.pass("timing.response");
    }

    // === Metrics Assertions ===

    /**
     * Asserts a counter metric has the expected value.
     *
     * @param counterName counter name
     * @param expected expected value
     * @return assertion result
     */
    public AssertionResult assertCounter(String counterName, long expected) {
        Map<String, Object> metricsMap = metrics.metrics();

        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) metricsMap.get("counters");

        if (counters == null) {
            return AssertionResult.fail(
                "metric.counter." + counterName,
                "No counters available"
            );
        }

        Long actual = counters.get(counterName);
        if (actual == null) {
            return AssertionResult.fail(
                "metric.counter." + counterName,
                "Counter not found: " + counterName
            );
        }

        if (!actual.equals(expected)) {
            return AssertionResult.fail(
                "metric.counter." + counterName,
                "Counter value mismatch",
                expected,
                actual
            );
        }

        return AssertionResult.pass("metric.counter." + counterName);
    }

    /**
     * Asserts a counter metric is at least the specified value.
     *
     * @param counterName counter name
     * @param minValue minimum expected value
     * @return assertion result
     */
    public AssertionResult assertCounterAtLeast(String counterName, long minValue) {
        Map<String, Object> metricsMap = metrics.metrics();

        @SuppressWarnings("unchecked")
        Map<String, Long> counters = (Map<String, Long>) metricsMap.get("counters");

        if (counters == null) {
            return AssertionResult.fail(
                "metric.counter.min." + counterName,
                "No counters available"
            );
        }

        Long actual = counters.get(counterName);
        if (actual == null) {
            return AssertionResult.fail(
                "metric.counter.min." + counterName,
                "Counter not found: " + counterName
            );
        }

        if (actual < minValue) {
            return AssertionResult.fail(
                "metric.counter.min." + counterName,
                "Counter value too low",
                ">= " + minValue,
                actual
            );
        }

        return AssertionResult.pass(
            "metric.counter.min." + counterName,
            counterName + " = " + actual
        );
    }

    // === Custom Assertions ===

    /**
     * Creates a custom assertion with the given predicate.
     *
     * @param name assertion name
     * @param condition condition to check
     * @param failureMessage message if condition is false
     * @return assertion result
     */
    public AssertionResult assertCondition(String name, boolean condition, String failureMessage) {
        if (!condition) {
            return AssertionResult.fail("custom." + name, failureMessage);
        }
        return AssertionResult.pass("custom." + name);
    }
}
