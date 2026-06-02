package dev.agenor.adapters.persistence.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.hitl.ApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Listens for cross-node HITL decisions via Postgres {@code LISTEN/NOTIFY}.
 *
 * <p>Opens a dedicated non-pooled connection and listens on channel {@code agenor_hitl}.
 * When a {@code NOTIFY} arrives with payload {@code <requestId>:<decisionJson>}, this
 * listener looks up the local future map and completes the future if present.
 *
 * <p>Uses reflection to invoke {@code PGConnection.getNotifications(int)} to avoid a
 * compile-time dependency on the Postgres driver (which is in {@code runtime} scope).
 *
 * <p>This class is optional and activated only when the JDBC URL contains
 * {@code postgresql}. Non-Postgres deployments fall back to polling via
 * {@link JdbcApprovalGate#getPendingRequests()}.
 *
 * @since 0.23.0
 */
public final class PostgresNotificationListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresNotificationListener.class);
    private static final String CHANNEL = "agenor_hitl";

    private final DataSource dataSource;
    private final Map<String, CompletableFuture<ApprovalDecision>> localFutures;
    private final ObjectMapper mapper;

    private volatile boolean running = false;
    private volatile Connection listenConnection;

    /**
     * Creates a listener that will complete futures in {@code localFutures} when a
     * Postgres {@code NOTIFY agenor_hitl} message arrives.
     *
     * @param dataSource   data source used to open the dedicated listen connection
     * @param localFutures mutable map of in-flight futures (keyed by request ID), shared with
     *                     {@link JdbcApprovalGate}
     * @param mapper       Jackson mapper used to deserialise the decision JSON payload
     */
    public PostgresNotificationListener(DataSource dataSource,
                                        Map<String, CompletableFuture<ApprovalDecision>> localFutures,
                                        ObjectMapper mapper) {
        this.dataSource   = dataSource;
        this.localFutures = localFutures;
        this.mapper       = mapper;
    }

    /** Starts the listener loop on a virtual thread. */
    public void start() {
        running = true;
        Thread.ofVirtual().name("hitl-pg-notify-listener").start(this::listenLoop);
        log.info("PostgresNotificationListener started on channel '{}'", CHANNEL);
    }

    @Override
    public void close() {
        running = false;
        try {
            var conn = listenConnection;
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.debug("Error closing Postgres notification connection", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void listenLoop() {
        while (running) {
            try {
                listenConnection = dataSource.getConnection();
                try (var stmt = listenConnection.createStatement()) {
                    stmt.execute("LISTEN " + CHANNEL);
                }
                log.debug("Listening on Postgres channel '{}'", CHANNEL);

                Method getNotifications = resolveGetNotifications(listenConnection);
                if (getNotifications == null) {
                    log.warn("PGConnection.getNotifications not available — listener disabled");
                    return;
                }

                while (running) {
                    Object[] notifications = invokeGetNotifications(getNotifications, listenConnection, 2000);
                    if (notifications != null) {
                        for (Object n : notifications) {
                            handleNotification(getParameter(n));
                        }
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Postgres notification listener error — reconnecting in 5s", e);
                    try { Thread.sleep(5_000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                try {
                    var conn = listenConnection;
                    if (conn != null) conn.close();
                } catch (SQLException ignored) {}
            }
        }
    }

    private void handleNotification(String payload) {
        if (payload == null || payload.isBlank()) return;
        int sep = payload.indexOf(':');
        if (sep < 0) return;
        String requestId = payload.substring(0, sep);
        String decisionJson = payload.substring(sep + 1);

        var future = localFutures.get(requestId);
        if (future == null) return;

        ApprovalDecision decision = parseDecision(decisionJson);
        if (decision != null) {
            future.complete(decision);
            log.debug("Completed local future via Postgres NOTIFY: requestId={}", requestId);
        }
    }

    private ApprovalDecision parseDecision(String json) {
        try {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) mapper.readValue(json, Map.class);
            String type = (String) map.getOrDefault("type", "");
            return switch (type) {
                case JdbcApprovalGate.STATUS_APPROVED -> new ApprovalDecision.Approved();
                case JdbcApprovalGate.STATUS_REJECTED -> {
                    String reason = (String) map.getOrDefault("data", "");
                    yield new ApprovalDecision.Rejected(reason);
                }
                case JdbcApprovalGate.STATUS_MODIFIED -> {
                    Object newPayload = map.getOrDefault("data", "");
                    yield new ApprovalDecision.Modified(newPayload);
                }
                default -> {
                    log.warn("Unknown decision type in Postgres NOTIFY payload: {}", type);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to parse decision from Postgres NOTIFY payload: {}", json, e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers (avoid compile-time dep on org.postgresql)
    // -------------------------------------------------------------------------

    private static Method resolveGetNotifications(Connection conn) {
        try {
            Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
            Object pgConn = conn.unwrap(pgConnClass);
            return pgConn.getClass().getMethod("getNotifications", int.class);
        } catch (Exception e) {
            log.debug("Could not resolve PGConnection.getNotifications", e);
            return null;
        }
    }

    private static Object[] invokeGetNotifications(Method method, Connection conn, int timeoutMs)
            throws SQLException {
        try {
            Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
            Object pgConn = conn.unwrap(pgConnClass);
            return (Object[]) method.invoke(pgConn, timeoutMs);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sq) throw sq;
            throw new RuntimeException("getNotifications failed", cause);
        } catch (Exception e) {
            throw new RuntimeException("getNotifications invocation failed", e);
        }
    }

    private static String getParameter(Object notification) {
        try {
            return (String) notification.getClass().getMethod("getParameter").invoke(notification);
        } catch (Exception e) {
            return null;
        }
    }
}
