package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.behavior.BaseBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ScheduledBehavior - Advanced behavior for cron-like scheduled execution.
 *
 * <p>Features:
 * <ul>
 *   <li>Full cron expression support (6 fields)</li>
 *   <li>Timezone-aware scheduling</li>
 *   <li>Missed execution policies (SKIP, EXECUTE_ONCE)</li>
 *   <li>Execution tracking and metrics</li>
 *   <li>Success/failure callbacks</li>
 *   <li>Graceful shutdown with in-flight execution handling</li>
 * </ul>
 *
 * <p>Cron Format (6 fields):
 * <pre>
 * ┌───────────── second (0-59)
 * │ ┌─────────── minute (0-59)
 * │ │ ┌───────── hour (0-23)
 * │ │ │ ┌─────── day of month (1-31)
 * │ │ │ │ ┌───── month (1-12 or JAN-DEC)
 * │ │ │ │ │ ┌─── day of week (0-6 or SUN-SAT, 0=Sunday)
 * * * * * * *
 * </pre>
 *
 */
public abstract class ScheduledBehavior extends BaseBehavior {

    private static final Logger log = LoggerFactory.getLogger(ScheduledBehavior.class);

    /**
     * Policy for handling missed executions.
     */
    public enum MissedExecutionPolicy {
        /**
         * Skip missed executions and wait for the next scheduled time.
         */
        SKIP,

        /**
         * Execute once immediately if execution was missed, then resume normal schedule.
         */
        EXECUTE_ONCE
    }

    // Configuration
    private final CronExpression cronExpression;
    private final ZoneId timezone;
    private MissedExecutionPolicy missedExecutionPolicy;
    private Duration executionTimeout;

    // State management
    private final AtomicBoolean executing = new AtomicBoolean(false);
    private volatile ZonedDateTime lastExecutionTime;
    private volatile ZonedDateTime nextExecutionTime;
    private volatile ScheduledFuture<?> scheduledFuture;

    // Metrics
    private final AtomicInteger totalExecutions = new AtomicInteger(0);
    private final AtomicInteger successfulExecutions = new AtomicInteger(0);
    private final AtomicInteger failedExecutions = new AtomicInteger(0);
    private final AtomicInteger missedExecutions = new AtomicInteger(0);
    private final AtomicLong totalExecutionTimeMs = new AtomicLong(0);

    // Callbacks
    private volatile Consumer<ScheduledBehavior> onSuccessCallback;
    private volatile Consumer<Exception> onFailureCallback;
    private volatile Runnable onMissedCallback;

    // Scheduler
    private final ScheduledExecutorService scheduler;
    private final boolean ownScheduler;

    /**
     * Create a scheduled behavior with default timezone (system default).
     *
     * @param behaviorId unique identifier for this behavior
     * @param cronExpression cron expression (6 fields)
     */
    protected ScheduledBehavior(String behaviorId, String cronExpression) {
        this(behaviorId, cronExpression, ZoneId.systemDefault());
    }

    /**
     * Create a scheduled behavior with specific timezone.
     *
     * @param behaviorId unique identifier for this behavior
     * @param cronExpression cron expression (6 fields)
     * @param timezone timezone for schedule interpretation
     */
    protected ScheduledBehavior(String behaviorId, String cronExpression, ZoneId timezone) {
        this(behaviorId, cronExpression, timezone, null);
    }

    /**
     * Create a scheduled behavior with custom scheduler.
     *
     * @param behaviorId unique identifier for this behavior
     * @param cronExpression cron expression (6 fields)
     * @param timezone timezone for schedule interpretation
     * @param scheduler custom scheduler (if null, creates own)
     */
    protected ScheduledBehavior(String behaviorId, String cronExpression,
                                ZoneId timezone, ScheduledExecutorService scheduler) {
        super(behaviorId, BehaviorType.SCHEDULED, null); // No fixed interval for scheduled behaviors

        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            throw new IllegalArgumentException("Cron expression cannot be null or empty");
        }

