package dev.agenor.adapters.persistence.directory;

import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * JDBC implementation of {@link AgentPresence} (ADR-028 Phase A).
 *
 * <p>Reuses the {@code agenor_agents} table the rest of the JDBC directory already writes:
 * {@link #heartbeat} touches only {@code last_seen}, which is lighter than
 * {@link JdbcAgentRegistry}'s status update, and {@link #getStatus} reads {@code status}
 * alongside {@code last_seen}. No new table, column or migration.
 *
 * <p><strong>Staleness is evaluated at read time.</strong> A relational store has no key
 * expiry, so {@link #getStatus} compares {@code now - last_seen} against the configured
 * window and answers {@link AgentStatus#UNKNOWN} when it is exceeded. Detection latency is
 * therefore coarse — tens of seconds to a couple of minutes at the recommended cadence —
 * and the answer is only as trustworthy as the clock skew between the writing and reading
 * nodes allows. The default window is deliberately wide enough to absorb ordinary NTP drift.
 *
 * <p><strong>Something has to send the heartbeats.</strong> Nothing in the framework calls
 * {@link #heartbeat} unless a heartbeat interval is configured, and with a bounded window a
 * store nobody heartbeats reports {@code UNKNOWN} for every agent one window after start-up.
 * Either enable the runtime's heartbeat driver, or construct this class with
 * {@link #UNBOUNDED_STALENESS_WINDOW}, which never expires a status and matches the
 * in-memory backend's behaviour.
 *
 * @since 0.26.0
 * @see JdbcAgentDirectory#presence()
 */
public class JdbcAgentPresence implements AgentPresence {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentPresence.class);

    /**
     * Default staleness window: three missed beats at the recommended 30-second cadence.
     */
    public static final Duration DEFAULT_STALENESS_WINDOW = Duration.ofSeconds(90);

    /**
     * Staleness window meaning "never expire a status", the unbounded value
     * {@link AgentPresence#getStatus} permits. Use it when no heartbeat driver is running,
     * so {@link #getStatus} simply reports what the registry last wrote.
     */
    public static final Duration UNBOUNDED_STALENESS_WINDOW = ChronoUnit.FOREVER.getDuration();

    private static final String TOUCH_LAST_SEEN =
            "UPDATE agenor_agents SET last_seen = ? WHERE agent_id = ?";

    private static final String SELECT_STATUS =
            "SELECT status, last_seen FROM agenor_agents WHERE agent_id = ?";

    private final JdbcHelper helper;
    private final AgenorTelemetry telemetry;
    private final Duration stalenessWindow;

    /**
     * Creates a presence backend with the default staleness window and noop telemetry.
     *
     * @param helper JDBC helper; must not be null
     */
    public JdbcAgentPresence(JdbcHelper helper) {
        this(helper, AgenorTelemetry.noop(), DEFAULT_STALENESS_WINDOW);
    }

    /**
     * Creates a presence backend.
     *
     * @param helper          JDBC helper; must not be null
     * @param telemetry       telemetry for {@code directory.*} spans; null treated as noop
     * @param stalenessWindow how long an agent may go unseen before {@link #getStatus}
     *                        answers {@link AgentStatus#UNKNOWN}; must not be null or
     *                        negative. Pass {@link #UNBOUNDED_STALENESS_WINDOW} to disable
     *                        expiry
     * @throws NullPointerException     if helper or stalenessWindow is null
     * @throws IllegalArgumentException if stalenessWindow is negative
     */
    public JdbcAgentPresence(JdbcHelper helper, AgenorTelemetry telemetry,
                             Duration stalenessWindow) {
        this.helper = Objects.requireNonNull(helper, "helper must not be null");
        this.telemetry = telemetry != null ? telemetry : AgenorTelemetry.noop();
        this.stalenessWindow =
                Objects.requireNonNull(stalenessWindow, "stalenessWindow must not be null");
        if (stalenessWindow.isNegative()) {
            throw new IllegalArgumentException("stalenessWindow must not be negative");
        }
    }

    /**
     * Returns the window after which {@link #getStatus} reports {@link AgentStatus#UNKNOWN}.
     *
     * @return the staleness window; never null
     */
    public Duration stalenessWindow() {
        return stalenessWindow;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Issues a single-row {@code UPDATE} against the primary key. A heartbeat for an
     * agent that is not registered updates no rows and is ignored, per the contract.
     */
    @Override
    public CompletableFuture<Void> heartbeat(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        var span = telemetry.spanBuilder("directory.heartbeat")
                .setAttribute("agent.id", agentId)
                .startSpan();
        return helper.mutate(conn -> {
            var rows = helper.update(conn, TOUCH_LAST_SEEN,
                    List.of(Timestamp.from(Instant.now()), agentId));
            if (rows == 0) {
                log.debug("Heartbeat for unregistered agent {} — ignored", agentId);
            }
        }).whenComplete((v, ex) -> {
            if (ex != null) span.recordException(ex).setStatus(SpanStatus.ERROR);
            else span.setStatus(SpanStatus.OK);
            span.end();
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answers {@link AgentStatus#UNKNOWN} when no row exists, and also when the row's
     * {@code last_seen} is older than this instance's staleness window.
     */
    @Override
    public CompletableFuture<AgentStatus> getStatus(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        var span = telemetry.spanBuilder("directory.get_status")
                .setAttribute("agent.id", agentId)
                .startSpan();
        return helper.<AgentStatus>query(conn -> {
            var row = helper.queryOne(conn, SELECT_STATUS, List.of(agentId), rs -> {
                var lastSeen = rs.getTimestamp("last_seen");
                return new StatusRow(AgentStatus.valueOf(rs.getString("status")),
                        lastSeen != null ? lastSeen.toInstant() : null);
            });
            if (row == null) {
                return AgentStatus.UNKNOWN;
            }
            return isStale(row.lastSeen()) ? AgentStatus.UNKNOWN : row.status();
        }).whenComplete((status, ex) -> {
            if (ex != null) span.recordException(ex).setStatus(SpanStatus.ERROR);
            else span.setAttribute("agent.status", status.name()).setStatus(SpanStatus.OK);
            span.end();
        });
    }

    /**
     * Decides whether a {@code last_seen} value is too old to vouch for.
     *
     * <p>Compares elapsed time against the window rather than adding the window to
     * {@code lastSeen}, so {@link #UNBOUNDED_STALENESS_WINDOW} needs no special case and
     * cannot overflow. A {@code lastSeen} in the future — clock skew between nodes — yields
     * a negative elapsed time and is therefore not stale.
     *
     * @param lastSeen the stored timestamp, may be null
     * @return true if the agent has not been seen within the window
     */
    private boolean isStale(Instant lastSeen) {
        // The column is NOT NULL, so this branch is defensive. Treating a missing timestamp
        // as stale errs toward UNKNOWN rather than toward a liveness claim the row does not
        // actually support.
        if (lastSeen == null) {
            return true;
        }
        return Duration.between(lastSeen, Instant.now()).compareTo(stalenessWindow) > 0;
    }

    /** One row of the presence query. */
    private record StatusRow(AgentStatus status, Instant lastSeen) {
    }
}
