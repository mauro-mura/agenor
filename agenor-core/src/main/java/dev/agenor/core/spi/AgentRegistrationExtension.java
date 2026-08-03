package dev.agenor.core.spi;

import dev.agenor.core.Agent;

/**
 * Extension point invoked once per {@code AgenorRuntime.registerAgent()} call,
 * discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Lets optional runtime modules (LLM guardrails, HITL behavior wrapping, ...)
 * hook into agent registration without {@code agenor-runtime} depending on them
 * directly. Absent implementations simply never register — the corresponding
 * feature becomes an inert annotation rather than a compile-time dependency.
 *
 * @since 0.25.0
 */
public interface AgentRegistrationExtension {

    /**
     * Called once for {@code agent} during {@code registerAgent()}.
     *
     * @param agent   the agent being registered; never {@code null}
     * @param context runtime services available to this extension; never {@code null}
     */
    void onAgentRegistered(Agent agent, RegistrationContext context);
}
