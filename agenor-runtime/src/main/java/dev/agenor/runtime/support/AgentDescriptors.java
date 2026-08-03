package dev.agenor.runtime.support;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.annotations.Agent;

/**
 * Builds an {@link AgentDescriptor} from an agent class's {@code @Agent}
 * annotation. Runs unconditionally for every registered agent (not gated by
 * package scanning), so it is reachable directly from
 * {@code AgenorRuntime.registerAgent()} without depending on
 * {@code agenor-runtime-scanning}. {@code AgentFactory} (in that module)
 * delegates here as well, so the logic has a single home.
 *
 * @since 0.25.0
 */
public final class AgentDescriptors {

    private AgentDescriptors() {}

    /**
     * Creates the {@link AgentDescriptor} for {@code agent}.
     *
     * @param agentClass the agent's class
     * @param agent      the agent instance
     * @return the built descriptor; never {@code null}
     */
    public static AgentDescriptor create(Class<? extends dev.agenor.core.Agent> agentClass,
                                          dev.agenor.core.Agent agent) {
        Agent annotation = agentClass.getAnnotation(Agent.class);

        if (annotation == null) {
            return AgentDescriptor.builder(agent.getAgentId())
                    .agentName(agent.getAgentName())
                    .agentType(agentClass.getSimpleName())
                    .status(AgentStatus.STOPPED)
                    .build();
        }

        // getAgentId() is the authoritative instance identity. Using it instead of the
        // annotation value ensures multi-instance agents (where getAgentId() returns
        // instance-specific IDs) are registered under the correct key in the directory.
        String agentId = agent.getAgentId();
        String agentType = annotation.type().trim().isEmpty()
                ? agentClass.getSimpleName() : annotation.type().trim();

        Set<String> capabilities = Arrays.stream(annotation.capabilities())
                .filter(c -> !c.trim().isEmpty())
                .collect(Collectors.toSet());

        Map<String, String> metadata = new HashMap<>();
        metadata.put("class", agentClass.getName());
        metadata.put("autoStart", String.valueOf(annotation.autoStart()));

        return AgentDescriptor.builder(agentId)
                .agentName(agent.getAgentName())
                .agentType(agentType)
                .capabilities(capabilities)
                .status(AgentStatus.STOPPED)
                .metadata(metadata)
                .build();
    }
}
