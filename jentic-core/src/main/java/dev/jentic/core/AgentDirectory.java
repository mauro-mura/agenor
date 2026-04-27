package dev.jentic.core;

import dev.jentic.core.directory.AgentDiscovery;
import dev.jentic.core.directory.AgentPresence;
import dev.jentic.core.directory.AgentRegistry;
import dev.jentic.core.directory.AgentResolver;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Agent directory façade — extends all four capability interfaces.
 *
 * <p>This composite interface is the primary directory contract consumed by agents and the
 * runtime. The in-memory default implementation is {@code InMemoryAgentDirectory} in
 * {@code jentic-runtime}.
 *
 * <p>For mix-and-match backends (e.g., JDBC registry + in-memory presence), wire the
 * individual capability interfaces ({@link AgentRegistry}, {@link AgentResolver},
 * {@link AgentDiscovery}, {@link AgentPresence}) via {@code JenticRuntime.Builder}
 * rather than using this composite.
 *
 * <p><strong>Deprecated methods</strong>: {@link #listAll()} and
 * {@link #findAgents(AgentQuery)} are deprecated since 0.20.0; use
 * {@link AgentDiscovery#findAgents(AgentQuery, PageRequest)} instead.
 *
 * @since 0.1.0
 * @see dev.jentic.core.directory.AgentDirectory
 * @see AgentRegistry
 * @see AgentResolver
 * @see AgentDiscovery
 * @see AgentPresence
 */
public interface AgentDirectory extends AgentRegistry, AgentResolver, AgentDiscovery, AgentPresence {

    // -------------------------------------------------------------------------
    // Deprecated legacy abstract methods (kept for LocalAgentDirectory compat)
    // -------------------------------------------------------------------------

    /**
     * Finds agents matching the given query (no pagination).
     *
     * @param query search criteria, must not be null
     * @return a future containing a list of matching descriptors
     * @deprecated since 0.20.0, for removal at 0.22.0.
     *     Use {@link #findAgents(AgentQuery, PageRequest)}.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    CompletableFuture<List<AgentDescriptor>> findAgents(AgentQuery query);

    /**
     * Lists all registered agents.
     *
     * @return a future containing all registered agent descriptors
     * @deprecated since 0.20.0, for removal at 0.22.0.
     *     Use {@code findAgents(AgentQuery.all(), PageRequest.first(Integer.MAX_VALUE))}.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    default CompletableFuture<List<AgentDescriptor>> listAll() {
        return findAgents(AgentQuery.all());
    }

    // -------------------------------------------------------------------------
    // AgentResolver — default bridge using findById + AgentDescriptor.endpoint
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Default: resolves via {@link #findById(String)} and returns
     * {@link AgentDescriptor#endpoint()} when present.
     */
    @Override
    default CompletableFuture<Optional<AgentEndpoint>> resolveEndpoint(String agentId) {
        return findById(agentId)
                .thenApply(opt -> opt.flatMap(d -> Optional.ofNullable(d.endpoint())));
    }

    // -------------------------------------------------------------------------
    // AgentDiscovery — default bridges using deprecated findAgents(AgentQuery)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Default: delegates to {@link #findAgents(AgentQuery)} with a capability filter.
     */
    @Override
    default CompletableFuture<List<AgentDescriptor>> findByCapability(String capability) {
        return findAgents(AgentQuery.withCapabilities(Set.of(capability)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default: delegates to {@link #findAgents(AgentQuery)} with a type filter.
     */
    @Override
    default CompletableFuture<List<AgentDescriptor>> findByType(String agentType) {
        return findAgents(AgentQuery.byType(agentType));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default: delegates to the deprecated {@link #findAgents(AgentQuery)}, then
     * applies in-memory pagination. This is acceptable for the in-memory backend;
     * remote backends should provide their own efficient implementation.
     */
    @Override
    default CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query,
                                                                 PageRequest request) {
        return findAgents(query).thenApply(list -> {
            int from = (int) Math.min(request.offset(), list.size());
            int to = (int) Math.min(from + request.size(), list.size());
            return new Page<>(list.subList(from, to), list.size(),
                    request.page(), request.size());
        });
    }

    // -------------------------------------------------------------------------
    // AgentPresence — default bridges using updateStatus / findById
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Default: calls {@link #updateStatus(String, AgentStatus)} with
     * {@link AgentStatus#RUNNING}.
     */
    @Override
    default CompletableFuture<Void> heartbeat(String agentId) {
        return updateStatus(agentId, AgentStatus.RUNNING);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default: calls {@link #findById(String)} and returns the status, or
     * {@link AgentStatus#UNKNOWN} if the agent is not registered.
     */
    @Override
    default CompletableFuture<AgentStatus> getStatus(String agentId) {
        return findById(agentId)
                .thenApply(opt -> opt.map(AgentDescriptor::status)
                        .orElse(AgentStatus.UNKNOWN));
    }
}
