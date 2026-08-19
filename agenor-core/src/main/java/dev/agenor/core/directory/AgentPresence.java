package dev.agenor.core.directory;

import dev.agenor.core.AgentStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Capability for agent liveness signalling and status queries.
 *
 * <p>Presence is kept separate from {@link AgentRegistry} because the two have different
 * access patterns, and therefore different backend fitness. Registration data is written
 * rarely and read by query; liveness is written continuously by every live agent and read
 * by point lookup. Keeping the capabilities separate lets a deployment back each with the
 * store that suits it, instead of forcing one choice on the other.
 *
 * <p>How quickly an implementation can tell that an agent has stopped signalling — and
 * any staleness window that implies — is a property of that implementation, documented
 * there rather than fixed by this contract.
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
