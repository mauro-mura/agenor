package dev.agenor.runtime.discovery;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.AgenorRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.directory.AgentDirectory;
import dev.agenor.core.BehaviorScheduler;
import dev.agenor.core.context.AgentContext;
import dev.agenor.core.exceptions.AgentException;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.support.AgentDescriptors;

/**
 * Factory for creating agent instances from annotated classes.
 * Responsible for instantiation and descriptor creation only — service injection
 * is handled by {@link AgenorRuntime#registerAgent}.
 *
 * <p>Supports two agent styles:
 * <ul>
 *   <li><b>BaseAgent subclass</b> (classic): instantiated via constructor; services are
 *       injected by the runtime after creation.</li>
 *   <li><b>Plain Agent implementor</b>: core services available for constructor injection.
 *       Declare an {@link AgentContext} parameter to receive all core services at once,
 *       or individual service parameters ({@link MessageDispatcher}, {@link AgentDirectory}, etc.).</li>
 * </ul>
 */
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final MessageDispatcher messageDispatcher;
    private final AgentDirectory agentDirectory;
    private final BehaviorScheduler behaviorScheduler;
    private final MemoryStore memoryStore;
    private final Map<Class<?>, Object> availableServices;

    public AgentFactory(MessageDispatcher messageDispatcher,
                        AgentDirectory agentDirectory,
                        BehaviorScheduler behaviorScheduler,
                        MemoryStore memoryStore) {
        this.messageDispatcher = messageDispatcher;
        this.agentDirectory = agentDirectory;
        this.behaviorScheduler = behaviorScheduler;
        this.memoryStore = memoryStore;
        this.availableServices = new HashMap<>();

        // Register individual core services
        this.availableServices.put(MessageDispatcher.class, messageDispatcher);
        this.availableServices.put(AgentDirectory.class, agentDirectory);
        this.availableServices.put(BehaviorScheduler.class, behaviorScheduler);
        if (memoryStore != null) {
            this.availableServices.put(MemoryStore.class, memoryStore);
        }

        // Register AgentContext so non-BaseAgent agents can receive all services at once
        if (messageDispatcher != null && agentDirectory != null && behaviorScheduler != null) {
            this.availableServices.put(AgentContext.class,
                    new AgentContext(messageDispatcher, agentDirectory, behaviorScheduler, memoryStore));
        }
    }

    /**
     * Add a service instance for dependency injection
     */
    public <T> void addService(Class<T> serviceClass, T instance) {
        availableServices.put(serviceClass, instance);
        log.debug("Registered service: {} -> {}", serviceClass.getSimpleName(), instance.getClass().getSimpleName());
    }

    /**
     * Create agent instances from the given classes
     */
    public Map<String, dev.agenor.core.Agent> createAgents(Set<Class<? extends dev.agenor.core.Agent>> agentClasses) {
        Map<String, dev.agenor.core.Agent> agents = new HashMap<>();

        for (Class<? extends dev.agenor.core.Agent> agentClass : agentClasses) {
            try {
                dev.agenor.core.Agent agent = createAgent(agentClass);

                // Extract agent ID from annotation or use class name
                String agentId = extractAgentId(agentClass, agent);
                agents.put(agentId, agent);

                log.info("Created agent: {} from class: {}", agentId, agentClass.getSimpleName());

            } catch (Exception e) {
                log.error("Failed to create agent from class: {}", agentClass.getName(), e);
            }
        }

        return agents;
    }

    /**
     * Create a single agent instance and set its descriptor.
     * Service injection is performed separately by
     * {@link AgenorRuntime#registerAgent}.
     */
    public <T extends dev.agenor.core.Agent> T createAgent(Class<T> agentClass) throws AgentException {
        try {
            log.debug("Creating agent from class: {}", agentClass.getName());

            // Try constructor-based dependency injection
            T agent = tryConstructorInjection(agentClass);

            // Set descriptor with annotation metadata (BaseAgent only)
            if (agent instanceof BaseAgent baseAgent) {
                AgentDescriptor descriptor = createDescriptor(agentClass, agent);
                baseAgent.setAgentDescriptor(descriptor);
            }

            return agent;

        } catch (Exception e) {
            throw new AgentException("unknown", "Failed to create agent from class: " + agentClass.getName(), e);
        }
    }

    /**
     * Try to instantiate using constructor dependency injection
     */
    private <T extends dev.agenor.core.Agent> T tryConstructorInjection(Class<T> agentClass) throws Exception {
        Constructor<?>[] constructors = agentClass.getDeclaredConstructors();

        // Sort constructors by parameter count (try most specific first)
        java.util.Arrays.sort(constructors, (a, b) -> Integer.compare(b.getParameterCount(), a.getParameterCount()));

        for (Constructor<?> constructor : constructors) {
            Object[] args = resolveConstructorArguments(constructor);
            if (args != null) {
                constructor.setAccessible(true);
                @SuppressWarnings("unchecked")
                T instance = (T) constructor.newInstance(args);

                log.debug("Successfully instantiated {} using constructor with {} parameters",
                        agentClass.getSimpleName(), constructor.getParameterCount());
                return instance;
            }
        }

        throw new IllegalArgumentException("No suitable constructor found for agent class: " + agentClass.getName());
    }

    /**
     * Resolve constructor arguments from available services
     */
    private Object[] resolveConstructorArguments(Constructor<?> constructor) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];

        for (int i = 0; i < parameterTypes.length; i++) {
            Object service = availableServices.get(parameterTypes[i]);

            if (service != null) {
                args[i] = service;
            } else if (parameterTypes[i] == String.class) {
                // For String parameters, we might need agent ID - handle this case
                args[i] = null; // Will be resolved later if needed
            } else {
                // Cannot satisfy this constructor
                log.trace("Cannot resolve parameter {} of type {} for constructor",
                        i, parameterTypes[i].getSimpleName());
                return null;
            }
        }

        return args;
    }

    /**
     * Extract agent ID from annotation or agent instance
     */
    private String extractAgentId(Class<? extends dev.agenor.core.Agent> agentClass, dev.agenor.core.Agent agent) {
        Agent annotation = agentClass.getAnnotation(Agent.class);

        if (annotation != null && !annotation.value().trim().isEmpty()) {
            return annotation.value().trim();
        }

        // Fallback to agent's own ID
        return agent.getAgentId();
    }

    /**
     * Extract agent metadata from annotation
     */
    public AgentDescriptor createDescriptor(Class<? extends dev.agenor.core.Agent> agentClass, dev.agenor.core.Agent agent) {
        return AgentDescriptors.create(agentClass, agent);
    }

    /**
     * Scan for agent classes in the given packages.
     *
     * @param packageNames packages to scan
     * @return set of classes annotated with @Agent
     * @deprecated since 0.40.0, for removal in 1.0.0. Nothing calls it: discovery goes through
     *     {@link AgentScanner}, and this was a copy of it that had to be fixed twice. Use
     *     {@link AgentScanner#scanForAgents(String...)}.
     */
    @Deprecated(since = "0.40.0", forRemoval = true)
    public Set<Class<? extends dev.agenor.core.Agent>> scanForAgents(String... packageNames) {
        return new AgentScanner().scanForAgents(packageNames);
    }
}
