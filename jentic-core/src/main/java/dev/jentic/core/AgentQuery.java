package dev.jentic.core;

import java.util.Set;

/**
 * Query object for searching agents in the {@link AgentDirectory}.
 *
 * <p>Combines multiple criteria (AND logic). A {@code null} value for any criterion
 * means "don't filter by this criterion".
 *
 * @param agentType            optional agent type filter
 * @param requiredCapabilities optional set of capabilities agents must have (ALL required)
 * @param status               optional status filter
 * @since 0.1.0
 */
public record AgentQuery(
        String agentType,
        Set<String> requiredCapabilities,
        AgentStatus status
) {

    /**
     * Creates a query that matches every agent (no filters applied).
     *
     * <p>Use with {@link dev.jentic.core.directory.AgentDiscovery#findAgents} and pagination.
     *
     * @return an unconstrained query
     * @since 0.20.0
     */
    public static AgentQuery all() {
        return new AgentQuery(null, null, null);
    }

    /**
     * Creates a new query builder.
     *
     * @return a new {@link AgentQueryBuilder} instance
     */
    public static AgentQueryBuilder builder() {
        return new AgentQueryBuilder();
    }

    /**
     * Creates a query that filters by agent type only.
     *
     * @param agentType the agent type to search for, must not be null
     * @return a new query matching only the specified agent type
     */
    public static AgentQuery byType(String agentType) {
        return new AgentQuery(agentType, null, null);
    }

    /**
     * Creates a query that filters by agent status only.
     *
     * @param status the agent status to search for, must not be null
     * @return a new query matching only the specified status
     */
    public static AgentQuery byStatus(AgentStatus status) {
        return new AgentQuery(null, null, status);
    }

    /**
     * Creates a query that filters by required capabilities.
     *
     * <p>An agent matches only if it has ALL of the specified capabilities.
     *
     * @param capabilities the set of required capabilities, must not be null or empty
     * @return a new query matching agents with all specified capabilities
     */
    public static AgentQuery withCapabilities(Set<String> capabilities) {
        return new AgentQuery(null, capabilities, null);
    }

    /**
     * Fluent builder for constructing {@link AgentQuery} instances.
     */
    public static class AgentQueryBuilder {
        private String agentType;
        private Set<String> requiredCapabilities;
        private AgentStatus status;

        /**
         * Sets the agent type filter.
         *
         * @param agentType the type to filter by
         * @return this builder
         */
        public AgentQueryBuilder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        /**
         * Sets all required capabilities, replacing any previously set.
         *
         * @param capabilities the capabilities to require
         * @return this builder
         */
        public AgentQueryBuilder requiredCapabilities(Set<String> capabilities) {
            this.requiredCapabilities = capabilities;
            return this;
        }

        /**
         * Adds a single required capability.
         *
         * @param capability the capability to require
         * @return this builder
         */
        public AgentQueryBuilder requiredCapability(String capability) {
            if (this.requiredCapabilities == null) {
                this.requiredCapabilities = Set.of(capability);
            } else {
                var newCapabilities = new java.util.HashSet<>(this.requiredCapabilities);
                newCapabilities.add(capability);
                this.requiredCapabilities = Set.copyOf(newCapabilities);
            }
            return this;
        }

        /**
         * Sets the status filter.
         *
         * @param status the status to filter by
         * @return this builder
         */
        public AgentQueryBuilder status(AgentStatus status) {
            this.status = status;
            return this;
        }

        /**
         * Builds an immutable {@link AgentQuery}.
         *
         * @return a new query instance
         */
        public AgentQuery build() {
            return new AgentQuery(agentType, requiredCapabilities, status);
        }
    }
}
