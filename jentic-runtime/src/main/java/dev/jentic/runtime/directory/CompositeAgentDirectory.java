package dev.jentic.runtime.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.AgentQuery;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Page;
import dev.jentic.core.PageRequest;
import dev.jentic.core.directory.AgentDirectory;
import dev.jentic.core.directory.AgentDiscovery;
import dev.jentic.core.directory.AgentPresence;
import dev.jentic.core.directory.AgentRegistry;
import dev.jentic.core.directory.AgentResolver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Assembles four separate capability implementations into a single {@link AgentDirectory}.
 *
 * <p>Use this when different backends handle different concerns — for example, a JDBC
 * registry combined with in-memory presence:
 *
 * <pre>{@code
 * JenticRuntime.builder()
 *     .agentRegistry(jdbcRegistry)
 *     .agentDiscovery(jdbcDiscovery)
 *     .agentPresence(inMemoryPresence)
 *     .build();
 * }</pre>
 *
 * @since 0.22.0
 */
public class CompositeAgentDirectory
        implements AgentDirectory, dev.jentic.core.AgentDirectory {

    private final AgentRegistry registry;
    private final AgentDiscovery discovery;
    private final AgentResolver resolver;
    private final AgentPresence presence;

    public CompositeAgentDirectory(AgentRegistry registry,
                                   AgentDiscovery discovery,
                                   AgentResolver resolver,
                                   AgentPresence presence) {
        this.registry  = Objects.requireNonNull(registry,  "registry");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.resolver  = Objects.requireNonNull(resolver,  "resolver");
        this.presence  = Objects.requireNonNull(presence,  "presence");
    }

    // -------------------------------------------------------------------------
    // AgentRegistry
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> register(AgentDescriptor descriptor) {
        return registry.register(descriptor);
    }

    @Override
    public CompletableFuture<Void> unregister(String agentId) {
        return registry.unregister(agentId);
    }

    @Override
    public CompletableFuture<Void> updateStatus(String agentId, AgentStatus status) {
        return registry.updateStatus(agentId, status);
    }

    // -------------------------------------------------------------------------
    // AgentResolver
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<AgentEndpoint>> resolveEndpoint(String agentId) {
        return resolver.resolveEndpoint(agentId);
    }

    // -------------------------------------------------------------------------
    // AgentDiscovery
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<AgentDescriptor>> findById(String agentId) {
        return discovery.findById(agentId);
    }

    @Override
    public CompletableFuture<List<AgentDescriptor>> findByCapability(String capability) {
        return discovery.findByCapability(capability);
    }

    @Override
    public CompletableFuture<List<AgentDescriptor>> findByType(String agentType) {
        return discovery.findByType(agentType);
    }

    @Override
    public CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query, PageRequest request) {
        return discovery.findAgents(query, request);
    }

    // -------------------------------------------------------------------------
    // AgentPresence
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> heartbeat(String agentId) {
        return presence.heartbeat(agentId);
    }

    @Override
    public CompletableFuture<AgentStatus> getStatus(String agentId) {
        return presence.getStatus(agentId);
    }
}
