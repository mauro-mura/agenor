package dev.jentic.runtime.discovery;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jentic.core.Agent;
import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentDirectory;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.BehaviorScheduler;
import dev.jentic.core.MessageService;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.context.AgentContext;
import dev.jentic.core.exceptions.AgentException;
import dev.jentic.core.memory.MemoryStore;
import dev.jentic.runtime.agent.BaseAgent;

/**
 * Factory for creating agent instances from annotated classes.
 * Responsible for instantiation and descriptor creation only — service injection
 * is handled by {@link dev.jentic.runtime.JenticRuntime#registerAgent}.
 *
 * <p>Supports two agent styles:
 * <ul>
 *   <li><b>BaseAgent subclass</b> (classic): instantiated via constructor; services are
 *       injected by the runtime after creation.</li>
 *   <li><b>Plain Agent implementor</b>: core services available for constructor injection.
 *       Declare an {@link AgentContext} parameter to receive all core services at once,
 *       or individual service parameters ({@link MessageService}, {@link AgentDirectory}, etc.).</li>
 * </ul>
 */
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final MessageService messageService;
    private final AgentDirectory agentDirectory;
    private final BehaviorScheduler behaviorScheduler;
    private final MemoryStore memoryStore;
    private final Map<Class<?>, Object> availableServices;

    public AgentFactory(MessageService messageService,
                        AgentDirectory agentDirectory,
                        BehaviorScheduler behaviorScheduler,
                        MemoryStore memoryStore) {
        this.messageService = messageService;
        this.agentDirectory = agentDirectory;
        this.behaviorScheduler = behaviorScheduler;
        this.memoryStore = memoryStore;
        this.availableServices = new HashMap<>();

        // Register individual core services
        this.availableServices.put(MessageService.class, messageService);
        this.availableServices.put(AgentDirectory.class, agentDirectory);
        this.availableServices.put(BehaviorScheduler.class, behaviorScheduler);
        if (memoryStore != null) {
            this.availableServices.put(MemoryStore.class, memoryStore);
        }

        // Register AgentContext so non-BaseAgent agents can receive all services at once
        if (messageService != null && agentDirectory != null && behaviorScheduler != null) {
            this.availableServices.put(AgentContext.class,
                    new AgentContext(messageService, agentDirectory, behaviorScheduler, memoryStore));
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
    public Map<String, Agent> createAgents(Set<Class<? extends Agent>> agentClasses) {
        Map<String, Agent> agents = new HashMap<>();

        for (Class<? extends Agent> agentClass : agentClasses) {
            try {
                Agent agent = createAgent(agentClass);

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
     * {@link dev.jentic.runtime.JenticRuntime#registerAgent}.
     */
    public <T extends Agent> T createAgent(Class<T> agentClass) throws AgentException {
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
    private <T extends Agent> T tryConstructorInjection(Class<T> agentClass) throws Exception {
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
    private String extractAgentId(Class<? extends Agent> agentClass, Agent agent) {
        JenticAgent annotation = agentClass.getAnnotation(JenticAgent.class);

        if (annotation != null && !annotation.value().trim().isEmpty()) {
            return annotation.value().trim();
        }

        // Fallback to agent's own ID
        return agent.getAgentId();
    }

    /**
     * Extract agent metadata from annotation
     */
    public AgentDescriptor createDescriptor(Class<? extends Agent> agentClass, Agent agent) {
        JenticAgent annotation = agentClass.getAnnotation(JenticAgent.class);

        if (annotation == null) {
            // Minimal descriptor for non-annotated agents
            return AgentDescriptor.builder(agent.getAgentId())
                    .agentName(agent.getAgentName())
                    .agentType(getClass().getSimpleName())
                    .status(AgentStatus.STOPPED)
                    .build();
        }

        String agentId = annotation.value().trim().isEmpty() ? agent.getAgentId() : annotation.value().trim();
        String agentType = annotation.type().trim().isEmpty() ? agentClass.getSimpleName() : annotation.type().trim();

        Set<String> capabilities = java.util.Arrays.stream(annotation.capabilities())
                .filter(c -> !c.trim().isEmpty())
                .collect(java.util.stream.Collectors.toSet());

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

    /**
     * Scan for agent classes in the given packages
     *
     * @param packageNames packages to scan
     * @return set of classes annotated with @JenticAgent
     */
    public Set<Class<? extends Agent>> scanForAgents(String... packageNames) {
        Set<Class<? extends Agent>> agentClasses = new HashSet<>();

        for (String packageName : packageNames) {
            if (packageName == null || packageName.trim().isEmpty()) {
                continue;
            }

            log.debug("Scanning package for agents: {}", packageName);

            try {
                Set<Class<?>> classes = scanPackage(packageName);

                for (Class<?> clazz : classes) {
                    if (isAgentClass(clazz)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends Agent> agentClass = (Class<? extends Agent>) clazz;
                        agentClasses.add(agentClass);
                        log.info("Discovered agent class: {}", clazz.getName());
                    }
                }

            } catch (Exception e) {
                log.error("Error scanning package: {}", packageName, e);
            }
        }

        log.info("Agent discovery completed. Found {} agent classes", agentClasses.size());
        return agentClasses;
    }

    /**
     * Check if a class is a valid agent class
     */
    private boolean isAgentClass(Class<?> clazz) {
        return clazz.isAnnotationPresent(JenticAgent.class) &&
                Agent.class.isAssignableFrom(clazz) &&
                !clazz.isInterface();
    }

    /**
     * Scan all classes in a package
     */
    private Set<Class<?>> scanPackage(String packageName) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();
        String packagePath = packageName.replace('.', '/');

        // Try multiple classloaders
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                getClass().getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader classLoader : loaders) {
            if (classLoader == null) continue;

            Enumeration<URL> resources = classLoader.getResources(packagePath);

            if (!resources.hasMoreElements()) {
                log.debug("No resources found for package {} with classloader {}",
                        packageName, classLoader.getClass().getName());
                continue;
            }

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();

                if ("file".equals(protocol)) {
                    classes.addAll(scanFileSystem(resource, packageName));
                } else if ("jar".equals(protocol)) {
                    classes.addAll(scanJarFile(resource, packagePath));
                } else {
                    log.warn("Unsupported resource protocol for package scanning: {}", protocol);
                }
            }

            if (!classes.isEmpty()) break; // Found classes, stop trying
        }

        return classes;
    }

    /**
     * Scan classes from file system
     */
    private Set<Class<?>> scanFileSystem(URL resource, String packageName) throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();

        try {
            String path = URLDecoder.decode(resource.getFile(), StandardCharsets.UTF_8);
            File directory = new File(path);

            if (directory.exists() && directory.isDirectory()) {
                classes.addAll(findClassesInDirectory(directory, packageName));
            }
        } catch (Exception e) {
            log.error("Error scanning file system for package: {}", packageName, e);
        }

        return classes;
    }

    /**
     * Recursively find classes in directory
     */
    private Set<Class<?>> findClassesInDirectory(File directory, String packageName) throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();

        File[] files = directory.listFiles();
        if (files == null) {
            return classes;
        }

        for (File file : files) {
            String fileName = file.getName();

            if (file.isDirectory()) {
                String subPackageName = packageName + "." + fileName;
                classes.addAll(findClassesInDirectory(file, subPackageName));
            } else if (fileName.endsWith(".class") && !fileName.contains("$")) {
                String className = packageName + "." + fileName.substring(0, fileName.length() - 6);

                try {
                    Class<?> clazz = Class.forName(className);
                    classes.add(clazz);
                    log.trace("Found class: {}", className);
                } catch (ClassNotFoundException e) {
                    log.debug("Could not load class: {}", className, e);
                } catch (NoClassDefFoundError e) {
                    log.debug("Missing dependency for class: {}", className, e);
                }
            }
        }

        return classes;
    }

    /**
     * Scan classes from JAR file
     */
    private Set<Class<?>> scanJarFile(URL resource, String packagePath) throws IOException, ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<>();

        String jarPath = resource.getPath();
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring(5);
        }

        int separatorIndex = jarPath.indexOf("!");
        if (separatorIndex > 0) {
            jarPath = jarPath.substring(0, separatorIndex);
        }

        jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8);

        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(packagePath) &&
                        entryName.endsWith(".class") &&
                        !entryName.contains("$")) {

                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);

                    try {
                        Class<?> clazz = Class.forName(className);
                        classes.add(clazz);
                        log.trace("Found class in JAR: {}", className);
                    } catch (ClassNotFoundException e) {
                        log.debug("Could not load class from JAR: {}", className, e);
                    } catch (NoClassDefFoundError e) {
                        log.debug("Missing dependency for JAR class: {}", className, e);
                    }
                }
            }
        }

        return classes;
    }
}