package dev.jentic.core.directory;

import dev.jentic.core.AgentEndpoint;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Hot-path capability for resolving an agent ID to its transport endpoint.
 *
 * <p>Called on every {@code MessageDispatcher.sendTo(agentId, msg)} invocation, so
 * implementations must be fast. In-memory implementations return immediately; remote
 * implementations should cache recent resolutions.
 *
 * <p>Returns empty when the agent is unknown or has no registered endpoint. The dispatcher
 * converts an empty result into an
 * {@link dev.jentic.core.exceptions.AgentNotFoundException}.
 *
 * @since 0.20.0
 * @see AgentDirectory
 */
public interface AgentResolver {

    /**
     * Resolves the transport endpoint for the given agent ID.
     *
     * @param agentId the unique agent identifier, must not be null
     * @return a future containing the endpoint if the agent is registered and reachable,
     *         or empty if the agent is unknown
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<Optional<AgentEndpoint>> resolveEndpoint(String agentId);
}
