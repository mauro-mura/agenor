package dev.agenor.runtime.hitl;

import dev.agenor.core.Agent;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.RegistrationContext;
import dev.agenor.runtime.agent.BaseAgent;

/**
 * Wraps {@code @RequiresApproval}-annotated behaviors at agent registration time,
 * discovered via {@link java.util.ServiceLoader}.
 *
 * @see HitlAnnotationProcessor
 * @since 0.25.0
 */
public final class HitlRegistrationExtension implements AgentRegistrationExtension {

    @Override
    public void onAgentRegistered(Agent agent, RegistrationContext context) {
        if (agent instanceof BaseAgent baseAgent) {
            HitlAnnotationProcessor.process(baseAgent, context.approvalGate());
        }
    }
}
