package dev.agenor.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Jentic agent eligible for automatic discovery and registration.
 *
 * <p>When package scanning is enabled on {@code AgenorRuntime}, all classes annotated
 * with {@code @Agent} are discovered, instantiated, and registered in the
 * {@link dev.agenor.core.AgentDirectory}. The runtime then calls {@code start()} on
 * each agent that has {@link #autoStart()} set to {@code true}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Agent(value = "order-processor", type = "processor",
 *              capabilities = {"order.processing", "inventory.check"})
 * public class OrderProcessorAgent extends BaseAgent {
 *
 *     @Behavior(type = CYCLIC, interval = "10s")
 *     public void processPendingOrders() { ... }
 * }
 * }</pre>
 *
 * @since 0.1.0
 * @see dev.agenor.core.Agent
 * @see Behavior
 * @see AgenorMessageHandler
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Agent {

    /**
     * The agent identifier used for registration and discovery.
     *
     * <p>When left empty, the runtime defaults to the simple class name
     * converted to a kebab-case (e.g., {@code OrderProcessorAgent} → {@code order-processor-agent}).
     * The value must be unique within the runtime.
     *
     * @return the agent identifier, or empty string to use the class-name default
     */
    String value() default "";

    /**
     * An optional type label used for grouping and querying agents.
     *
     * <p>Types allow callers to query the {@link dev.agenor.core.AgentDirectory} for
     * all agents of a given type (e.g., {@code "processor"}, {@code "collector"},
     * {@code "monitor"}). The type has no effect on agent behavior.
     *
     * @return the agent type label, or empty string if not categorized
     */
    String type() default "";

    /**
     * Capability identifiers advertised by this agent.
     *
     * <p>Capabilities are arbitrary strings that describe what this agent can do
     * (e.g., {@code "order.processing"}, {@code "report.generation"}). Other agents
     * can query the directory to find agents with specific capabilities.
     *
     * @return declared capability strings, empty array if none
     */
    String[] capabilities() default {};

    /**
     * Whether the runtime should start this agent automatically after registration.
     *
     * <p>Set to {@code false} for agents that should be started manually or on demand.
     * Defaults to {@code true}.
     *
     * @return {@code true} to start automatically, {@code false} for manual start
     */
    boolean autoStart() default true;
}
