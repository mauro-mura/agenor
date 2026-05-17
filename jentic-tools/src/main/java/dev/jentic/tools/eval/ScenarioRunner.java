package dev.jentic.tools.eval;

import dev.jentic.core.Message;
import dev.jentic.core.messaging.FilterableSubscriber;
import dev.jentic.core.messaging.Subscription;
import dev.jentic.runtime.JenticRuntime;
import dev.jentic.tools.health.HealthCheckService;
import dev.jentic.tools.health.HealthCheckService.HealthReport;
import dev.jentic.tools.metrics.MetricsService;
import dev.jentic.tools.metrics.MetricsService.MetricsSnapshot;
import dev.jentic.tools.history.MessageHistoryService;
import dev.jentic.tools.history.MessageHistoryService.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Executes evaluation scenarios against a Jentic runtime.
 *
 * <p>The runner orchestrates the complete evaluation lifecycle:
 * <ol>
 *   <li>Setup - prepare the runtime environment</li>
 *   <li>Execute - trigger agent behavior</li>
 *   <li>Collect - gather metrics, health status, messages</li>
 *   <li>Verify - run assertions against collected data</li>
 *   <li>Teardown - cleanup resources</li>
 * </ol>
 *
 * <p>Reuses existing Jentic tools services:
 * <ul>
 *   <li>{@link MetricsService} - for metrics collection</li>
 *   <li>{@link HealthCheckService} - for health verification</li>
 *   <li>{@link MessageHistoryService} - for message tracking</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * JenticRuntime runtime = JenticRuntime.create();
 * ScenarioRunner runner = new ScenarioRunner(runtime);
 * 
 * EvaluationResult result = runner.run(myScenario);
 * 
 * if (result.passed()) {
 *     System.out.println("All assertions passed!");
 * } else {
 *     result.failedAssertions().forEach(System.out::println);
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final JenticRuntime runtime;
    private final MetricsService metrics;
    private final HealthCheckService health;
    private final MessageHistoryService history;
    private final ExecutorService executor;

    /**
     * Creates a runner with a new set of services.
     *
     * @param runtime the Jentic runtime to evaluate
     */
    public ScenarioRunner(JenticRuntime runtime) {
        this(runtime, 500);
    }

    /**
     * Creates a runner with custom message history size.
     *
     * @param runtime the Jentic runtime to evaluate
     * @param historySize maximum messages to retain
     */
    public ScenarioRunner(JenticRuntime runtime, int historySize) {
        this.runtime = Objects.requireNonNull(runtime, "Runtime cannot be null");
        this.metrics = new MetricsService(runtime);
        this.health = new HealthCheckService(runtime);
        this.history = new MessageHistoryService(historySize);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Creates a runner with externally provided services.
     *
     * @param runtime the Jentic runtime
     * @param metrics metrics service
     * @param health health check service
     * @param history message history service
     */
    public ScenarioRunner(
            JenticRuntime runtime,
            MetricsService metrics,
            HealthCheckService health,
            MessageHistoryService history) {
        this.runtime = Objects.requireNonNull(runtime, "Runtime cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "Metrics cannot be null");
        this.health = Objects.requireNonNull(health, "Health cannot be null");
        this.history = Objects.requireNonNull(history, "History cannot be null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Runs a single scenario.
     *
     * @param scenario the scenario to run
     * @return evaluation result
     */
    public EvaluationResult run(Scenario scenario) {
        Objects.requireNonNull(scenario, "Scenario cannot be null");

        log.info("Starting scenario: {} (timeout: {}ms)", 
            scenario.getId(), scenario.getTimeout().toMillis());

        Instant startTime = Instant.now();
        Subscription subscription = null;

        try {
            // Clear history for fresh capture
            history.clear();

            // Setup message capture BEFORE running scenario
            subscription = setupMessageCapture();
            
            // Small delay to ensure subscription is active
            Thread.sleep(50);

            // Run with timeout
            CompletableFuture<EvaluationResult> future = CompletableFuture.supplyAsync(
                () -> executeScenario(scenario, startTime),
                executor
            );

            return future.get(scenario.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            log.warn("Scenario {} timed out after {}ms", 
                scenario.getId(), scenario.getTimeout().toMillis());
            return EvaluationResult.timeout(scenario.getId(), scenario.getTimeout());

        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Scenario {} failed with error: {}", scenario.getId(), cause.getMessage(), cause);
            return EvaluationResult.error(
                scenario.getId(),
                Duration.between(startTime, Instant.now()),
                cause.getMessage()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EvaluationResult.error(
                scenario.getId(),
                Duration.between(startTime, Instant.now()),
                "Interrupted"
            );

        } finally {
            // Cleanup message capture
            if (subscription != null) {
                cleanupMessageCapture(subscription);
            }

            // Always run teardown
            try {
                scenario.teardown(runtime);
            } catch (Exception e) {
                log.warn("Teardown failed for scenario {}: {}", scenario.getId(), e.getMessage());
            }
        }
    }

    /**
     * Runs multiple scenarios and returns all results.
     *
     * @param scenarios scenarios to run
     * @return list of results in order
     */
    public List<EvaluationResult> runAll(List<Scenario> scenarios) {
        List<EvaluationResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            results.add(run(scenario));
        }
        return results;
    }

    /**
     * Runs multiple scenarios in parallel.
     *
     * @param scenarios scenarios to run
     * @return list of results (order may vary)
     */
    public List<EvaluationResult> runAllParallel(List<Scenario> scenarios) {
        List<CompletableFuture<EvaluationResult>> futures = scenarios.stream()
            .map(s -> CompletableFuture.supplyAsync(() -> run(s), executor))
            .toList();

        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    /**
     * Creates an evaluation report from results.
     *
     * @param results evaluation results
     * @return formatted report
     */
    public EvaluationReport createReport(List<EvaluationResult> results) {
        return new EvaluationReport(results);
    }

    // === Internal Methods ===

    private EvaluationResult executeScenario(Scenario scenario, Instant startTime) {
        // 1. Setup
        log.debug("Running setup for scenario: {}", scenario.getId());
        metrics.increment("scenario.setup");
        try (var timer = metrics.startTimer("scenario.setup.time")) {
            scenario.setup(runtime);
        }

        // 2. Execute
        log.debug("Running execution for scenario: {}", scenario.getId());
        metrics.increment("scenario.execute");
        try (var timer = metrics.startTimer("scenario.execute.time")) {
            scenario.execute(runtime);
        }

        // 3. Wait a bit for async operations to complete
        waitForAsyncOperations();

        // 4. Collect data
        Instant endTime = Instant.now();
        MetricsSnapshot metricsSnapshot = metrics.snapshot();
        HealthReport healthReport = health.check();
        
        // Convert StoredMessage to Message for EvaluationContext
        List<StoredMessage> storedMessages = history.getRecent(1000);
        List<Message> capturedMessages = storedMessages.stream()
            .map(this::toMessage)
            .toList();

        log.debug("Collected {} messages for scenario: {}", 
            capturedMessages.size(), scenario.getId());

        // 5. Build context
        EvaluationContext context = new EvaluationContext(
            runtime,
            metricsSnapshot,
            healthReport,
            capturedMessages,
            startTime,
            endTime
        );

        // 6. Verify
        log.debug("Running verification for scenario: {}", scenario.getId());
        metrics.increment("scenario.verify");
        List<AssertionResult> assertions;
        try (var timer = metrics.startTimer("scenario.verify.time")) {
            assertions = scenario.verify(context);
        }

        // 7. Determine status
        Duration executionTime = Duration.between(startTime, endTime);
        boolean allPassed = assertions.stream().allMatch(AssertionResult::passed);

        EvaluationResult result;
        if (allPassed) {
            metrics.increment("scenario.passed");
            result = EvaluationResult.passed(
                scenario.getId(), executionTime, metricsSnapshot, healthReport, assertions
            );
        } else {
            metrics.increment("scenario.failed");
            result = EvaluationResult.failed(
                scenario.getId(), executionTime, metricsSnapshot, healthReport, assertions
            );
        }

        log.info("Scenario {} completed: {} ({} assertions, {}ms)",
            scenario.getId(), result.status(), 
            assertions.size(), executionTime.toMillis());

        return result;
    }

    /**
     * Converts StoredMessage back to Message.
     */
    private Message toMessage(StoredMessage stored) {
        return Message.builder()
            .id(stored.id())
            .topic(stored.topic())
            .senderId(stored.senderId())
            .receiverId(stored.receiverId())
            .correlationId(stored.correlationId())
            .content(stored.payload())
            .headers(stored.headers())
            .timestamp(stored.timestamp())
            .build();
    }

    private Subscription setupMessageCapture() {
        var dispatcher = runtime.getMessageDispatcher();
        if (!(dispatcher instanceof FilterableSubscriber fs)) {
            log.warn("MessageDispatcher does not support predicate filtering — message capture disabled");
            return null;
        }

        try {
            Subscription sub = fs.subscribeFiltered(
                message -> true,
                message -> {
                    history.store(message);
                    metrics.increment("messages.captured");
                    log.trace("Captured message: {} on topic {}", message.id(), message.topic());
                    return CompletableFuture.completedFuture(null);
                }
            );
            log.debug("Message capture subscription established: {}", sub.subscriptionId());
            return sub;
        } catch (Exception e) {
            log.warn("Failed to setup message capture: {}", e.getMessage());
            return null;
        }
    }

    private void cleanupMessageCapture(Subscription subscription) {
        try {
            subscription.unsubscribe();
        } catch (Exception e) {
            log.trace("Cleanup subscription failed: {}", e.getMessage());
        }
    }

    private void waitForAsyncOperations() {
        // Give async operations time to complete
        // InMemoryMessageService uses virtual threads for async delivery
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shuts down the runner and releases resources.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}