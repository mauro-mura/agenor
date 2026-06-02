package dev.agenor.adapters.persistence.directory;

import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * JDBC implementation of {@link AgentRegistry}.
 *
 * <p>{@link #register} uses upsert semantics (insert-then-update on PK conflict) to support
 * agent restarts and node failover without requiring callers to distinguish first registration
 * from re-registration. See ADR-023 for the singleton-per-agent-id contract.
 *
 * @since 0.22.0
 */
public class JdbcAgentRegistry implements AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentRegistry.class);

    private static final String INSERT_AGENT =
            "INSERT INTO jentic_agents " +
            "(agent_id, agent_name, agent_type, status, node_id, " +
            " endpoint_transport_type, endpoint_props, metadata, registered_at, last_seen) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_AGENT =
            "UPDATE jentic_agents SET " +
            "agent_name = ?, agent_type = ?, status = ?, node_id = ?, " +
            "endpoint_transport_type = ?, endpoint_props = ?, metadata = ?, last_seen = ? " +
            "WHERE agent_id = ?";

    private static final String DELETE_AGENT =
            "DELETE FROM jentic_agents WHERE agent_id = ?";

    private static final String UPDATE_STATUS =
            "UPDATE jentic_agents SET status = ?, last_seen = ? WHERE agent_id = ?";

    private static final String DELETE_CAPABILITIES =
            "DELETE FROM jentic_agent_capabilities WHERE agent_id = ?";

    private static final String INSERT_CAPABILITY =
            "INSERT INTO jentic_agent_capabilities (agent_id, capability) VALUES (?, ?)";

    private final JdbcHelper helper;
    private final AgenorTelemetry telemetry;

    public JdbcAgentRegistry(JdbcHelper helper) {
        this(helper, AgenorTelemetry.noop());
    }

    /**
     * @param helper    JDBC helper; must not be null
     * @param telemetry telemetry for registry spans; null treated as noop
     */
    public JdbcAgentRegistry(JdbcHelper helper, AgenorTelemetry telemetry) {
        this.helper = Objects.requireNonNull(helper, "helper must not be null");
        this.telemetry = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    /**
     * Registers or updates an agent in the directory.
     *
     * <p>On first registration inserts a new row. On re-registration (same {@code agent_id},
     * e.g. after a restart) updates all mutable fields except {@code registered_at}.
     */
    @Override
    public CompletableFuture<Void> register(AgentDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        var span = telemetry.spanBuilder("directory.register")
                .setAttribute("agent.id", descriptor.agentId())
                .startSpan();
        return helper.mutate(conn -> {
            var now = Instant.now();
            var endpoint = descriptor.endpoint();
            var transportType = endpoint != null ? endpoint.transportType() : "local";
            var nodeId = endpoint != null ? endpoint.nodeId() : "";
            var endpointProps = helper.toJson(endpoint != null ? endpoint.transportProps() : null);
            var metadata = helper.toJson(descriptor.metadata());
            var status = descriptor.status() != null ? descriptor.status().name() : AgentStatus.UNKNOWN.name();

            // Try INSERT first; fall back to UPDATE on PK conflict (see ADR-023)
            try {
                List<Object> insertParams = new ArrayList<>();
                insertParams.add(descriptor.agentId());
                insertParams.add(descriptor.agentName());
                insertParams.add(descriptor.agentType());
                insertParams.add(status);
                insertParams.add(nodeId);
                insertParams.add(transportType);
                insertParams.add(endpointProps);
                insertParams.add(metadata);
                insertParams.add(Timestamp.from(descriptor.registeredAt() != null ? descriptor.registeredAt() : now));
                insertParams.add(Timestamp.from(now));
                helper.update(conn, INSERT_AGENT, insertParams);
            } catch (SQLException e) {
                if (!helper.isUniqueViolation(e)) throw e;
                // PK conflict → agent already exists, update mutable fields
                List<Object> updateParams = new ArrayList<>();
                updateParams.add(descriptor.agentName());
                updateParams.add(descriptor.agentType());
                updateParams.add(status);
                updateParams.add(nodeId);
                updateParams.add(transportType);
                updateParams.add(endpointProps);
                updateParams.add(metadata);
                updateParams.add(Timestamp.from(now));
                updateParams.add(descriptor.agentId());
                helper.update(conn, UPDATE_AGENT, updateParams);
            }

            // Sync capabilities: delete all then re-insert
            helper.update(conn, DELETE_CAPABILITIES, List.of(descriptor.agentId()));
            for (var capability : descriptor.capabilities()) {
                helper.update(conn, INSERT_CAPABILITY, List.of(descriptor.agentId(), capability));
            }
            log.debug("Registered agent {} (type={})", descriptor.agentId(), descriptor.agentType());
        }).whenComplete((v, ex) -> {
            if (ex != null) span.recordException(ex).setStatus(SpanStatus.ERROR);
            else span.setStatus(SpanStatus.OK);
            span.end();
        });
    }

    @Override
    public CompletableFuture<Void> unregister(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        var span = telemetry.spanBuilder("directory.unregister")
                .setAttribute("agent.id", agentId)
                .startSpan();
        return helper.mutate(conn -> {
            helper.update(conn, DELETE_AGENT, List.of(agentId));
            log.debug("Unregistered agent {}", agentId);
        }).whenComplete((v, ex) -> {
            if (ex != null) span.recordException(ex).setStatus(SpanStatus.ERROR);
            else span.setStatus(SpanStatus.OK);
            span.end();
        });
    }

    @Override
    public CompletableFuture<Void> updateStatus(String agentId, AgentStatus status) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        var span = telemetry.spanBuilder("directory.update_status")
                .setAttribute("agent.id", agentId)
                .setAttribute("agent.status", status.name())
                .startSpan();
        return helper.mutate(conn -> {
            helper.update(conn, UPDATE_STATUS,
                    List.of(status.name(), Timestamp.from(Instant.now()), agentId));
            log.debug("Updated status for agent {} to {}", agentId, status);
        }).whenComplete((v, ex) -> {
            if (ex != null) span.recordException(ex).setStatus(SpanStatus.ERROR);
            else span.setStatus(SpanStatus.OK);
            span.end();
        });
    }
}
