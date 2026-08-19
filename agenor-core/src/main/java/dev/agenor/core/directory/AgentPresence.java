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
     * Records a heartbeat for the given agent, refreshing its {@code lastSeen} timestamp.
     *
     * <p>A heartbeat signals liveness and nothing else: it does <em>not</em> change the
     * agent's {@link AgentStatus}. Use {@link AgentRegistry#updateStatus} for that. An
     * implementation backed by key expiry has no status to promote, so a heartbeat that
     * also wrote a status could not mean the same thing across backends.
     *
     * <p>A heartbeat for an agent the implementation does not know is ignored rather than
     * rejected — it is a race with unregistration, not an error.
     *
     * @param agentId the unique agent identifier, must not be null
     * @return a future that completes when the heartbeat is recorded
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<Void> heartbeat(String agentId);

    /**
     * Returns the current status of the given agent.
     *
     * <p>The answer is {@link AgentStatus#UNKNOWN} when the agent is not registered, and
     * also when it has not been seen within the implementation's staleness window. That
     * window is a property of the implementation, not of this contract: an
     * <em>unbounded</em> window is a legal value, and means the backend never expires a
     * status.
     *
     * @param agentId the unique agent identifier, must not be null
     * @return a future containing the agent's status, or
     *         {@link AgentStatus#UNKNOWN} if it is unregistered or stale
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<AgentStatus> getStatus(String agentId);
}