        this.cronExpression = CronExpression.parse(cronExpression);
        this.timezone = timezone != null ? timezone : ZoneId.systemDefault();
        this.missedExecutionPolicy = MissedExecutionPolicy.SKIP;
        this.executionTimeout = Duration.ofMinutes(10); // Default 10 minute timeout

        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownScheduler = false;
        } else {
            this.scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "scheduled-behavior-" + behaviorId);
                t.setDaemon(true);
                return t;
            });
            this.ownScheduler = true;
        }

        calculateNextExecution();
    }

    // ========== FACTORY METHODS ==========

    /**
     * Create a behavior that runs every hour.
     *
     * @param behaviorId unique identifier
     * @param action the action to execute
     * @return configured ScheduledBehavior
     */
    public static ScheduledBehavior everyHour(String behaviorId, Runnable action) {
        return new ScheduledBehavior(behaviorId, "0 0 * * * *") {
            @Override
            protected void scheduledAction() {
                action.run();
            }
        };
    }

    /**
     * Create a behavior that runs daily at a specific time.
     *
     * @param behaviorId unique identifier
     * @param hour hour of day (0-23)
     * @param minute minute of hour (0-59)
     * @param action the action to execute
     * @return configured ScheduledBehavior
     */
    public static ScheduledBehavior daily(String behaviorId, int hour, int minute, Runnable action) {
        String cron = String.format("0 %d %d * * *", minute, hour);
        return new ScheduledBehavior(behaviorId, cron) {
            @Override
            protected void scheduledAction() {
                action.run();
            }
        };
    }

    /**
     * Create a behavior that runs on weekdays at a specific time.
     *
     * @param behaviorId unique identifier
     * @param hour hour of day (0-23)
     * @param minute minute of hour (0-59)
     * @param action the action to execute
     * @return configured ScheduledBehavior
     */
    public static ScheduledBehavior weekdays(String behaviorId, int hour, int minute, Runnable action) {
        String cron = String.format("0 %d %d * * MON-FRI", minute, hour);
        return new ScheduledBehavior(behaviorId, cron) {
            @Override
            protected void scheduledAction() {
                action.run();
            }
        };
    }

    // ========== ABSTRACT METHOD ==========

    /**
     * The action to execute on schedule.
     * Subclasses must implement this method.
     *
     * @throws Exception if execution fails
     */
    protected abstract void scheduledAction() throws Exception;

    /**
     * Implementation of BaseBehavior's action() method.
     * Not used - ScheduledBehavior overrides execute() completely.
     */
    @Override
    protected void action() {
        // Not used - execute() is overridden with scheduling logic
    }

    // ========== BEHAVIOR LIFECYCLE ==========

    @Override
    public CompletableFuture<Void> execute() {
        if (!isActive()) {
            return CompletableFuture.completedFuture(null);
        }

        ZonedDateTime now = ZonedDateTime.now(timezone);

        // Check if we should execute
        if (nextExecutionTime != null && now.isBefore(nextExecutionTime)) {
            // Not time yet, schedule next check
            scheduleNextCheck();
            return CompletableFuture.completedFuture(null);
        }

        // Check for missed execution
        if (lastExecutionTime != null && nextExecutionTime != null &&
            now.isAfter(nextExecutionTime.plusSeconds(60))) {
            handleMissedExecution(now);
        }

        // Execute if not already executing
        if (executing.compareAndSet(false, true)) {
            return executeScheduledAction()
                .whenComplete((result, throwable) -> {
                    executing.set(false);
                    calculateNextExecution();
                    scheduleNextCheck();
                });
        }

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> executeScheduledAction() {
        long startTime = System.currentTimeMillis();
        totalExecutions.incrementAndGet();

        log.debug("Executing scheduled behavior '{}' at {}",
                 getBehaviorId(),
                 ZonedDateTime.now(timezone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        CompletableFuture<Void> executionFuture = CompletableFuture.runAsync(() -> {
            try {
                scheduledAction();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, scheduler);

        // Apply timeout
        if (executionTimeout != null) {
            executionFuture = executionFuture.orTimeout(
                executionTimeout.toMillis(),
                TimeUnit.MILLISECONDS
            );
        }

        return executionFuture
            .whenComplete((result, throwable) -> {
                long executionTime = System.currentTimeMillis() - startTime;
                totalExecutionTimeMs.addAndGet(executionTime);
                lastExecutionTime = ZonedDateTime.now(timezone);

                if (throwable != null) {
                    failedExecutions.incrementAndGet();
                    log.error("Scheduled behavior '{}' failed after {}ms",
                             getBehaviorId(), executionTime, throwable);

                    if (onFailureCallback != null) {
                        try {
                            onFailureCallback.accept(
                                throwable instanceof CompletionException ?
                                (Exception) throwable.getCause() :
                                (Exception) throwable
                            );
                        } catch (Exception e) {
                            log.error("Error in failure callback", e);
                        }
                    }
                } else {
                    successfulExecutions.incrementAndGet();
                    log.debug("Scheduled behavior '{}' completed successfully in {}ms",
                             getBehaviorId(), executionTime);

                    if (onSuccessCallback != null) {
                        try {
                            onSuccessCallback.accept(this);
                        } catch (Exception e) {
                            log.error("Error in success callback", e);
                        }
                    }
                }
            });
    }

    private void handleMissedExecution(ZonedDateTime now) {
        missedExecutions.incrementAndGet();

        log.warn("Scheduled behavior '{}' missed execution at {}. Current time: {}",
                getBehaviorId(),
                nextExecutionTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        if (onMissedCallback != null) {
            try {
                onMissedCallback.run();
            } catch (Exception e) {
                log.error("Error in missed execution callback", e);
            }
        }

        // Handle based on policy
        if (missedExecutionPolicy == MissedExecutionPolicy.EXECUTE_ONCE) {
            log.info("Executing missed behavior '{}' immediately", getBehaviorId());
            // Execution will happen in next cycle
        } else {
            log.debug("Skipping missed execution for behavior '{}'", getBehaviorId());
            calculateNextExecution(); // Skip to next scheduled time
        }
    }

    private void scheduleNextCheck() {
        if (!isActive() || nextExecutionTime == null) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(timezone);
        Duration delay = Duration.between(now, nextExecutionTime);

        // Add a small buffer to ensure we don't execute early
        delay = delay.plusMillis(100);

        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }

        if (scheduledFuture != null && !scheduledFuture.isDone()) {
            scheduledFuture.cancel(false);
        }

        scheduledFuture = scheduler.schedule(
            () -> execute(),
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void calculateNextExecution() {
        ZonedDateTime now = ZonedDateTime.now(timezone);
        nextExecutionTime = cronExpression.getNextExecution(now);

        if (nextExecutionTime != null) {
            log.debug("Behavior '{}' next execution at: {}",
                     getBehaviorId(),
                     nextExecutionTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else {
            log.warn("Behavior '{}' has no future executions", getBehaviorId());
            stop();
        }
    }

    @Override
    public void stop() {
        super.stop();

        if (scheduledFuture != null && !scheduledFuture.isDone()) {
            scheduledFuture.cancel(false);
        }

        if (ownScheduler) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.debug("Scheduled behavior '{}' stopped", getBehaviorId());
    }

    // ========== CONFIGURATION ==========

    /**
     * Set the missed execution policy.
     *
     * @param policy the policy to use
     * @return this behavior for chaining
     */
    public ScheduledBehavior setMissedExecutionPolicy(MissedExecutionPolicy policy) {
        this.missedExecutionPolicy = policy;
        return this;
    }

    /**
     * Set the execution timeout.
     *
     * @param timeout max duration for each execution
     * @return this behavior for chaining
     */
    public ScheduledBehavior setExecutionTimeout(Duration timeout) {
        this.executionTimeout = timeout;
        return this;
    }

    // ========== CALLBACKS ==========

    /**
     * Register a callback for successful executions.
     *
     * @param callback the callback to invoke
     * @return this behavior for chaining
     */
    public ScheduledBehavior onSuccess(Consumer<ScheduledBehavior> callback) {
        this.onSuccessCallback = callback;
        return this;
    }

    /**
     * Register a callback for failed executions.
     *
     * @param callback the callback to invoke with the exception
     * @return this behavior for chaining
     */
    public ScheduledBehavior onFailure(Consumer<Exception> callback) {
        this.onFailureCallback = callback;
        return this;
    }

    /**
     * Register a callback for missed executions.
     *
     * @param callback the callback to invoke
     * @return this behavior for chaining
     */
    public ScheduledBehavior onMissed(Runnable callback) {
        this.onMissedCallback = callback;
        return this;
    }

    // ========== GETTERS ==========

    public String getCronExpression() {
        return cronExpression.getExpression();
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public ZonedDateTime getNextExecutionTime() {
        return nextExecutionTime;
    }

    public ZonedDateTime getLastExecutionTime() {
        return lastExecutionTime;
    }

    public Duration getTimeUntilNextExecution() {
        if (nextExecutionTime == null) {
            return null;
        }
        return Duration.between(ZonedDateTime.now(timezone), nextExecutionTime);
    }

    public boolean isExecuting() {
        return executing.get();
    }

    // ========== METRICS ==========

    public int getTotalExecutions() {
        return totalExecutions.get();
    }

    public int getSuccessfulExecutions() {
        return successfulExecutions.get();
    }

    public int getFailedExecutions() {
        return failedExecutions.get();
    }

    public int getMissedExecutions() {
        return missedExecutions.get();
    }

    public double getSuccessRate() {
        int total = totalExecutions.get();
        return total > 0 ? (double) successfulExecutions.get() / total : 0.0;
    }

    public double getAverageExecutionTimeMs() {
        int total = totalExecutions.get();
        return total > 0 ? (double) totalExecutionTimeMs.get() / total : 0.0;
    }

    /**
     * Get a summary of execution metrics.
     *
     * @return formatted metrics string
     */
    public String getMetricsSummary() {
        return String.format(
            "ScheduledBehavior[%s] Metrics: total=%d, success=%d, failed=%d, missed=%d, " +
            "successRate=%.1f%%, avgTime=%.1fms, nextExec=%s",
            getBehaviorId(),
            getTotalExecutions(),
            getSuccessfulExecutions(),
            getFailedExecutions(),
            getMissedExecutions(),
            getSuccessRate() * 100,
            getAverageExecutionTimeMs(),
            nextExecutionTime != null ?
                nextExecutionTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) :
                "none"
        );
    }
}
