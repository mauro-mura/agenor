package dev.jentic.adapters.persistence.directory;

import dev.jentic.adapters.persistence.JdbcHelper;
import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.directory.AgentResolver;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.SpanStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JDBC implementation of {@link AgentResolver}.
 *
 * <p>Single-row PK lookup — O(1) via the primary key index on {@code agent_id}.
 *
 * @since 0.22.0
 */
public class JdbcAgentResolver implements AgentResolver {

    private static final String SELECT_ENDPOINT =
            "SELECT node_id, endpoint_transport_type, endpoint_props " +
            "FROM jentic_agents WHERE agent_id = ?";

    private final JdbcHelper helper;
    private final JenticTelemetry telemetry;

    public JdbcAgentResolver(JdbcHelper helper) {
        this(helper, JenticTelemetry.noop());
    }

    /**
     * @param helper    JDBC helper; must not be null
     * @param telemetry telemetry for {@code directory.resolve} spans; null treated as noop
     */
    public JdbcAgentResolver(JdbcHelper helper, JenticTelemetry telemetry) {
        this.helper = Objects.requireNonNull(helper, "helper must not be null");
        this.telemetry = telemetry != null ? telemetry : JenticTelemetry.noop();
    }

    @Override
    public CompletableFuture<Optional<AgentEndpoint>> resolveEndpoint(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        var span = telemetry.spanBuilder("directory.resolve")
                .setAttribute("agent.id", agentId)
                .startSpan();
        return helper.<Optional<AgentEndpoint>>query(conn -> {
            var endpoint = helper.queryOne(conn, SELECT_ENDPOINT, List.of(agentId),
                    helper::mapEndpoint);
            return Optional.ofNullable(endpoint);
        }).whenComplete((result, ex) -> {
            if (ex != null) {
                span.recordException(ex).setStatus(SpanStatus.ERROR);
            } else {
                span.setAttribute("endpoint.type",
                        result.map(AgentEndpoint::transportType).orElse("not-found"))
                        .setStatus(SpanStatus.OK);
            }
            span.end();
        });
    }
}
