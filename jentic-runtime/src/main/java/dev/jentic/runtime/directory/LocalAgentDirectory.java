package dev.jentic.runtime.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentDirectory;
import dev.jentic.core.AgentQuery;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Page;
import dev.jentic.core.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Local in-memory implementation of AgentDirectory.
 *
 * @deprecated since 0.20.0. Use {@link InMemoryAgentDirectory} instead.
 */
@Deprecated(since = "0.20.0", forRemoval = true)
@SuppressWarnings("deprecation")
public class LocalAgentDirectory implements AgentDirectory {
    
    private static final Logger log = LoggerFactory.getLogger(LocalAgentDirectory.class);
    
    private final ConcurrentHashMap<String, AgentDescriptor> agents = new ConcurrentHashMap<>();
    
    @Override
    public CompletableFuture<Void> register(AgentDescriptor descriptor) {
        return CompletableFuture.runAsync(() -> {
            agents.put(descriptor.agentId(), descriptor);
            log.debug("Registered agent: {} ({})", descriptor.agentName(), descriptor.agentId());
        });
    }
    
    @Override
    public CompletableFuture<Void> unregister(String agentId) {
        return CompletableFuture.runAsync(() -> {
            AgentDescriptor removed = agents.remove(agentId);
            if (removed != null) {
                log.debug("Unregistered agent: {} ({})", removed.agentName(), agentId);
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<AgentDescriptor>> findById(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            return Optional.ofNullable(agents.get(agentId));
        });
    }
    
    @Override
    public CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query, PageRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            var matched = agents.values().stream()
                .filter(descriptor -> matchesQuery(descriptor, query))
                .collect(Collectors.toList());
            int from = (int) Math.min(request.offset(), matched.size());
            int to   = (int) Math.min(from + request.size(), matched.size());
            return new Page<>(matched.subList(from, to), matched.size(),
                    request.page(), request.size());
        });
    }
    
    @Override
    public CompletableFuture<Void> updateStatus(String agentId, AgentStatus status) {
        return CompletableFuture.runAsync(() -> {
            agents.computeIfPresent(agentId, (id, descriptor) -> {
                return new AgentDescriptor(
                    descriptor.agentId(),
                    descriptor.agentName(),
                    descriptor.agentType(),
                    status,
                    descriptor.capabilities(),
                    descriptor.metadata(),
                    descriptor.registeredAt(),
                    Instant.now()  // Update lastSeen
                );
            });
            log.debug("Updated status for agent: {} to: {}", agentId, status);
        });
    }
    
    private boolean matchesQuery(AgentDescriptor descriptor, AgentQuery query) {
        // Check agent type
        if (query.agentType() != null && !query.agentType().equals(descriptor.agentType())) {
            return false;
        }
        
        // Check status
        if (query.status() != null && query.status() != descriptor.status()) {
            return false;
        }
        
        // Check required capabilities
        if (query.requiredCapabilities() != null && 
            !descriptor.capabilities().containsAll(query.requiredCapabilities())) {
            return false;
        }
        
        // Check custom filter
        if (query.customFilter() != null && !query.customFilter().test(descriptor)) {
            return false;
        }
        
        return true;
    }
}