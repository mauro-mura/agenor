package dev.agenor.core.directory;

/**
 * Composite directory facade — extends all four capability interfaces.
 *
 * <p>This is the primary interface consumed by agents and the runtime. The in-memory
 * default implementation is {@code InMemoryAgentDirectory} in {@code agenor-runtime}.
 *
 * <p>For mix-and-match backends (e.g., JDBC registry + in-memory presence), wire the
 * individual capability interfaces via {@code AgenorRuntime.Builder} rather than using
 * this composite. The runtime assembles the right implementation per capability.
 *
 * @since 0.20.0
 * @see AgentRegistry
 * @see AgentResolver
 * @see AgentDiscovery
 * @see AgentPresence
 */
public interface AgentDirectory
        extends AgentRegistry, AgentResolver, AgentDiscovery, AgentPresence {
}
