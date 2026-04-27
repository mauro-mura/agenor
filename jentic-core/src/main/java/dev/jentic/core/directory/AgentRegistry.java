package dev.jentic.core.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Capability for registering, unregistering, and updating agents in the directory.
 *
 * <p>All backends that store agent state must implement this interface.
 * Backends that only answer queries (e.g., a read-replica) may implement
 * {@link AgentDiscovery} without implementing this interface.
 *
 * @since 0.20.0
 * @see AgentDirectory
 */
public interface AgentRegistry {

    /**
     * Registers an agent in the directory, making it discoverable.
     *
     * <p>If an agent with the same ID is already registered, the implementation
     * should update the existing entry.
     *
     * @param descriptor the agent descriptor, must not be null
     * @return a future that completes when registration succeeds
     * @throws NullPointerException if descriptor is null
     */
    CompletableFuture<Void> register(AgentDescriptor descriptor);

    /**
     * Removes an agent from the directory.
     *
     * <p>If the agent is not registered the call is a no-op.
     *
     * @param agentId the unique agent identifier, must not be null
     * @return a future that completes when unregistration succeeds
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<Void> unregister(String agentId);

    /**
     * Updates the status of a registered agent.
     *
     * <p>Implementations should also update the agent's {@code lastSeen} timestamp.
     * If the agent is not registered the behaviour is implementation-defined (log-and-ignore
     * is recommended for leniency).
     *
     * @param agentId the unique agent identifier, must not be null
     * @param status  the new status, must not be null
     * @return a future that completes when the update succeeds
     * @throws NullPointerException if agentId or status is null
     */
    CompletableFuture<Void> updateStatus(String agentId, AgentStatus status);
}
