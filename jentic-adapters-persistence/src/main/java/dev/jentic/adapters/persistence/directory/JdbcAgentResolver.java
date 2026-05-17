package dev.jentic.adapters.persistence.directory;

import dev.jentic.adapters.persistence.JdbcHelper;
import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.directory.AgentResolver;

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

    public JdbcAgentResolver(JdbcHelper helper) {
        this.helper = Objects.requireNonNull(helper, "helper must not be null");
    }

    @Override
    public CompletableFuture<Optional<AgentEndpoint>> resolveEndpoint(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        return helper.<Optional<AgentEndpoint>>query(conn -> {
            var endpoint = helper.queryOne(conn, SELECT_ENDPOINT, List.of(agentId),
                    helper::mapEndpoint);
            return Optional.ofNullable(endpoint);
        });
    }
}
