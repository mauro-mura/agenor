package dev.agenor.core.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentQuery;
import dev.agenor.core.Page;
import dev.agenor.core.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Capability for querying the agent directory.
 *
 * <p>All query methods return {@code CompletableFuture} to support both in-memory
 * and remote backends (JDBC, Consul, etcd) transparently.
 *
 * @since 0.20.0
 * @see AgentDirectory
 */
public interface AgentDiscovery {

    /**
     * Finds an agent by its unique identifier.
     *
     * @param agentId the unique identifier, must not be null
     * @return a future containing the descriptor if found, or empty if not registered
     * @throws NullPointerException if agentId is null
     */
    CompletableFuture<Optional<AgentDescriptor>> findById(String agentId);

    /**
     * Finds all agents that declare the given capability.
     *
     * @param capability the capability tag, must not be null
     * @return a future containing a (possibly empty) list of matching descriptors
     * @throws NullPointerException if capability is null
     */
    CompletableFuture<List<AgentDescriptor>> findByCapability(String capability);

    /**
     * Finds all agents of the given type.
     *
     * @param agentType the agent type string, must not be null
     * @return a future containing a (possibly empty) list of matching descriptors
     * @throws NullPointerException if agentType is null
     */
    CompletableFuture<List<AgentDescriptor>> findByType(String agentType);

    /**
     * Finds agents matching the given query, returning a bounded page of results.
     *
     * <p>Use {@link AgentQuery#all()} to retrieve all agents with pagination.
     *
     * @param query   the search criteria, must not be null
     * @param request pagination parameters, must not be null
     * @return a future containing the page of matching descriptors
     * @throws NullPointerException if query or request is null
     */
    CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query, PageRequest request);
}
