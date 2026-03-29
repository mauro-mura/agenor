package dev.jentic.runtime.behavior.composite;

import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorType;
import dev.jentic.core.composite.CompositeBehavior;
import dev.jentic.core.composite.SchedulingHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Executes child behaviors sequentially, one after another.
 * Each child waits for the previous one to complete.
 *
 * <h2>Scheduling modes</h2>
 * <p>The mode is determined by whether an {@code interval} is provided:
 * <ul>
 *   <li><strong>One-shot</strong> (no interval) — runs all steps once, then becomes inactive.
 *       {@link SchedulingHint#ONCE}: {@code addBehavior()} is sufficient.</li>
 *   <li><strong>Repeating</strong> (interval provided) — each scheduler tick advances one step
 *       and wraps around. {@link SchedulingHint#CYCLIC}: {@code addBehavior()} is sufficient.</li>
 * </ul>
 *
 * <h2>Usage — one-shot</h2>
 * <pre>{@code
 * SequentialBehavior startup = new SequentialBehavior("startup");
 * startup.addChildBehavior(new ConnectDatabaseBehavior());
 * startup.addChildBehavior(new LoadConfigBehavior());
 * startup.addChildBehavior(new RegisterWithDirectoryBehavior());
 *
 * agent.addBehavior(startup); // registers and triggers automatically
 * }</pre>
 *
 * <h2>Usage — repeating (round-robin)</h2>
 * <pre>{@code
 * // One step every second, cycling through all children
 * SequentialBehavior roundRobin = new SequentialBehavior("round-robin", Duration.ofSeconds(1));
 * roundRobin.addChildBehavior(OneShotBehavior.from("poll-north",   this::pollNorth));
 * roundRobin.addChildBehavior(OneShotBehavior.from("poll-central", this::pollCentral));
 * roundRobin.addChildBehavior(OneShotBehavior.from("poll-south",   this::pollSouth));
 *
 * agent.addBehavior(roundRobin); // registers and schedules cyclically
 * }</pre>
 *
 * <h2>Usage — one-shot with step timeout</h2>
 * <pre>{@code
 * SequentialBehavior pipeline = new SequentialBehavior("pipeline")
 *         .withStepTimeout(Duration.ofSeconds(10));
 * pipeline.addChildBehavior(new StepOneBehavior());
 * pipeline.addChildBehavior(new StepTwoBehavior());
 *
 * agent.addBehavior(pipeline);
 * }</pre>
 *
 * <h2>Usage — repeating with step timeout</h2>
 * <pre>{@code
 * SequentialBehavior roundRobin = new SequentialBehavior("poller", Duration.ofMillis(200))
 *         .withStepTimeout(Duration.ofSeconds(5));
 * roundRobin.addChildBehavior(OneShotBehavior.from("poll-north", this::pollNorth));
 * roundRobin.addChildBehavior(OneShotBehavior.from("poll-south", this::pollSouth));
 *
 * agent.addBehavior(roundRobin);
 * }</pre>
 */
public class SequentialBehavior extends CompositeBehavior {

    private static final Logger log = LoggerFactory.getLogger(SequentialBehavior.class);

    private int currentIndex = 0;
    private Duration stepTimeout;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * One-shot sequential: runs all steps once, then becomes inactive.
     *
     * @param behaviorId unique behavior identifier
     */
    public SequentialBehavior(String behaviorId) {
        super(behaviorId);
    }

    /**
     * Repeating sequential (round-robin): each scheduler tick advances one step and wraps around.
     *
     * @param behaviorId unique behavior identifier
     * @param interval   how often the scheduler calls {@code execute()} to advance one step;
     *                   must not be null
     * @throws IllegalArgumentException if interval is null
     */
    public SequentialBehavior(String behaviorId, Duration interval) {
        super(behaviorId);
        if (interval == null) {
            throw new IllegalArgumentException(
                "SequentialBehavior '" + behaviorId + "': interval must not be null for repeating mode.");
        }
        this.interval = interval;
    }

    /**
     * Sets a per-step timeout and returns {@code this} for fluent chaining.
     *
     * <p>Works for both one-shot and repeating modes:
     * <pre>{@code
     * new SequentialBehavior("pipeline").withStepTimeout(Duration.ofSeconds(10))
     * new SequentialBehavior("poller", Duration.ofMillis(200)).withStepTimeout(Duration.ofSeconds(5))
     * }</pre>
     *
     * @param stepTimeout maximum duration allowed per step; {@code null} disables the timeout
     * @return this instance
     */
    public SequentialBehavior withStepTimeout(Duration stepTimeout) {
        this.stepTimeout = stepTimeout;
        return this;
    }

    // -------------------------------------------------------------------------
    // Scheduling contract
    // -------------------------------------------------------------------------

    @Override
    public BehaviorType getType() {
        return BehaviorType.SEQUENTIAL;
    }

    /**
     * Returns {@link SchedulingHint#ONCE} for one-shot sequences and
     * {@link SchedulingHint#CYCLIC} for repeating sequences.
     *
     * <p>The {@link dev.jentic.runtime.scheduler.SimpleBehaviorScheduler} uses this
     * to drive the behavior automatically when registered via {@code agent.addBehavior()}.
     */
    @Override
    public SchedulingHint getSchedulingHint() {
        return interval != null ? SchedulingHint.CYCLIC : SchedulingHint.ONCE;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> execute() {
        if (!active || childBehaviors.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (interval != null) {
            // Repeating: each scheduler tick executes exactly one step
            return executeSingleStep();
        } else {
            // One-shot: chain all steps via CompletableFuture composition
            return executeCurrentBehavior();
        }
    }

    /**
     * Executes the current step (repeating mode — one step per scheduler tick).
     */
    private CompletableFuture<Void> executeSingleStep() {
        if (!active) {
            return CompletableFuture.completedFuture(null);
        }

        Behavior current = childBehaviors.get(currentIndex);
        log.debug("Sequential behavior '{}' executing step {}/{}: {}",
                behaviorId, currentIndex + 1, childBehaviors.size(), current.getBehaviorId());

        CompletableFuture<Void> stepFuture = applyTimeout(current.execute());

        // Advance index; wrap to 0 immediately after the last step so getCurrentStep()
        // reflects the reset before the next scheduler tick.
        currentIndex++;
        if (currentIndex >= childBehaviors.size()) {
            currentIndex = 0;
            log.trace("Sequential behavior '{}' wrapped to beginning", behaviorId);
        }

        return stepFuture.exceptionally(ex -> {
            log.warn("Sequential behavior '{}' step '{}' failed: {}",
                    behaviorId, current.getBehaviorId(), ex.getMessage());
            return null;
        });
    }

    /**
     * Chains all remaining steps sequentially (one-shot mode).
     */
    private CompletableFuture<Void> executeCurrentBehavior() {
        if (!active || currentIndex >= childBehaviors.size()) {
            // One-shot complete: mark inactive but leave currentIndex == size() so
            // getCurrentStep() reports the total steps executed (not 0).
            active = false;
            return CompletableFuture.completedFuture(null);
        }

        Behavior current = childBehaviors.get(currentIndex);
        log.debug("Sequential behavior '{}' executing step {}/{}: {}",
                behaviorId, currentIndex + 1, childBehaviors.size(), current.getBehaviorId());

        currentIndex++;
        return applyTimeout(current.execute())
                .exceptionally(ex -> {
                    log.warn("Sequential behavior '{}' step '{}' failed: {}",
                            behaviorId, current.getBehaviorId(), ex.getMessage());
                    return null;
                })
                .thenCompose(__ -> executeCurrentBehavior());
    }

    private CompletableFuture<Void> applyTimeout(CompletableFuture<Void> future) {
        if (stepTimeout == null) {
            return future;
        }
        return future.orTimeout(stepTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                        log.warn("Sequential behavior '{}' step timed out after {}",
                                behaviorId, stepTimeout);
                    }
                    throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
                });
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    public int getCurrentStep() {
        return currentIndex;
    }

    public int getTotalSteps() {
        return childBehaviors.size();
    }

    /** Returns {@code true} if this behavior runs in repeating (round-robin) mode. */
    public boolean isRepeating() {
        return interval != null;
    }

    public void reset() {
        currentIndex = 0;
    }

    public Duration getStepTimeout() {
        return stepTimeout;
    }

    public void setStepTimeout(Duration stepTimeout) {
        this.stepTimeout = stepTimeout;
    }
}