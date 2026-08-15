package dev.agenor.runtime.guardrail;

import dev.agenor.core.Agent;
import dev.agenor.core.guardrail.WithGuardrails;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.RegistrationContext;
import dev.agenor.runtime.llm.LLMAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Injects the {@code @WithGuardrails} chain and installs telemetry on
 * {@link LLMAgent} instances at agent registration time, discovered via
 * {@link java.util.ServiceLoader}.
 *
 * @see GuardrailAnnotationProcessor
 * @since 0.25.0
 */
public final class LlmRegistrationExtension implements AgentRegistrationExtension {

    private static final Logger log = LoggerFactory.getLogger(LlmRegistrationExtension.class);

    @Override
    public void onAgentRegistered(Agent agent, RegistrationContext context) {
        if (agent instanceof LLMAgent llmAgent) {
            GuardrailAnnotationProcessor.process(llmAgent);
            llmAgent.installTelemetry(context.telemetry());
        } else if (agent.getClass().isAnnotationPresent(WithGuardrails.class)) {
            // The runtime's startup diagnostic cannot catch this one: it sees the annotation
            // as claimed, because this module is on the classpath. Only this module knows the
            // annotation applies to LLMAgent alone.
            log.warn("@WithGuardrails on {} has no effect: guardrails apply to LLMAgent "
                    + "subclasses only, and this agent is not one.", agent.getClass().getName());
        }
    }

    /**
     * @return {@code @WithGuardrails}, which this extension processes
     * @since 0.26.0
     */
    @Override
    public Set<Class<? extends Annotation>> handledAnnotations() {
        return Set.of(WithGuardrails.class);
    }
}
