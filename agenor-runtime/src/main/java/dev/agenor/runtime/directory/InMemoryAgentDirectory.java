package dev.agenor.runtime.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentQuery;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.Page;
import dev.agenor.core.PageRequest;
import dev.agenor.core.directory.AgentDirectory;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link AgentDirectory}.
 *
 * <p>All registered agents are assigned a {@link AgentEndpoint} with
 * {@code transportType="local"} and a runtime-generated {@code nodeId}. This
 * allows the {@link dev.agenor.runtime.messaging.InMemoryMessageDispatcher} to
 * short-circuit point-to-point messages without a transport hop.
 *
 * <p>This implementation replaces the {@code LocalAgentDirectory} class removed at 0.22.0.
 *
 * @since 0.20.0
 */
/**
 * Implements both the new clean {@link AgentDirectory} and the backward-compatible
 * {@link dev.agenor.core.AgentDirectory} facade so this class can be used anywhere
 * either interface is expected.
 */
public class InMemoryAgentDirectory implements AgentDirectory, dev.agenor.core.AgentDirectory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAgentDirectory.class);

    /** UUID identifying this JVM instance — constant for the lifetime of the runtime. */
    private final String nodeId;
    private volatile AgenorTelemetry telemetry;

    private final ConcurrentHashMap<String, AgentDescriptor> agents = new ConcurrentHashMap<>();

    /**
     * Creates an {@code InMemoryAgentDirectory} with a randomly generated node ID and noop telemetry.
     */
    public InMemoryAgentDirectory() {
        this(UUID.randomUUID().toString(), AgenorTelemetry.noop());
    }

    /**
     * Creates an {@code InMemoryAgentDirectory} with the given node ID and noop telemetry.
     *
     * @param nodeId the node identifier for this JVM; must not be null
     */
    public InMemoryAgentDirectory(String nodeId) {
        this(nodeId, AgenorTelemetry.noop());
    }

    /**
     * Creates an {@code InMemoryAgentDirectory} with the given node ID and telemetry.
     *
     * @param nodeId    the node identifier for this JVM; must not be null
     * @param telemetry telemetry instance for {@code directory.resolve} spans; null treated as noop
     */
    public InMemoryAgentDirectory(String nodeId, AgenorTelemetry telemetry) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.telemetry = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    /**
     * Updates the telemetry instance after construction.
     *
     * @param telemetry the telemetry instance; null treated as noop
     */
    public void setTelemetry(AgenorTelemetry telemetry) {
        this.telemetry = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    /** Returns the node ID assigned to this JVM instance. */
    public String nodeId() {
        return nodeId;
    }

    // -------------------------------------------------------------------------
    // AgentRegistry
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> register(AgentDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.agentId() == null || descriptor.agentId().isBlank()) {
            log.warn("Cannot register agent with null/blank agentId — skipping directory registration for '{}'",
                    descriptor.agentName());
            return CompletableFuture.completedFuture(null);
        }
        // Ensure a local endpoint is set if not already provided
        AgentDescriptor toStore = descriptor.endpoint() == null
                ? AgentDescriptor.builder(descriptor.agentId())
                        .agentName(descriptor.agentName())
                        .agentType(descriptor.agentType())
                        .status(descriptor.status())
                        .capabilities(descriptor.capabilities())
                        .metadata(descriptor.metadata())
                        .endpoint(AgentEndpoint.local(nodeId))
                        .registeredAt(descriptor.registeredAt())
                        .lastSeen(descriptor.lastSeen())
                        .build()
                : descriptor;
        agents.put(descriptor.agentId(), toStore);
        log.debug("Registered agent: {} ({}) on node {}", toStore.agentName(), toStore.agentId(), nodeId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregister(String agentId) {
        Objects.requireNonNull(agentId, "agentId");
        var removed = agents.remove(agentId);
        if (removed != null) {
            log.debug("Unregistered agent: {} ({})", removed.agentName(), agentId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> updateStatus(String agentId, AgentStatus status) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(status, "status");
        agents.computeIfPresent(agentId, (id, d) ->
                AgentDescriptor.builder(d.agentId())
                        .agentName(d.agentName())
                        .agentType(d.agentType())
                        .status(status)
                        .capabilities(d.capabilities())
                        .metadata(d.metadata())
                        .endpoint(d.endpoint())
                        .registeredAt(d.registeredAt())
                        .lastSeen(Instant.now())
                        .build()
        );
        log.debug("Updated status for agent '{}' to {}", agentId, status);
        return CompletableFuture.completedFuture(null);
    }

    // -------------------------------------------------------------------------
    // AgentResolver
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<AgentEndpoint>> resolveEndpoint(String agentId) {
        Objects.requireNonNull(agentId, "agentId");
        var span = telemetry.spanBuilder("directory.resolve")
                .setAttribute("agent.id", agentId)
                .startSpan();
        try {
            var d = agents.get(agentId);
            var result = Optional.ofNullable(d).map(AgentDescriptor::endpoint);
            span.setAttribute("endpoint.type", result.map(AgentEndpoint::transportType).orElse("not-found"))
                .setStatus(SpanStatus.OK);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            span.recordException(e).setStatus(SpanStatus.ERROR);
            return CompletableFuture.failedFuture(e);
        } finally {
            span.end();
        }
    }

    // -------------------------------------------------------------------------
    // AgentDiscovery
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<AgentDescriptor>> findById(String agentId) {
        Objects.requireNonNull(agentId, "agentId");
        return CompletableFuture.completedFuture(
                Optional.ofNullable(agents.get(agentId)));
    }

    @Override
    public CompletableFuture<List<AgentDescriptor>> findByCapability(String capability) {
        Objects.requireNonNull(capability, "capability");
        return CompletableFuture.supplyAsync(() ->
                agents.values().stream()
                        .filter(d -> d.capabilities().contains(capability))
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<AgentDescriptor>> findByType(String agentType) {
        Objects.requireNonNull(agentType, "agentType");
        return CompletableFuture.supplyAsync(() ->
                agents.values().stream()
                        .filter(d -> agentType.equals(d.agentType()))
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query, PageRequest request) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> {
            var matched = agents.values().stream()
                    .filter(buildPredicate(query))
                    .collect(Collectors.toList());
            int from = (int) Math.min(request.offset(), matched.size());
            int to = (int) Math.min(from + request.size(), matched.size());
            return new Page<>(matched.subList(from, to), matched.size(),
                    request.page(), request.size());
        });
    }

    // -------------------------------------------------------------------------
    // AgentPresence
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> heartbeat(String agentId) {
        Objects.requireNonNull(agentId, "agentId");
        return updateStatus(agentId, AgentStatus.RUNNING);
    }

    @Override
    public CompletableFuture<AgentStatus> getStatus(String agentId) {
        Objects.requireNonNull(agentId, "agentId");
        return CompletableFuture.completedFuture(
                Optional.ofNullable(agents.get(agentId))
                        .map(AgentDescriptor::status)
                        .orElse(AgentStatus.UNKNOWN));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static Predicate<AgentDescriptor> buildPredicate(AgentQuery query) {
        Predicate<AgentDescriptor> p = d -> true;
        if (query.agentType() != null) {
            p = p.and(d -> query.agentType().equals(d.agentType()));
        }
        if (query.status() != null) {
            p = p.and(d -> query.status() == d.status());
        }
        if (query.requiredCapabilities() != null && !query.requiredCapabilities().isEmpty()) {
            Set<String> caps = query.requiredCapabilities();
            p = p.and(d -> d.capabilities().containsAll(caps));
        }
        return p;
    }
}
