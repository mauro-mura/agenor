package dev.jentic.adapters.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.AgentStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Internal JDBC utility shared across all persistence adapters.
 * Not public API — not to be referenced from outside this module.
 *
 * @since 0.22.0
 */
public final class JdbcHelper {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    final DataSource dataSource;
    final Executor executor;
    final ObjectMapper mapper;

    public JdbcHelper(DataSource dataSource) {
        this.dataSource = dataSource;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.mapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Transaction support
    // -------------------------------------------------------------------------

    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T execute(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    public interface TransactionCallback {
        void execute(Connection conn) throws SQLException;
    }

    public <T> CompletableFuture<T> query(ConnectionCallback<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (var conn = dataSource.getConnection()) {
                return work.execute(conn);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("JDBC query failed", e);
            }
        }, executor);
    }

    public CompletableFuture<Void> mutate(TransactionCallback work) {
        return CompletableFuture.runAsync(() -> inTransaction(work), executor);
    }

    public void inTransaction(TransactionCallback callback) {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                callback.execute(conn);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("JDBC operation failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public <T> List<T> queryList(Connection conn, String sql, List<Object> params, RowMapper<T> mapper)
            throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (var rs = ps.executeQuery()) {
                var result = new ArrayList<T>();
                while (rs.next()) result.add(mapper.map(rs));
                return result;
            }
        }
    }

    public <T> T queryOne(Connection conn, String sql, List<Object> params, RowMapper<T> mapper)
            throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        }
    }

    public int update(Connection conn, String sql, List<Object> params) throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            var value = params.get(i);
            if (value instanceof Instant instant) {
                ps.setTimestamp(i + 1, Timestamp.from(instant));
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Unique-constraint detection (portable across Postgres, MySQL, H2)
    // -------------------------------------------------------------------------

    public boolean isUniqueViolation(SQLException e) {
        var state = e.getSQLState();
        // ANSI SQLState 23xxx = integrity constraint violation
        return state != null && state.startsWith("23");
    }

    // -------------------------------------------------------------------------
    // ResultSet mappers
    // -------------------------------------------------------------------------

    public AgentDescriptor mapDescriptor(ResultSet rs) throws SQLException {
        var agentId = rs.getString("agent_id");
        var capabilities = new HashSet<String>();
        // capabilities are loaded separately via JOIN query when needed
        return AgentDescriptor.builder(agentId)
                .agentName(rs.getString("agent_name"))
                .agentType(rs.getString("agent_type"))
                .status(AgentStatus.valueOf(rs.getString("status")))
                .capabilities(capabilities)
                .metadata(parseJson(rs.getString("metadata")))
                .endpoint(mapEndpoint(rs))
                .registeredAt(toInstant(rs.getTimestamp("registered_at")))
                .lastSeen(toInstant(rs.getTimestamp("last_seen")))
                .build();
    }

    public AgentEndpoint mapEndpoint(ResultSet rs) throws SQLException {
        var transportType = rs.getString("endpoint_transport_type");
        if (transportType == null || transportType.isBlank()) return null;
        var nodeId = rs.getString("node_id");
        var props = parseJson(rs.getString("endpoint_props"));
        return new AgentEndpoint(nodeId, transportType, props);
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    public String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize map to JSON", e);
        }
    }

    public Map<String, String> parseJson(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
