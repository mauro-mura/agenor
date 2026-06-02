package dev.agenor.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Immutable descriptor containing agent metadata for discovery.
 *
 * <p>The {@code endpoint} field was added in 0.20.0 to carry transport routing information.
 * It is nullable: agents built with {@link #builder(String)} without an explicit
 * {@link AgentDescriptorBuilder#endpoint(AgentEndpoint)} call will have a {@code null} endpoint.
 *
 * @param agentId       unique identifier of the agent
 * @param agentName     human-readable display name
 * @param agentType     fully-qualified class name of the agent implementation
 * @param status        current lifecycle status; defaults to {@link AgentStatus#UNKNOWN} if null
 * @param capabilities  immutable set of capability tags declared by this agent; empty if null
 * @param metadata      immutable map of arbitrary key/value metadata; empty if null
 * @param endpoint      transport endpoint for routing; nullable (populated by {@code InMemoryAgentDirectory})
 * @param registeredAt  timestamp of first registration; defaults to {@code Instant.now()} if null
 * @param lastSeen      timestamp of last heartbeat or status update; defaults to {@code Instant.now()} if null
 * @since 0.1.0
 */
public record AgentDescriptor(
        @JsonProperty("agentId") String agentId,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("agentType") String agentType,
        @JsonProperty("status") AgentStatus status,
        @JsonProperty("capabilities") Set<String> capabilities,
        @JsonProperty("metadata") Map<String, String> metadata,
        @JsonProperty("endpoint") AgentEndpoint endpoint,
        @JsonProperty("registeredAt") Instant registeredAt,
        @JsonProperty("lastSeen") Instant lastSeen
) {

    @JsonCreator
    public AgentDescriptor(
            @JsonProperty("agentId") String agentId,
            @JsonProperty("agentName") String agentName,
            @JsonProperty("agentType") String agentType,
            @JsonProperty("status") AgentStatus status,
            @JsonProperty("capabilities") Set<String> capabilities,
            @JsonProperty("metadata") Map<String, String> metadata,
            @JsonProperty("endpoint") AgentEndpoint endpoint,
            @JsonProperty("registeredAt") Instant registeredAt,
            @JsonProperty("lastSeen") Instant lastSeen
    ) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.agentType = agentType;
        this.status = status != null ? status : AgentStatus.UNKNOWN;
        this.capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.endpoint = endpoint;
        this.registeredAt = registeredAt != null ? registeredAt : Instant.now();
        this.lastSeen = lastSeen != null ? lastSeen : Instant.now();
    }

    /**
     * Create a builder for constructing agent descriptors.
     *
     * @param agentId the agent ID
     * @return new agent descriptor builder
     */
    public static AgentDescriptorBuilder builder(String agentId) {
        return new AgentDescriptorBuilder(agentId);
    }

    public static class AgentDescriptorBuilder {
        private final String agentId;
        private String agentName;
        private String agentType;
        private AgentStatus status = AgentStatus.UNKNOWN;
        private Set<String> capabilities = Set.of();
        private Map<String, String> metadata = Map.of();
        private AgentEndpoint endpoint;
        private Instant registeredAt;
        private Instant lastSeen;

        private AgentDescriptorBuilder(String agentId) {
            this.agentId = agentId;
        }

        public AgentDescriptorBuilder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public AgentDescriptorBuilder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public AgentDescriptorBuilder status(AgentStatus status) {
            this.status = status;
            return this;
        }

        public AgentDescriptorBuilder capabilities(Set<String> capabilities) {
            if (capabilities != null) {
                var allCapabilities = new java.util.HashSet<>(this.capabilities);
                allCapabilities.addAll(capabilities);
                this.capabilities = Set.copyOf(allCapabilities);
            }
            return this;
        }

        public AgentDescriptorBuilder capability(String capability) {
            if (capability != null && !capability.trim().isEmpty()) {
                var newCapabilities = new java.util.HashSet<>(this.capabilities);
                newCapabilities.add(capability.trim());
                this.capabilities = Set.copyOf(newCapabilities);
            }
            return this;
        }

        public AgentDescriptorBuilder metadata(Map<String, String> metadata) {
            if (metadata != null) {
                var allMetadata = new java.util.HashMap<>(this.metadata);
                allMetadata.putAll(metadata);
                this.metadata = Map.copyOf(allMetadata);
            }
            return this;
        }

        public AgentDescriptorBuilder metadata(String key, String value) {
            if (key != null && value != null) {
                var newMetadata = new java.util.HashMap<>(this.metadata);
                newMetadata.put(key, value);
                this.metadata = Map.copyOf(newMetadata);
            }
            return this;
        }

        /**
         * Sets the transport endpoint for this agent.
         *
         * @param endpoint the transport endpoint; may be null
         * @return this builder
         * @since 0.20.0
         */
        public AgentDescriptorBuilder endpoint(AgentEndpoint endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public AgentDescriptorBuilder registeredAt(Instant registeredAt) {
            this.registeredAt = registeredAt;
            return this;
        }

        public AgentDescriptorBuilder lastSeen(Instant lastSeen) {
            this.lastSeen = lastSeen;
            return this;
        }

        public AgentDescriptor build() {
            return new AgentDescriptor(agentId, agentName, agentType, status,
                    capabilities, metadata, endpoint, registeredAt, lastSeen);
        }
    }
}
