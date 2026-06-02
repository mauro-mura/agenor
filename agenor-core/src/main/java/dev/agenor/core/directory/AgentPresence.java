package dev.agenor.core.directory;

import dev.agenor.core.AgentStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Capability for agent liveness signalling and status queries.
 *
 * <p>Presence is intentionally separated from {@link AgentRegistry} because liveness
 * has different access patterns (high write frequency from heartbeats) and different
 * backend fitness (Redis TTL keys, Consul session leases) compared to registration
 * data (low-frequency writes, relational storage).
 *
 * <p>The JDBC agent directory ({@code agenor-adapters-persistence}) deliberately does
 * <em>not</em> implement this interface. Heartbeat-over-JDBC amplifies write load and
 * Postgres is not the right tool for liveness. The runtime uses in-memory presence by
 * default and a dedicated backend (Redis TTL — future) for multi-node liveness.
 *
 * @since 0.20.0
 * @see AgentDirectory
 */
public interface AgentPresence {

    /**
     * Records a heartbeat for the given agent, updating its {@code lastSeen} timestamp.
     *
     * @param agentId the unique agent identifier, must not be null
     * @return a future that completes when the heartbeat is recorded
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<Void> heartbeat(String agentId);

    /**
     * Returns the current status of the given agent.
     *
     * @param agentId the unique agent identifier, must not be null
     * @return a future containing the agent's status, or
     *         {@link dev.agenor.core.AgentStatus#UNKNOWN} if not registered
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<AgentStatus> getStatus(String agentId);
}
