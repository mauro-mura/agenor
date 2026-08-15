package dev.agenor.runtime.hitl;

import dev.agenor.core.Agent;
import dev.agenor.core.hitl.RequiresApproval;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.RegistrationContext;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Wraps {@code @RequiresApproval}-annotated behaviors at agent registration time,
 * discovered via {@link java.util.ServiceLoader}.
 *
 * @see HitlAnnotationProcessor
 * @since 0.25.0
 */
public final class HitlRegistrationExtension implements AgentRegistrationExtension {

    private static final Logger log = LoggerFactory.getLogger(HitlRegistrationExtension.class);

    @Override
    public void onAgentRegistered(Agent agent, RegistrationContext context) {
        if (agent instanceof BaseAgent baseAgent) {
            HitlAnnotationProcessor.process(baseAgent, context.approvalGate());
        } else if (agent.getClass().isAnnotationPresent(RequiresApproval.class)) {
            // The runtime's startup diagnostic cannot catch this one: it sees the annotation
            // as claimed, because this module is on the classpath. Only this module knows the
            // behavior wrapping needs a BaseAgent.
            log.warn("@RequiresApproval on {} has no effect: behavior approval wrapping needs a "
                    + "BaseAgent subclass, and this agent is not one.", agent.getClass().getName());
        }
    }

    /**
     * @return {@code @RequiresApproval}, which this extension processes
     * @since 0.26.0
     */
    @Override
    public Set<Class<? extends Annotation>> handledAnnotations() {
        return Set.of(RequiresApproval.class);
    }
}
