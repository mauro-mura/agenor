package dev.agenor.core.spi;

import java.util.Map;
import java.util.Set;

import dev.agenor.core.Agent;
import dev.agenor.core.context.AgentContext;

/**
 * Optional package-scanning and DI-based agent construction engine, discovered via
 * {@link java.util.ServiceLoader}.
 *
 * <p>{@code agenor-runtime} depends on this interface only; the implementation
 * (reflection-based classpath scanning, factory instantiation) lives in
 * {@code agenor-runtime-scanning} and is resolved once at {@code AgenorRuntime}
 * construction time. When absent, package-scanning calls fail with a clear
 * {@link IllegalStateException} pointing at the missing module.
 *
 * <p>{@code @Behavior}/{@code @AgenorMessageHandler} annotation processing is not part
 * of this contract — it runs unconditionally in {@code agenor-runtime} regardless of
 * whether this engine is present; see {@link BehaviorAnnotationExtension} for the
 * seam covering ext-only behavior types.
 *
 * @since 0.25.0
 */
public interface AgentDiscoveryEngine {

    /**
     * Initializes this engine with the runtime's core services.
     *
     * @param context the runtime's core services; never {@code null}
     */
    void initialize(AgentContext context);

    /**
     * Scans the given packages for classes annotated with {@code @Agent}.
     *
     * @param packageNames packages to scan
     * @return discovered agent classes
     */
    Set<Class<? extends Agent>> scanForAgents(String... packageNames);

    /**
     * Instantiates one agent per discovered class.
     *
     * @param agentClasses classes to instantiate
     * @return agent instances keyed by agent ID
     */
    Map<String, Agent> createAgents(Set<Class<? extends Agent>> agentClasses);

    /**
     * Instantiates a single agent from its class via constructor injection.
     *
     * @param agentClass the agent class to instantiate
     * @param <T>        the agent type
     * @return the created agent instance
     */
    <T extends Agent> T createAgent(Class<T> agentClass);

    /**
     * Registers an additional service instance available for constructor injection.
     *
     * @param serviceClass the service type
     * @param instance     the service instance
     * @param <T>          the service type
     */
    <T> void addService(Class<T> serviceClass, T instance);
}
