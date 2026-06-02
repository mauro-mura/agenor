package dev.agenor.core;

import dev.agenor.core.directory.AgentDiscovery;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.directory.AgentResolver;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Agent directory façade — extends all four capability interfaces.
 *
 * <p>This composite interface is the primary directory contract consumed by agents and the
 * runtime. The in-memory default implementation is {@code InMemoryAgentDirectory} in
 * {@code agenor-runtime}.
 *
 * <p>For mix-and-match backends (e.g., JDBC registry + in-memory presence), wire the
 * individual capability interfaces ({@link AgentRegistry}, {@link AgentResolver},
 * {@link AgentDiscovery}, {@link AgentPresence}) via {@code JenticRuntime.Builder}
 * rather than using this composite.
 *
 * @since 0.1.0
 * @deprecated since 0.22.0, for removal at 0.24.0 (Agenor rebranding). Use
 *     {@link dev.agenor.core.directory.AgentDirectory} instead. The runtime, starter,
 *     and all built-in implementations already implement the new interface; only the
 *     package path changes.
 * @see dev.agenor.core.directory.AgentDirectory
 * @see AgentRegistry
 * @see AgentResolver
 * @see AgentDiscovery
 * @see AgentPresence
 */
@Deprecated(since = "0.22.0", forRemoval = true)
public interface AgentDirectory extends AgentRegistry, AgentResolver, AgentDiscovery, AgentPresence {

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
    // AgentDiscovery — default bridges delegating to findAgents(AgentQuery, PageRequest)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Default: delegates to {@link #findAgents(AgentQuery, PageRequest)} with a capability filter.
     */
    @Override
    default CompletableFuture<List<AgentDescriptor>> findByCapability(String capability) {
        return findAgents(AgentQuery.withCapabilities(Set.of(capability)),
                PageRequest.first(Integer.MAX_VALUE))
                .thenApply(Page::content);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default: delegates to {@link #findAgents(AgentQuery, PageRequest)} with a type filter.
     */
    @Override
    default CompletableFuture<List<AgentDescriptor>> findByType(String agentType) {
        return findAgents(AgentQuery.byType(agentType), PageRequest.first(Integer.MAX_VALUE))
                .thenApply(Page::content);
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
