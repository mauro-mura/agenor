package dev.jentic.runtime.behavior.composite;

import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorType;
import dev.jentic.core.composite.CompletionStrategy;
import dev.jentic.core.composite.CompositeBehavior;
import dev.jentic.core.composite.SchedulingHint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes multiple child behaviors in parallel using virtual threads.
 * Supports different completion strategies (ALL, ANY, FIRST, N_OF_M).
 *
 * <p>{@code ParallelBehavior} reports {@link SchedulingHint#ONCE}: the scheduler
 * fires all children in parallel immediately after registration via
 * {@code agent.addBehavior()}, waits for the configured completion strategy,
 * then marks the behavior inactive.
 */
public class ParallelBehavior extends CompositeBehavior {

    private static final Logger log = LoggerFactory.getLogger(ParallelBehavior.class);

    private final CompletionStrategy strategy;
    private final int requiredCompletions; // For N_OF_M strategy
    private final AtomicInteger completedCount;  // Successful completions only
    private final AtomicInteger finishedCount;   // All finished (success + failure)
    private Duration childTimeout;

    public ParallelBehavior(String behaviorId) {
        this(behaviorId, CompletionStrategy.ALL);
    }

    public ParallelBehavior(String behaviorId, CompletionStrategy strategy) {
        this(behaviorId, strategy, 0);
    }

    public ParallelBehavior(String behaviorId, CompletionStrategy strategy, int requiredCompletions) {
        this(behaviorId, strategy, requiredCompletions, null);
    }

    public ParallelBehavior(String behaviorId, CompletionStrategy strategy,
                            int requiredCompletions, Duration childTimeout) {
        super(behaviorId);
        this.strategy = strategy;
        this.requiredCompletions = requiredCompletions;
        this.completedCount = new AtomicInteger(0);
        this.finishedCount = new AtomicInteger(0);
        this.childTimeout = childTimeout;
    }

    // -------------------------------------------------------------------------
    // Scheduling contract
    // -------------------------------------------------------------------------

    @Override
    public BehaviorType getType() {
        return BehaviorType.PARALLEL;
    }

    /**
     * {@code ParallelBehavior} is a one-shot fan-out: fire all children in parallel,
     * wait for the completion strategy, then done. The scheduler drives this automatically.
     */
    @Override
    public SchedulingHint getSchedulingHint() {
        return SchedulingHint.ONCE;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> execute() {
        if (!active || childBehaviors.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Parallel behavior '{}' executing {} child behaviors with strategy: {}",
                behaviorId, childBehaviors.size(), strategy);

        completedCount.set(0);
        finishedCount.set(0);

        return switch (strategy) {
            case ALL   -> executeAll();
            case ANY   -> executeAny();
            case FIRST -> executeFirst();
            case N_OF_M -> executeNOfM();
        };
    }

    private CompletableFuture<Void> executeAll() {
        List<CompletableFuture<Void>> futures = childBehaviors.stream()
                .map(this::executeChild)
                .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> executeAny() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        for (Behavior child : childBehaviors) {
            executeChild(child).thenRun(() -> done.complete(null));
        }
        return done;
    }

    private CompletableFuture<Void> executeFirst() {
        CompletableFuture<Void>[] futures = childBehaviors.stream()
                .map(this::executeChild)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.anyOf(futures).thenAccept(__ -> {});
    }

    private CompletableFuture<Void> executeNOfM() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int target = requiredCompletions > 0 ? requiredCompletions : childBehaviors.size();
        for (Behavior child : childBehaviors) {
            // completedCount is already incremented by executeChild() on success;
            // just read it here to avoid double-counting.
            executeChild(child).thenRun(() -> {
                if (completedCount.get() >= target) {
                    done.complete(null);
                }
            });
        }
        return done;
    }

    private CompletableFuture<Void> executeChild(Behavior child) {
        CompletableFuture<Void> future = child.execute();
        if (childTimeout != null) {
            future = future.orTimeout(childTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return future
                .thenRun(() -> {
                    completedCount.incrementAndGet();
                    finishedCount.incrementAndGet();
                    log.debug("Parallel behavior '{}': child '{}' completed", behaviorId, child.getBehaviorId());
                })
                .exceptionally(ex -> {
                    finishedCount.incrementAndGet();
                    log.warn("Parallel behavior '{}': child '{}' failed: {}",
                            behaviorId, child.getBehaviorId(), ex.getMessage());
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    public int getCompletedCount() {
        return completedCount.get();
    }

    public int getFinishedCount() {
        return finishedCount.get();
    }

    public CompletionStrategy getStrategy() {
        return strategy;
    }

    public Duration getChildTimeout() {
        return childTimeout;
    }

    public void setChildTimeout(Duration childTimeout) {
        this.childTimeout = childTimeout;
    }
}