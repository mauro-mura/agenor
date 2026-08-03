package dev.agenor.runtime.guardrail;

import dev.agenor.core.Agent;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.RegistrationContext;
import dev.agenor.runtime.agent.LLMAgent;

/**
 * Injects the {@code @WithGuardrails} chain and installs telemetry on
 * {@link LLMAgent} instances at agent registration time, discovered via
 * {@link java.util.ServiceLoader}.
 *
 * @see GuardrailAnnotationProcessor
 * @since 0.25.0
 */
public final class LlmRegistrationExtension implements AgentRegistrationExtension {

    @Override
    public void onAgentRegistered(Agent agent, RegistrationContext context) {
        if (agent instanceof LLMAgent llmAgent) {
            GuardrailAnnotationProcessor.process(llmAgent);
            llmAgent.installTelemetry(context.telemetry());
        }
    }
}
