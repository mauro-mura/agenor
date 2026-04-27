package dev.jentic.core;

import java.util.Map;

/**
 * Transport-layer handle used by {@link dev.jentic.core.messaging.MessageTransport}.
 *
 * <p>Agents work with {@code agentId} strings. The dispatcher resolves an agent ID to
 * an {@link AgentEndpoint} and then converts it to a {@code TransportEndpoint} for the
 * actual wire operation. This separation keeps agent identifiers stable while allowing
 * the physical address to change (node restart, migration).
 *
 * @param transportType  transport identifier (must match the owning transport implementation)
 * @param address        transport-specific physical address (node ID for local, stream key for Redis, …)
 * @param properties     optional transport-specific properties
 * @since 0.20.0
 */
public record TransportEndpoint(
        String transportType,
        String address,
        Map<String, String> properties
) {

    /**
     * Compact constructor — defensively copies {@code properties}.
     */
    public TransportEndpoint {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }

    /**
     * Creates a local transport endpoint addressed by agent ID.
     *
     * @param agentId the target agent identifier
     * @return a local-transport endpoint
     */
    public static TransportEndpoint local(String agentId) {
        return new TransportEndpoint("local", agentId, Map.of());
    }
}
