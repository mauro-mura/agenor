package dev.agenor.runtime.discovery;

import java.util.Map;
import java.util.Set;

import dev.agenor.core.Agent;
import dev.agenor.core.context.AgentContext;
import dev.agenor.core.spi.AgentDiscoveryEngine;

/**
 * Default {@link AgentDiscoveryEngine}, wrapping {@link AgentScanner},
 * {@link AgentFactory}, and {@link AnnotationProcessor}. Discovered via
 * {@link java.util.ServiceLoader}.
 *
 * @since 0.25.0
 */
public final class DefaultAgentDiscoveryEngine implements AgentDiscoveryEngine {

    private AgentScanner agentScanner;
    private AgentFactory agentFactory;
    private AnnotationProcessor annotationProcessor;

    @Override
    public void initialize(AgentContext context) {
        this.agentScanner = new AgentScanner();
        this.agentFactory = new AgentFactory(context.messageDispatcher(), context.agentDirectory(),
                context.behaviorScheduler(), context.memoryStore());
        this.annotationProcessor = new AnnotationProcessor(context.messageDispatcher());
    }

    @Override
    public Set<Class<? extends Agent>> scanForAgents(String... packageNames) {
        return agentScanner.scanForAgents(packageNames);
    }

    @Override
    public Map<String, Agent> createAgents(Set<Class<? extends Agent>> agentClasses) {
        return agentFactory.createAgents(agentClasses);
    }

    @Override
    public <T extends Agent> T createAgent(Class<T> agentClass) {
        return agentFactory.createAgent(agentClass);
    }

    @Override
    public void processAnnotations(Agent agent) {
        annotationProcessor.processAnnotations(agent);
    }

    @Override
    public <T> void addService(Class<T> serviceClass, T instance) {
        agentFactory.addService(serviceClass, instance);
    }
}
