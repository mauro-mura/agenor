package dev.agenor.runtime.directory;

import dev.agenor.core.directory.AgentPresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sends periodic heartbeats for the agents a runtime is currently running (ADR-028 D-3).
 *
 * <p>{@link AgentPresence#heartbeat} says an agent is still alive, but nothing in the
 * framework calls it on its own. Without a driver a bounded-window backend reports
 * {@link dev.agenor.core.AgentStatus#UNKNOWN} for every agent one staleness window after
 * start-up, so a presence backend that expires entries needs this class — or an unbounded
 * window — to tell the truth.
 *
 * <p><strong>Opt-in.</strong> {@link dev.agenor.runtime.AgenorRuntime} builds a driver only
 * when an interval is configured. Heartbeats are writes, and turning them on for every
 * existing deployment without being asked is exactly the write volume ADR-023 objected to.
 * The recommended cadence is 15–30 seconds, roughly a third of the backend's window.
 *
 * <p><strong>One daemon thread, and it only submits.</strong> The driver owns a single
 * scheduled thread for the whole runtime rather than a timer per agent, and deliberately not
 * the behavior scheduler, whose small platform pool is shared by every behavior of every
 * agent. {@code heartbeat} returns a future the thread never waits on, so a slow backend
 * delays the next beat but never blocks the runtime.
 *
 * @since 0.26.0
 */
public final class AgentHeartbeatDriver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentHeartbeatDriver.class);

    private final AgentPresence presence;
    private final Supplier<Collection<String>> agentIds;
    private final Duration interval;

    private ScheduledExecutorService scheduler;

    /**
     * Creates a driver. Nothing is scheduled until {@link #start()} is called.
     *
     * @param presence the presence capability to heartbeat against, never null
     * @param agentIds supplies the ids to beat for on each tick — evaluated per tick, not
     *                 captured once, so agents starting or stopping are picked up; never null
     * @param interval the cadence, must be positive
     * @throws NullPointerException     if {@code presence}, {@code agentIds} or
     *                                  {@code interval} is null
     * @throws IllegalArgumentException if {@code interval} is zero or negative
     */
    public AgentHeartbeatDriver(AgentPresence presence,
                                Supplier<Collection<String>> agentIds,
                                Duration interval) {
        this.presence = Objects.requireNonNull(presence, "presence");
        this.agentIds = Objects.requireNonNull(agentIds, "agentIds");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("heartbeat interval must be positive: " + interval);
        }
    }

    /**
     * Starts beating. The first beat happens one interval from now, because an agent has
     * just registered and its {@code last_seen} is already current. Calling this on a
     * started driver does nothing.
     */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "agenor-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        scheduler.scheduleAtFixedRate(this::beat, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Agent heartbeat driver started, interval {}", interval);
    }

    /**
     * Stops beating and releases the thread. Calling this on a stopped driver does nothing.
     */
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Heartbeat thread did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        log.debug("Agent heartbeat driver stopped");
    }

    /**
     * Whether this driver is currently scheduled.
     *
     * @return true between a {@link #start()} and the matching {@link #stop()}
     */
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    /**
     * The configured cadence.
     *
     * @return the interval between beats, never null
     */
    public Duration interval() {
        return interval;
    }

    /** Equivalent to {@link #stop()}, so the driver can be used in try-with-resources. */
    @Override
    public void close() {
        stop();
    }

    /**
     * One round of heartbeats.
     *
     * <p>Nothing escapes this method. A task that throws is silently cancelled by
     * {@code scheduleAtFixedRate} and never runs again, which would look exactly like a
     * backend that stopped answering — so every failure is caught and logged, and the next
     * tick tries again.
     */
    void beat() {
        try {
            for (String agentId : agentIds.get()) {
                try {
                    presence.heartbeat(agentId).exceptionally(error -> {
                        log.warn("Heartbeat failed for agent {}: {}", agentId, error.toString());
                        return null;
                    });
                } catch (RuntimeException e) {
                    log.warn("Heartbeat could not be sent for agent {}: {}", agentId, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Heartbeat round failed: {}", e.toString());
        }
    }
}
