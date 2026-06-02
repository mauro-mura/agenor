package dev.agenor.adapters.persistence.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.hitl.ApprovalTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JDBC-backed implementation of {@link ApprovalGate}.
 *
 * <p>Each approval request is persisted in {@code jentic_hitl_requests}. A local
 * {@link CompletableFuture} registry (in-memory, per JVM) is keyed by request ID so the
 * calling virtual thread can park cheaply on {@code future.join()} (ADR-001).
 *
 * <p>When a decision is submitted, the row is updated and the local future is completed.
 * On Postgres, a {@code NOTIFY jentic_hitl} message is emitted so other JVMs listening on this
 * channel can complete their local futures via {@link PostgresNotificationListener}.
 *
 * <p><b>Restart recovery</b>: call {@link #recoverExpired()} at construction time to mark
 * any rows whose {@code expires_at} has passed as {@code EXPIRED}.
 *
 * <p><b>Single-JVM future constraint</b>: the {@link CompletableFuture} lives only in the
 * JVM that called {@link #requestApproval(ApprovalRequest)}. If that JVM crashes, the future is lost.
 * The DB row remains for audit. See ADR-024 for details.
 *
 * <p>Implements {@link AutoCloseable} — call {@link #close()} to shut down the timeout
 * scheduler and any active Postgres notification listener.
 *
 * @since 0.23.0
 */
public class JdbcApprovalGate implements ApprovalGate, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovalGate.class);

    static final String STATUS_PENDING  = "PENDING";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_REJECTED = "REJECTED";
    static final String STATUS_MODIFIED = "MODIFIED";
    static final String STATUS_EXPIRED  = "EXPIRED";

    private final JdbcHelper jdbc;
    final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final boolean postgresNotify;

    /** In-memory futures — keyed by requestId, live only in this JVM. */
    final Map<String, CompletableFuture<ApprovalDecision>> localFutures =
            new ConcurrentHashMap<>();

    private PostgresNotificationListener notificationListener;

    // -------------------------------------------------------------------------
    // Constructors / factories
    // -------------------------------------------------------------------------

    /**
     * Creates a gate backed by the given {@code DataSource}.
     *
     * <p>When {@code jdbcUrl} contains {@code "postgresql"}, Postgres-specific
     * cross-node notification is enabled. Pass {@code null} or any other URL to disable it.
     *
     * @param dataSource the JDBC data source; must not be null
     * @param jdbcUrl    the JDBC URL used to detect Postgres; may be null
     */
    public JdbcApprovalGate(DataSource dataSource, String jdbcUrl) {
        this(new JdbcHelper(dataSource), new ObjectMapper(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = Thread.ofVirtual().unstarted(r);
                    t.setName("hitl-timeout-scheduler");
                    t.setDaemon(true);
                    return t;
                }),
                jdbcUrl != null && jdbcUrl.contains("postgresql"),
                dataSource);
    }

    /** Package-private constructor for testing. */
    JdbcApprovalGate(JdbcHelper jdbc, ObjectMapper mapper, ScheduledExecutorService scheduler,
                     boolean postgresNotify, DataSource dataSource) {
        this.jdbc    = Objects.requireNonNull(jdbc);
        this.mapper  = Objects.requireNonNull(mapper);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.postgresNotify = postgresNotify;

        if (postgresNotify && dataSource != null) {
            notificationListener = new PostgresNotificationListener(dataSource, localFutures, mapper);
            notificationListener.start();
        }
    }

    // -------------------------------------------------------------------------
    // Startup recovery
    // -------------------------------------------------------------------------

    /**
     * Marks PENDING rows whose {@code expires_at} has passed as {@code EXPIRED}.
     * Call once after construction.
     */
    public void recoverExpired() {
        jdbc.inTransaction(conn -> {
            int updated = jdbc.update(conn,
                    "UPDATE jentic_hitl_requests SET status = ? WHERE status = ? AND expires_at <= ?",
                    List.of(STATUS_EXPIRED, STATUS_PENDING, Timestamp.from(Instant.now())));
            if (updated > 0) {
                log.info("HITL startup recovery: marked {} expired requests", updated);
            }
        });
    }

    // -------------------------------------------------------------------------
    // ApprovalGate
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");

        var future = new CompletableFuture<ApprovalDecision>();
        future.whenComplete((d, ex) -> localFutures.remove(request.requestId()));

        localFutures.put(request.requestId(), future);
        persistRequest(request);
        scheduleTimeout(request, future);

        log.debug("Approval requested: requestId={}, agentId={}, action={}, expiresAt={}",
                request.requestId(), request.agentId(), request.action(), request.expiresAt());
        return future;
    }

    @Override
    public void submit(String requestId, ApprovalDecision decision) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        var row = loadRow(requestId);
        if (row == null) {
            throw new IllegalArgumentException(
                    "No pending approval request found for requestId: " + requestId);
        }
        if (!STATUS_PENDING.equals(row.status())) {
            log.debug("Decision ignored — request already in status '{}': requestId={}",
                    row.status(), requestId);
            return;
        }

        persistDecision(requestId, decision);

        var future = localFutures.get(requestId);
        if (future != null) {
            future.complete(decision);
            log.debug("Approval decision completed local future: requestId={}", requestId);
        } else {
            log.debug("No local future for requestId={} (cross-node or already completed)", requestId);
        }

        if (postgresNotify) {
            emitNotify(requestId, decision);
        }
    }

    @Override
    public List<ApprovalRequest> getPendingRequests() {
        return jdbc.query(conn ->
                jdbc.queryList(conn,
                        "SELECT request_id, agent_id, action, payload, metadata, created_at, expires_at"
                        + " FROM jentic_hitl_requests WHERE status = ? AND expires_at > ?",
                        List.of(STATUS_PENDING, Timestamp.from(Instant.now())),
                        rs -> {
                            var metadata = parseMetadata(rs.getString("metadata"));
                            return new ApprovalRequest(
                                    rs.getString("request_id"),
                                    rs.getString("agent_id"),
                                    rs.getString("action"),
                                    rs.getString("payload"),
                                    rs.getTimestamp("expires_at").toInstant(),
                                    metadata
                            );
                        })
        ).join();
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (notificationListener != null) {
            notificationListener.close();
        }
    }

    // -------------------------------------------------------------------------
    // Internal — DB operations
    // -------------------------------------------------------------------------

    private void persistRequest(ApprovalRequest req) {
        var now = Timestamp.from(Instant.now());
        jdbc.inTransaction(conn ->
                jdbc.update(conn,
                        "INSERT INTO jentic_hitl_requests"
                        + " (request_id, agent_id, action, payload, metadata,"
                        + "  created_at, expires_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        List.of(
                                req.requestId(),
                                req.agentId(),
                                req.action(),
                                serializePayload(req.payload()),
                                serializeMetadata(req.metadata()),
                                now,
                                Timestamp.from(req.expiresAt()),
                                STATUS_PENDING
                        )));
    }

    private void persistDecision(String requestId, ApprovalDecision decision) {
        var now = Timestamp.from(Instant.now());
        String type;
        String data;
        if (decision instanceof ApprovalDecision.Approved) {
            type = STATUS_APPROVED;
            data = "";
        } else if (decision instanceof ApprovalDecision.Rejected r) {
            type = STATUS_REJECTED;
            data = r.reason();
        } else if (decision instanceof ApprovalDecision.Modified m) {
            type = STATUS_MODIFIED;
            data = serializePayload(m.newPayload());
        } else {
            throw new IllegalArgumentException("Unknown decision type: " + decision);
        }

        String finalType = type;
        String finalData = data;
        jdbc.inTransaction(conn ->
                jdbc.update(conn,
                        "UPDATE jentic_hitl_requests"
                        + " SET status = ?, decision_type = ?, decision_data = ?, decided_at = ?"
                        + " WHERE request_id = ? AND status = ?",
                        List.of(finalType, finalType, finalData, now, requestId, STATUS_PENDING)));
    }

    private record RowSummary(String requestId, String status) {}

    private RowSummary loadRow(String requestId) {
        return jdbc.query(conn ->
                jdbc.queryOne(conn,
                        "SELECT request_id, status FROM jentic_hitl_requests WHERE request_id = ?",
                        List.of(requestId),
                        rs -> new RowSummary(rs.getString("request_id"), rs.getString("status")))
        ).join();
    }

    // -------------------------------------------------------------------------
    // Timeout
    // -------------------------------------------------------------------------

    private void scheduleTimeout(ApprovalRequest request,
                                  CompletableFuture<ApprovalDecision> future) {
        long delayMs = Math.max(0L,
                Duration.between(Instant.now(), request.expiresAt()).toMillis());

        scheduler.schedule(() -> {
            boolean timedOut = future.completeExceptionally(new ApprovalTimeoutException(request));
            if (timedOut) {
                markExpired(request.requestId());
                log.warn("Approval request timed out: requestId={}, agentId={}, action={}",
                        request.requestId(), request.agentId(), request.action());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void markExpired(String requestId) {
        jdbc.inTransaction(conn ->
                jdbc.update(conn,
                        "UPDATE jentic_hitl_requests SET status = ?"
                        + " WHERE request_id = ? AND status = ?",
                        List.of(STATUS_EXPIRED, requestId, STATUS_PENDING)));
    }

    // -------------------------------------------------------------------------
    // Postgres NOTIFY
    // -------------------------------------------------------------------------

    private void emitNotify(String requestId, ApprovalDecision decision) {
        try {
            String type;
            String data;
            if (decision instanceof ApprovalDecision.Approved) {
                type = STATUS_APPROVED;
                data = "";
            } else if (decision instanceof ApprovalDecision.Rejected r) {
                type = STATUS_REJECTED;
                data = r.reason();
            } else if (decision instanceof ApprovalDecision.Modified m) {
                type = STATUS_MODIFIED;
                data = serializePayload(m.newPayload());
            } else {
                return;
            }
            String payload = requestId + ":" + mapper.writeValueAsString(
                    Map.of("type", type, "data", data));
            jdbc.inTransaction(conn -> {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("NOTIFY jentic_hitl, '" + payload.replace("'", "''") + "'");
                }
            });
        } catch (Exception e) {
            log.warn("Failed to emit Postgres NOTIFY for requestId={}", requestId, e);
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    String serializePayload(Object payload) {
        if (payload == null) return null;
        if (payload instanceof String s) return s;
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize HITL payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
        try {
            return (Map<String, Object>) mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize HITL metadata", e);
        }
    }
}
