package dev.agenor.runtime.scheduler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.agenor.core.Agent;
import dev.agenor.core.composite.CompositeBehavior;
import dev.agenor.core.composite.SchedulingHint;
import dev.agenor.core.console.ConsoleEventListener;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorScheduler;

/**
 * Simple implementation of {@link BehaviorScheduler} backed by a
 * {@link ScheduledThreadPoolExecutor}.
 *
 * <p><strong>Platform threads, not virtual ones.</strong> The pool holds
 * {@code threadPoolSize} platform threads — four by default — shared by every behavior of
 * every agent in the runtime, and {@code executeBehavior} calls {@code execute().join()},
 * so a running behavior occupies its thread until it completes. Enough concurrently
 * blocking behaviors will therefore stall the rest of the runtime. Size the pool for the
 * expected concurrency, or keep behaviors non-blocking.
 */
public class SimpleBehaviorScheduler implements BehaviorScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimpleBehaviorScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledBehaviors = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ConsoleEventListener eventListener = ConsoleEventListener.noOp();
    private final AgenorTelemetry telemetry;

    public SimpleBehaviorScheduler() {
        this(4, AgenorTelemetry.noop());
    }

    public SimpleBehaviorScheduler(int threadPoolSize) {
        this(threadPoolSize, AgenorTelemetry.noop());
    }

    public SimpleBehaviorScheduler(int threadPoolSize, AgenorTelemetry telemetry) {
        this.scheduler  = new ScheduledThreadPoolExecutor(threadPoolSize);
        this.telemetry  = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    /**
     * Sets the event listener for behavior execution notifications.
     *
     * @param listener the listener, or null to use no-op
     */
    public void setEventListener(ConsoleEventListener listener) {
        this.eventListener = listener != null ? listener : ConsoleEventListener.noOp();
    }

    @Override
    public CompletableFuture<Void> schedule(Behavior behavior) {
        return CompletableFuture.runAsync(() -> {
            if (!running.get()) {
                log.warn("Scheduler not running, cannot schedule behavior: {}", behavior.getBehaviorId());
                return;
            }

            // A composite says how it wants to be driven through SchedulingHint, which exists
            // because BehaviorType cannot say it: a SequentialBehavior is cyclic or one-shot
            // depending on whether it carries an interval, and a control-flow composite is
            // neither. Ask the composite first, so the switch below never answers for a type
            // that only a composite returns.
            if (behavior instanceof CompositeBehavior composite) {
                scheduleComposite(composite);
                return;
            }

            switch (behavior.getType()) {
                case ONE_SHOT -> scheduleOneShot(behavior);
                case CYCLIC   -> scheduleCyclic(behavior);
                // A state machine decides its own next step, so the scheduler registers it and
                // leaves it alone; the owner drives it.
                case FSM -> log.debug("On-demand behavior registered (not auto-scheduled): {}",
                        behavior.getBehaviorId());
            }
        });
    }

    /**
     * Drives a composite behavior according to the {@link SchedulingHint} it declares.
     *
     * <ul>
     *   <li>{@link SchedulingHint#ONCE}    — fires {@code execute()} immediately, once.</li>
     *   <li>{@link SchedulingHint#CYCLIC}  — fires {@code execute()} at the behavior's interval.</li>
     *   <li>{@link SchedulingHint#ON_DEMAND} — only registers; caller must call {@code execute()} manually.</li>
     * </ul>
     *
     * <p>This is the only path a composite takes: {@link #schedule(Behavior)} routes every
     * {@link CompositeBehavior} here without consulting {@link dev.agenor.core.BehaviorType},
     * so the hint is authoritative rather than a second opinion.
     *
     * @throws IllegalStateException if hint is CYCLIC but {@code getInterval()} returns null.
     */
    private void scheduleComposite(CompositeBehavior composite) {
        switch (composite.getSchedulingHint()) {
            case ONCE -> {
                log.debug("Scheduling composite behavior as one-shot: {}", composite.getBehaviorId());
                scheduleOneShot(composite);
            }
            case CYCLIC -> {
                if (composite.getInterval() == null) {
                    throw new IllegalStateException(
                            "Composite behavior '%s' has SchedulingHint.CYCLIC but getInterval() returned null. "
                                    .formatted(composite.getBehaviorId())
                                    + "Provide an interval via the constructor: "
                                    + "new SequentialBehavior(id, true, stepTimeout, Duration.ofSeconds(1))");
                }
                log.debug("Scheduling composite behavior as cyclic (interval={}): {}",
                        composite.getInterval(), composite.getBehaviorId());
                scheduleCyclic(composite);
            }
            case ON_DEMAND -> log.debug("On-demand composite registered (not auto-scheduled): {}",
                    composite.getBehaviorId());
        }
    }

    @Override
    public boolean cancel(String behaviorId) {
        ScheduledFuture<?> future = scheduledBehaviors.remove(behaviorId);
        if (future != null) {
            boolean cancelled = future.cancel(false);
            log.debug("Cancelled behavior: {} (success: {})", behaviorId, cancelled);
            return cancelled;
        }
        return false;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public CompletableFuture<Void> start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting behavior scheduler");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping behavior scheduler");

            return CompletableFuture.runAsync(() -> {
                // Cancel all scheduled behaviors
                scheduledBehaviors.values().forEach(future -> future.cancel(false));
                scheduledBehaviors.clear();

                // Shutdown scheduler
                scheduler.shutdown();
            });
        }

        return CompletableFuture.completedFuture(null);
    }

    private void scheduleOneShot(Behavior behavior) {
        long delay = initialDelayMillis(behavior);
        ScheduledFuture<?> future = scheduler.schedule(() -> executeBehavior(behavior), delay,
            java.util.concurrent.TimeUnit.MILLISECONDS);

        scheduledBehaviors.put(behavior.getBehaviorId(), future);
        log.debug("Scheduled one-shot behavior: {} (initial delay {}ms)",
            behavior.getBehaviorId(), delay);
    }

    /**
     * The delay before a behavior's first execution, in milliseconds.
     *
     * <p>A null or negative {@link Behavior#getInitialDelay()} means "start now", which is what
     * every behavior that does not declare one gets.
     */
    private long initialDelayMillis(Behavior behavior) {
        Duration delay = behavior.getInitialDelay();
        return delay == null || delay.isNegative() ? 0L : delay.toMillis();
    }

    private void scheduleCyclic(Behavior behavior) {
        Duration interval = behavior.getInterval();
        if (interval == null) {
            log.error("Cyclic behavior {} has no interval specified", behavior.getBehaviorId());
            return;
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
            () -> {
                if (behavior.isActive()) {
                    try {
                        executeBehavior(behavior);
                    } catch (Throwable t) {
                        // Never let exceptions bubble to the scheduler
                        log.error("Scheduled runner failed for behavior: {}", behavior.getBehaviorId(), t);
                    }
                } else {
                    cancel(behavior.getBehaviorId());
                }
            },
            initialDelayMillis(behavior),
            interval.toMillis(),
            java.util.concurrent.TimeUnit.MILLISECONDS
        );

        scheduledBehaviors.put(behavior.getBehaviorId(), future);
        log.debug("Scheduled cyclic behavior: {} with interval: {}",
                 behavior.getBehaviorId(), interval);
    }

    private void executeBehavior(Behavior behavior) {
        Agent agent = behavior.getAgent();
        String agentId = agent != null ? agent.getAgentId() : "unknown";

        Span span = telemetry.spanBuilder("behavior.execute")
                .setAttribute("behavior.id",   behavior.getBehaviorId())
                .setAttribute("behavior.type", behavior.getType().name())
                .setAttribute("agent.id",      agentId)
                .startSpan();

        long startTime = System.currentTimeMillis();
        boolean success = true;
        String error = null;

        try (var scope = span.makeCurrent()) {
            behavior.execute().join();
            span.setStatus(SpanStatus.OK);
        } catch (Exception e) {
            success = false;
            error = e.getMessage();
            span.recordException(e).setStatus(SpanStatus.ERROR);
            log.error("Error executing behavior: {}", behavior.getBehaviorId(), e);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            span.setAttribute("behavior.duration_ms", durationMs).end();
            eventListener.onBehaviorExecuted(agentId, behavior.getBehaviorId(),
                    durationMs, success, error);
        }
    }
}
