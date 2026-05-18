package dev.jentic.adapters.persistence.directory;

import dev.jentic.adapters.persistence.JdbcHelper;
import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentQuery;
import dev.jentic.core.Page;
import dev.jentic.core.PageRequest;
import dev.jentic.core.directory.AgentDiscovery;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.SpanStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JDBC implementation of {@link AgentDiscovery}.
 *
 * @since 0.22.0
 */
public class JdbcAgentDiscovery implements AgentDiscovery {

    private static final String SELECT_BY_ID =
            "SELECT a.*, c.capability FROM jentic_agents a " +
            "LEFT JOIN jentic_agent_capabilities c ON a.agent_id = c.agent_id " +
            "WHERE a.agent_id = ?";

    private static final String SELECT_BY_CAPABILITY =
            "SELECT a.*, c.capability FROM jentic_agents a " +
            "JOIN jentic_agent_capabilities c ON a.agent_id = c.agent_id " +
            "WHERE a.agent_id IN (" +
            "  SELECT agent_id FROM jentic_agent_capabilities WHERE capability = ?" +
            ")";

    private static final String SELECT_BY_TYPE =
            "SELECT a.*, c.capability FROM jentic_agents a " +
            "LEFT JOIN jentic_agent_capabilities c ON a.agent_id = c.agent_id " +
            "WHERE a.agent_type = ?";

    private final JdbcHelper helper;
    private final JenticTelemetry telemetry;

    public JdbcAgentDiscovery(JdbcHelper helper) {
        this(helper, JenticTelemetry.noop());
    }

    /**
     * @param helper    JDBC helper; must not be null
     * @param telemetry telemetry for {@code directory.find} spans; null treated as noop
     */
    public JdbcAgentDiscovery(JdbcHelper helper, JenticTelemetry telemetry) {
        this.helper = Objects.requireNonNull(helper, "helper must not be null");
        this.telemetry = telemetry != null ? telemetry : JenticTelemetry.noop();
    }

    @Override
    public CompletableFuture<Optional<AgentDescriptor>> findById(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        var span = telemetry.spanBuilder("directory.find")
                .setAttribute("directory.find.type", "by_id")
                .startSpan();
        return helper.<Optional<AgentDescriptor>>query(conn -> {
            var rows = helper.queryList(conn, SELECT_BY_ID, List.of(agentId),
                    rs -> new Row(helper.mapDescriptor(rs), rs.getString("capability")));
            return mergeRows(rows).stream().findFirst();
        }).whenComplete((result, ex) -> {
            if (ex != null) {
                span.recordException(ex).setStatus(SpanStatus.ERROR);
            } else {
                span.setAttribute("directory.find.result_count", result.isPresent() ? 1L : 0L)
                        .setStatus(SpanStatus.OK);
            }
            span.end();
        });
    }

    @Override
    public CompletableFuture<List<AgentDescriptor>> findByCapability(String capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        var span = telemetry.spanBuilder("directory.find")
                .setAttribute("directory.find.type", "by_capability")
                .startSpan();
        return helper.<List<AgentDescriptor>>query(conn -> {
            var rows = helper.queryList(conn, SELECT_BY_CAPABILITY, List.of(capability),
                    rs -> new Row(helper.mapDescriptor(rs), rs.getString("capability")));
            return mergeRows(rows);
        }).whenComplete((result, ex) -> {
            if (ex != null) {
                span.recordException(ex).setStatus(SpanStatus.ERROR);
            } else {
                span.setAttribute("directory.find.result_count", (long) result.size())
                        .setStatus(SpanStatus.OK);
            }
            span.end();
        });
    }

    @Override
    public CompletableFuture<List<AgentDescriptor>> findByType(String agentType) {
        Objects.requireNonNull(agentType, "agentType must not be null");
        var span = telemetry.spanBuilder("directory.find")
                .setAttribute("directory.find.type", "by_type")
                .startSpan();
        return helper.<List<AgentDescriptor>>query(conn -> {
            var rows = helper.queryList(conn, SELECT_BY_TYPE, List.of(agentType),
                    rs -> new Row(helper.mapDescriptor(rs), rs.getString("capability")));
            return mergeRows(rows);
        }).whenComplete((result, ex) -> {
            if (ex != null) {
                span.recordException(ex).setStatus(SpanStatus.ERROR);
            } else {
                span.setAttribute("directory.find.result_count", (long) result.size())
                        .setStatus(SpanStatus.OK);
            }
            span.end();
        });
    }

