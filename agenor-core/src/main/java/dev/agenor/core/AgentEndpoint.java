package dev.agenor.core;

import java.util.Map;

/**
 * Transport endpoint descriptor for a registered agent.
 *
 * <p>Carried inside {@link AgentDescriptor} to allow the message dispatcher to route
 * {@code sendTo(agentId, msg)} calls without a second directory lookup.
 *
 * <p>{@code transportType} values are open strings; the runtime defines the built-in
 * values:
 * <ul>
 *   <li>{@code "local"} — agent lives in the same JVM; dispatch is direct, no network hop</li>
 *   <li>{@code "redis"} — agent is reachable via a Redis Streams node stream (Item 3)</li>
 *   <li>{@code "a2a"} — agent is reachable via the A2A HTTP/JSON-RPC protocol</li>
 * </ul>
 *
 * @param nodeId         UUID of the JVM that owns this agent
 * @param transportType  transport identifier (e.g., {@code "local"}, {@code "redis"})
 * @param transportProps transport-specific key/value properties (stream name, URL, …)
 * @since 0.20.0
 */
public record AgentEndpoint(
        String nodeId,
        String transportType,
        Map<String, String> transportProps
) {

    /**
     * Compact constructor — defensively copies {@code transportProps}.
     */
    public AgentEndpoint {
        transportProps = transportProps != null ? Map.copyOf(transportProps) : Map.of();
    }

    /**
     * Creates a local endpoint for the given node and agent.
     *
     * @param nodeId runtime-generated UUID of the owning JVM
     * @return a local-transport endpoint
     */
    public static AgentEndpoint local(String nodeId) {
        return new AgentEndpoint(nodeId, "local", Map.of());
    }
}
