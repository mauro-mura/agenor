package dev.jentic.runtime.scheduler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.jentic.core.Agent;
import dev.jentic.core.composite.SchedulingHint;
import dev.jentic.core.console.ConsoleEventListener;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.Span;
import dev.jentic.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorScheduler;

/**
 * Simple implementation of BehaviorScheduler using ScheduledExecutorService.
 * Uses virtual threads for behavior execution.
 */
public class SimpleBehaviorScheduler implements BehaviorScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleBehaviorScheduler.class);
    
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledBehaviors = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ConsoleEventListener eventListener = ConsoleEventListener.noOp();
    private final JenticTelemetry telemetry;

    public SimpleBehaviorScheduler() {
        this(4, JenticTelemetry.noop());
    }

    public SimpleBehaviorScheduler(int threadPoolSize) {
        this(threadPoolSize, JenticTelemetry.noop());
    }

    public SimpleBehaviorScheduler(int threadPoolSize, JenticTelemetry telemetry) {
        this.scheduler  = new ScheduledThreadPoolExecutor(threadPoolSize);
        this.telemetry  = telemetry != null ? telemetry : JenticTelemetry.noop();
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

            switch (behavior.getType()) {
                case ONE_SHOT  -> scheduleOneShot(behavior);
                case CYCLIC    -> scheduleCyclic(behavior);
                case WAKER     -> scheduleWaker(behavior);
                case EVENT_DRIVEN -> {
                    // Event-driven behaviors respond to events; no scheduling needed.
                    log.debug("Event-driven behavior registered: {}", behavior.getBehaviorId());
                }
                // BATCH needs continuous polling to drain its internal queue.
                case BATCH -> scheduleCustom(behavior);
                // CONDITIONAL and THROTTLED carry their own interval inside execute().
                case CONDITIONAL, THROTTLED, CUSTOM -> scheduleCustom(behavior);
                // Workflow composites (SEQUENTIAL, PARALLEL) declare their scheduling intent
                // via SchedulingHint; delegate to scheduleComposite() so that addBehavior()
                // is sufficient — no manual execute() call required.
                case SEQUENTIAL, PARALLEL -> scheduleComposite(behavior);
                // Control-flow composites are on-demand: they wrap operations triggered
                // externally and must NOT be auto-scheduled.
                case RETRY, CIRCUIT_BREAKER, PIPELINE, FSM -> {
                    log.debug("On-demand composite registered (not auto-scheduled): {}",
                            behavior.getBehaviorId());
                }
                // SCHEDULED manages its own internal ScheduledExecutorService but needs
                // execute() called once to start the cron loop.
                case SCHEDULED -> scheduleOneShot(behavior);
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
     * @throws IllegalStateException if hint is CYCLIC but {@code getInterval()} returns null.
     */
    private void scheduleComposite(Behavior behavior) {
        if (!(behavior instanceof dev.jentic.core.composite.CompositeBehavior composite)) {
            // Fallback: if somehow a non-composite returns SEQUENTIAL/PARALLEL type, treat as one-shot.
            scheduleOneShot(behavior);
            return;
        }

        switch (composite.getSchedulingHint()) {
            case ONCE -> {
                log.debug("Scheduling composite behavior as one-shot: {}", behavior.getBehaviorId());
                scheduleOneShot(behavior);
            }
            case CYCLIC -> {
                if (behavior.getInterval() == null) {
                    throw new IllegalStateException(
                            "Composite behavior '%s' has SchedulingHint.CYCLIC but getInterval() returned null. "
                                    .formatted(behavior.getBehaviorId())
                                    + "Provide an interval via the constructor: "
                                    + "new SequentialBehavior(id, true, stepTimeout, Duration.ofSeconds(1))");
                }
                log.debug("Scheduling composite behavior as cyclic (interval={}): {}",
                        behavior.getInterval(), behavior.getBehaviorId());
                scheduleCyclic(behavior);
            }
            case ON_DEMAND -> log.debug("On-demand composite registered (not auto-scheduled): {}",
                    behavior.getBehaviorId());
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
        ScheduledFuture<?> future = scheduler.schedule(() -> executeBehavior(behavior), 0, 
            java.util.concurrent.TimeUnit.MILLISECONDS);
        
        scheduledBehaviors.put(behavior.getBehaviorId(), future);
        log.debug("Scheduled one-shot behavior: {}", behavior.getBehaviorId());
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
            0,
            interval.toMillis(),
            java.util.concurrent.TimeUnit.MILLISECONDS
        );
        
        scheduledBehaviors.put(behavior.getBehaviorId(), future);
        log.debug("Scheduled cyclic behavior: {} with interval: {}", 
                 behavior.getBehaviorId(), interval);
    }
    
    private void scheduleWaker(Behavior behavior) {
        // For MVP, treat waker behaviors like one-shot
        // Future versions can add more sophisticated wake conditions
        scheduleOneShot(behavior);
    }
    
    private void scheduleCustom(Behavior behavior) {
        // For custom behaviors, delegate to the behavior itself
        // This allows behaviors to define their own scheduling logic
        CompletableFuture.runAsync(() -> {
            while (behavior.isActive()) {
                executeBehavior(behavior);
                
                // Default interval for custom behaviors
                try {
                    Thread.sleep(behavior.getInterval() != null ? 
                        behavior.getInterval().toMillis() : 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        log.debug("Scheduled custom behavior: {}", behavior.getBehaviorId());
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

        try {
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