    @Override
    public CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query, PageRequest request) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(request, "request must not be null");
        var span = telemetry.spanBuilder("directory.find")
                .setAttribute("directory.find.type", "query")
                .startSpan();
        return helper.<Page<AgentDescriptor>>query(conn -> executePagedQuery(conn, query, request))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        span.recordException(ex).setStatus(SpanStatus.ERROR);
                    } else {
                        span.setAttribute("directory.find.result_count", (long) result.content().size())
                                .setStatus(SpanStatus.OK);
                    }
                    span.end();
                });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Page<AgentDescriptor> executePagedQuery(Connection conn, AgentQuery query, PageRequest request)
            throws SQLException {
        var whereParams = new ArrayList<>();
        var where = buildWhereClause(query, whereParams);

        var countSql = "SELECT COUNT(DISTINCT a.agent_id) FROM jentic_agents a" + where;
        var total = helper.queryOne(conn, countSql, whereParams, rs -> rs.getLong(1));

        if (total == null || total == 0) return Page.empty(request);

        var pageSql =
                "SELECT a.*, c.capability FROM jentic_agents a " +
                "LEFT JOIN jentic_agent_capabilities c ON a.agent_id = c.agent_id" +
                where +
                " ORDER BY a.agent_id LIMIT ? OFFSET ?";

        var pageParams = new ArrayList<>(whereParams);
        pageParams.add(request.size());
        pageParams.add((int) request.offset());

        var rows = helper.queryList(conn, pageSql, pageParams,
                rs -> new Row(helper.mapDescriptor(rs), rs.getString("capability")));

        return new Page<>(mergeRows(rows), total, request.page(), request.size());
    }

    private String buildWhereClause(AgentQuery query, List<Object> params) {
        var conditions = new ArrayList<String>();

        if (query.agentType() != null) {
            conditions.add("a.agent_type = ?");
            params.add(query.agentType());
        }
        if (query.status() != null) {
            conditions.add("a.status = ?");
            params.add(query.status().name());
        }
        var caps = query.requiredCapabilities();
        if (caps != null && !caps.isEmpty()) {
            var placeholders = "?,".repeat(caps.size());
            conditions.add(
                    "a.agent_id IN (SELECT agent_id FROM jentic_agent_capabilities " +
                    "WHERE capability IN (" + placeholders.substring(0, placeholders.length() - 1) + ") " +
                    "GROUP BY agent_id HAVING COUNT(DISTINCT capability) = ?)");
            params.addAll(caps);
            params.add(caps.size());
        }

        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * Collapses JOIN rows (one per capability) into descriptors with full capability sets.
     */
    private List<AgentDescriptor> mergeRows(List<Row> rows) {
        var byId = new java.util.LinkedHashMap<String, AgentDescriptorAccumulator>();
        for (var row : rows) {
            var acc = byId.computeIfAbsent(row.descriptor.agentId(),
                    id -> new AgentDescriptorAccumulator(row.descriptor));
            if (row.capability != null) acc.capabilities.add(row.capability);
        }
        return byId.values().stream()
                .map(acc -> AgentDescriptor.builder(acc.base.agentId())
                        .agentName(acc.base.agentName())
                        .agentType(acc.base.agentType())
                        .status(acc.base.status())
                        .capabilities(acc.capabilities)
                        .metadata(acc.base.metadata())
                        .endpoint(acc.base.endpoint())
                        .registeredAt(acc.base.registeredAt())
                        .lastSeen(acc.base.lastSeen())
                        .build())
                .toList();
    }

    private record Row(AgentDescriptor descriptor, String capability) {}

    private static final class AgentDescriptorAccumulator {
        final AgentDescriptor base;
        final java.util.Set<String> capabilities = new HashSet<>();

        AgentDescriptorAccumulator(AgentDescriptor base) {
            this.base = base;
        }
    }
}
