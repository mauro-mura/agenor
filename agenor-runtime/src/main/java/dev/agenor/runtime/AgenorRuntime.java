package dev.agenor.runtime;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import dev.agenor.core.*;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.context.AgentContext;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalHandle;
import dev.agenor.core.hitl.NoopApprovalHandle;
import dev.agenor.core.spi.AgentDiscoveryEngine;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.BehaviorAnnotationExtension;
import dev.agenor.core.spi.DefaultLLMMemoryManagerProvider;
import dev.agenor.core.spi.HitlSupportProvider;
import dev.agenor.core.spi.RegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.directory.AgentDiscovery;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.messaging.LocalEndpointProvider;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.config.ConfigurationLoader;
import dev.agenor.core.config.ConfigurationException;
import dev.agenor.core.llm.LLMMemoryAware;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.memory.llm.LLMMemoryManager;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.annotation.AgentAnnotationProcessor;
import dev.agenor.runtime.config.DefaultConfigurationLoader;
import dev.agenor.runtime.directory.CompositeAgentDirectory;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.lifecycle.LifecycleListener;
import dev.agenor.runtime.lifecycle.LifecycleManager;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import dev.agenor.runtime.support.AgentDescriptors;

/**
 * Main runtime for the Agenor framework with automatic agent discovery.
 * Manages agent lifecycle, service discovery, and annotation processing.
 */
public class AgenorRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgenorRuntime.class);

    private final AgenorConfiguration configuration;
    private final MessageDispatcher messageDispatcher;
    private final AgentDirectory agentDirectory;
    private final BehaviorScheduler behaviorScheduler;
    private final MemoryStore memoryStore;
    private final Function<String, LLMMemoryManager> llmMemoryManagerFactory;
    private final AgenorTelemetry telemetry;

    // HITL: singleton gate and handle — shared across all agents
    private final ApprovalGate approvalGate;
    private final ApprovalHandle approvalService;

    // Optional-module extension points, resolved once via ServiceLoader (ADR-027)
    private final List<AgentRegistrationExtension> registrationExtensions;
    private final Optional<AgentDiscoveryEngine> discoveryEngine;
    private final Optional<HitlSupportProvider> hitlSupportProvider;
    private final Optional<DefaultLLMMemoryManagerProvider> llmMemoryManagerProvider;
    private final Optional<BehaviorAnnotationExtension> behaviorAnnotationExtension;

    // Always available regardless of which optional modules are present — handles
    // @AgenorMessageHandler and the core @Behavior types directly, delegates ext-only
    // types to behaviorAnnotationExtension when present (ADR-027 amendment)
    private final AgentAnnotationProcessor annotationProcessor;

    private final LifecycleManager lifecycleManager;

    // Configuration
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Map<String, dev.agenor.core.Agent> agents = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> serviceInstances = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    private AgenorRuntime(Builder builder) {
        // Configuration (from file or builder)
        this.configuration = builder.configuration;

        logConfigurationInfo();

        // Initialize services
        this.telemetry = builder.telemetry != null ? builder.telemetry : AgenorTelemetry.noop();

        // Directory: composite from per-capability setters, or in-memory default
        InMemoryAgentDirectory defaultDir = new InMemoryAgentDirectory(
                java.util.UUID.randomUUID().toString(), this.telemetry);
        if (builder.agentRegistry != null || builder.agentDiscovery != null
                || builder.agentResolver != null || builder.agentPresence != null) {
            this.agentDirectory = new CompositeAgentDirectory(
                    builder.agentRegistry != null ? builder.agentRegistry : defaultDir,
                    builder.agentDiscovery != null ? builder.agentDiscovery : defaultDir,
                    builder.agentResolver != null ? builder.agentResolver : defaultDir,
                    builder.agentPresence != null ? builder.agentPresence : defaultDir
            );
        } else {
            this.agentDirectory = defaultDir;
        }

        // Dispatcher: prefer explicitly provided dispatcher; default to InMemoryMessageDispatcher
        AgentResolver resolverForDispatcher = (this.agentDirectory instanceof AgentResolver ar)
                ? ar : defaultDir;
        this.messageDispatcher = builder.messageDispatcher != null
                ? builder.messageDispatcher
                : new InMemoryMessageDispatcher(resolverForDispatcher, this.telemetry);
        this.behaviorScheduler = builder.behaviorScheduler != null ?
                builder.behaviorScheduler : new SimpleBehaviorScheduler(4, this.telemetry);
        this.memoryStore = builder.memoryStore; // optional

        // Resolve optional-module extension points once (ADR-027). Any of these
        // collections/Optionals can be empty when agenor-runtime-llm/-ext/-scanning
        // is absent from the classpath — every call site below tolerates that.
        this.registrationExtensions = ServiceLoader.load(AgentRegistrationExtension.class)
                .stream().map(ServiceLoader.Provider::get).toList();
        this.discoveryEngine = ServiceLoader.load(AgentDiscoveryEngine.class).findFirst();
        this.hitlSupportProvider = ServiceLoader.load(HitlSupportProvider.class).findFirst();
        this.llmMemoryManagerProvider = ServiceLoader.load(DefaultLLMMemoryManagerProvider.class).findFirst();
        this.behaviorAnnotationExtension = ServiceLoader.load(BehaviorAnnotationExtension.class).findFirst();
        this.annotationProcessor = new AgentAnnotationProcessor(messageDispatcher, behaviorAnnotationExtension);

        this.llmMemoryManagerFactory = builder.llmMemoryManagerFactory != null ?
                builder.llmMemoryManagerFactory :
                this.createDefaultLLMMemoryManagerFactory();

        // HITL: use provided gate, or the optional provider's default, or none
        this.approvalGate = builder.approvalGate != null
                ? builder.approvalGate
                : hitlSupportProvider.map(HitlSupportProvider::createDefaultApprovalGate).orElse(null);
        this.approvalService = hitlSupportProvider
                .map(provider -> provider.createApprovalHandle(this.approvalGate))
                .orElseGet(NoopApprovalHandle::new);

        // Initialize the optional discovery engine, if present
        this.discoveryEngine.ifPresent(engine -> engine.initialize(
                new AgentContext(messageDispatcher, agentDirectory, behaviorScheduler, memoryStore)));
        this.lifecycleManager = new LifecycleManager();

        // Add default lifecycle listener
        this.lifecycleManager.addLifecycleListener(LifecycleListener.logging());

        this.serviceInstances.putAll(builder.serviceInstances);

        // Register additional services with the factory
        for (Map.Entry<Class<?>, Object> entry : serviceInstances.entrySet()) {
            registerServiceUnchecked(entry.getKey(), entry.getValue());
        }
    }

    /**
     *  Creates the default LLM memory manager factory.
     * @return a factory function that creates LLMMemoryManager instances per agent
     */
    private Function<String,LLMMemoryManager> createDefaultLLMMemoryManagerFactory() {
        if (memoryStore == null || llmMemoryManagerProvider.isEmpty()) return null;
        return agentId -> llmMemoryManagerProvider.get().create(memoryStore, agentId);
    }

    /**
     * Start the runtime and all registered agents
     */
    public CompletableFuture<Void> start() {
        if (running) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Starting Agenor Runtime...");

        return CompletableFuture.runAsync(() -> {
            try {
                // Start core services
                behaviorScheduler.start().join();

                // Discover and create agents
                if (!configuration.agents().getAllScanPackages().isEmpty()) {
                    discoverAndCreateAgents();
                }

                // Report which optional modules resolved, and any optional annotation that
                // nothing will act on — after discovery, so every agent is known (ADR-027)
                logOptionalModuleDiagnostics();

                // Process annotations for all agents
                processAgentAnnotations();

                // Start all agents
                startAllAgents();

                running = true;
                log.info("Agenor Runtime started successfully with {} agents", agents.size());

                // Log agent summary
                logAgentSummary();

            } catch (Exception e) {
                log.error("Failed to start Agenor Runtime", e);
                throw new RuntimeException("Failed to start runtime", e);
            }
        });
    }

    /**
     * Stop the runtime and all agents
     */
    public CompletableFuture<Void> stop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Stopping Agenor Runtime...");

        return CompletableFuture.runAsync(() -> {
            try {
                // Stop all agents
                List<CompletableFuture<Void>> stopFutures = new ArrayList<>();
                for (dev.agenor.core.Agent agent : agents.values()) {
                    if (agent.isRunning()) {
                        CompletableFuture<Void> stopFuture = lifecycleManager
                                .stopAgent(agent, DEFAULT_SHUTDOWN_TIMEOUT)
                                .thenCompose(v -> {
                                    // Unregister from the directory after a successful stop
                                    log.debug("Unregistering agent {} from directory", agent.getAgentId());
                                    return agentDirectory.unregister(agent.getAgentId());
                                })
                                .exceptionally(throwable -> {
                                    log.error("Failed to stop agent: {} - {}",
                                            agent.getAgentId(), throwable.getMessage());
                                    return null;
                                });
                        stopFutures.add(stopFuture);
                    }
                }

                // Wait for all agents to stop
                CompletableFuture.allOf(stopFutures.toArray(new CompletableFuture[0])).join();

                // Stop core services
                behaviorScheduler.stop().join();

                // Flush and shut down telemetry (forces BatchSpanProcessor export)
                if (telemetry instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception e) {
                        log.warn("Error closing telemetry during shutdown", e);
                    }
                }

                running = false;
                log.info("Agenor Runtime stopped successfully");

            } catch (Exception e) {
                log.error("Error stopping Agenor Runtime", e);
            }
        });
    }

    /**
     * Get an agent by ID
     */
    public Optional<dev.agenor.core.Agent> getAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /**
     * Get all registered agents
     */
    public Collection<dev.agenor.core.Agent> getAgents() {
        return Collections.unmodifiableCollection(agents.values());
    }

    /**
     * Register a new agent instance
     */
    public void registerAgent(dev.agenor.core.Agent agent) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        String agentId = agent.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            agentId = java.util.UUID.randomUUID().toString();
            log.warn("Agent ID not set. Generated random ID: {}", agentId);
        }
        agents.put(agentId, agent);

        // Configure agent services
        if (agent instanceof BaseAgent baseAgent) {
            baseAgent.setMessageDispatcher(messageDispatcher);
            baseAgent.setAgentDirectory(agentDirectory);
            baseAgent.setBehaviorScheduler(behaviorScheduler);
            if (memoryStore != null) {
                baseAgent.setMemoryStore(memoryStore);
            }
        }
        if (agent instanceof LLMMemoryAware llmAware && llmMemoryManagerFactory != null) {
            llmAware.setLLMMemoryManager(llmMemoryManagerFactory.apply(agent.getAgentId()));
        }

        // Optional-module hooks (LLM guardrails/telemetry, HITL behavior wrapping, ...),
        // discovered via ServiceLoader — no-op when the owning module is absent (ADR-027)
        RegistrationContext registrationContext = new RegistrationContext(telemetry, approvalGate);
        for (AgentRegistrationExtension extension : registrationExtensions) {
            extension.onAgentRegistered(agent, registrationContext);
        }

        // Create a descriptor and register in a directory.
        // If the dispatcher knows how agents on this node are reached from other nodes, stamp
        // that endpoint on the descriptor: the directory is where a peer looks it up, and a
        // networked transport that advertises nothing routes cross-node messages into the void.
        AgentDescriptor descriptor = AgentDescriptors.create(agent.getClass(), agent);
        if (messageDispatcher instanceof LocalEndpointProvider provider) {
            descriptor = provider.localEndpoint()
                    .map(descriptor::withEndpoint)
                    .orElse(descriptor);
        }

        // Hand the same descriptor to the agent. BaseAgent.start() re-registers itself as RUNNING
        // from its own copy, so an agent left holding the bare default it builds in its constructor
        // would overwrite this row moments later — losing the annotation-derived type and
        // capabilities, and the endpoint that makes it reachable from another node. Agents created
        // through AgentFactory already get one; those registered as plain instances did not.
        if (agent instanceof BaseAgent baseAgent) {
            baseAgent.setAgentDescriptor(descriptor);
        }

        agentDirectory.register(descriptor)
                .exceptionally(throwable -> {
                    log.error("Failed to register agent {} in directory: {}",
                            agent.getAgentId(), throwable.getMessage());
                    return null;
                });

        log.info("Registered agent: {} ({}) in runtime and directory",
                agent.getAgentName(), agent.getAgentId());
    }

    /**
     * Create an agent from a class using annotation discovery
     */
    public <T extends dev.agenor.core.Agent> T createAgent(Class<T> agentClass) {
        AgentDiscoveryEngine engine = discoveryEngine.orElseThrow(() -> new IllegalStateException(
                "createAgent(Class) requires agenor-runtime-scanning on the classpath"));
        try {
            T agent = engine.createAgent(agentClass);
            Objects.requireNonNull(agent, "Factory returned null agent for class: " + agentClass.getName());

            registerAgent(agent);

            // Process annotations if runtime is already started
            if (running) {
                annotationProcessor.processAnnotations(agent);
            }

            return agent;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create agent: " + agentClass.getName(), e);
        }
    }

    /**
     * Get the agent directory
     */
    public AgentDirectory getAgentDirectory() {
        return agentDirectory;
    }

    /**
     * Returns the {@link MessageDispatcher} for this runtime.
     *
     * @return the message dispatcher; never null
     * @since 0.20.0
     */
    public MessageDispatcher getMessageDispatcher() {
        return messageDispatcher;
    }

    /**
     * Get the behavior scheduler
     */
    public BehaviorScheduler getBehaviorScheduler() {
        return behaviorScheduler;
    }

    /**
     * Returns an {@link AgentContext} wrapping the core runtime services.
     *
     * <p>Useful when manually instantiating agents that implement {@link dev.agenor.core.Agent}
     * directly (without extending {@code BaseAgent}) and need all services
     * in a single object:
     *
     * <pre>{@code
     * var agent = new MyDomainAgent(runtime.getAgentContext());
     * runtime.registerAgent(agent);
     * }</pre>
     *
     * <p>When agents are discovered via package scanning, {@code AgentFactory}
     * builds and injects the context automatically — this method is only needed
     * for manually registered agents.
     *
     * @return a new {@link AgentContext} built from the current runtime services
     * @since 0.10.0
     */
    public AgentContext getAgentContext() {
        return new AgentContext(messageDispatcher, agentDirectory, behaviorScheduler, memoryStore);
    }

    /**
     * Check if runtime is currently running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the lifecycle manager
     */
    public LifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Get the current configuration
     */
    public AgenorConfiguration getConfiguration() {
        return configuration;
    }
    /**
     * Get runtime statistics
     */
    public RuntimeStats getStats() {
        long runningAgents = agents.values().stream().mapToLong(agent -> agent.isRunning() ? 1 : 0).sum();

        return new RuntimeStats(
                agents.size(),
                (int) runningAgents,
                configuration.agents().getAllScanPackages().size(),
                serviceInstances.size()
        );
    }

    /**
     * Returns the {@link AgenorTelemetry} instance wired into this runtime.
     *
     * @return the telemetry instance; never {@code null} (falls back to noop)
     * @since 0.19.0
     */
    public AgenorTelemetry getTelemetry() {
        return telemetry;
    }

    /**
     * Returns the singleton {@link ApprovalHandle} for this runtime.
     *
     * <p>External systems (HTTP handlers, tests, webhooks) call this handle to
     * submit human approval decisions for pending {@code HumanCheckpointBehavior}
     * executions.
     *
     * <p>Returns a {@link NoopApprovalHandle} when no HITL provider (e.g.
     * {@code agenor-runtime-ext}) is on the classpath; its mutating methods throw
     * {@link UnsupportedOperationException}.
     *
     * @return the approval handle; never {@code null}
     * @since 0.13.0
     */
    public ApprovalHandle getApprovalService() {
        return approvalService;
    }

    /**
     * Helper method to register services with type erasure
     */
    @SuppressWarnings("unchecked")
    private <T> void registerServiceUnchecked(Class<?> serviceClass, Object instance) {
        discoveryEngine.ifPresent(engine -> engine.addService((Class<T>) serviceClass, (T) instance));
    }

    // ========== PRIVATE METHODS ==========

    /**
     * Log configuration information
     */
    private void logConfigurationInfo() {
        log.debug("Runtime Configuration:");
        log.debug("  Name: {}", configuration.runtime().name());
        log.debug("  Environment: {}", configuration.runtime().environment());

        if (!configuration.runtime().properties().isEmpty()) {
            log.debug("  Properties:");
            configuration.runtime().properties().forEach((key, value) ->
                    log.debug("    {}: {}", key, value)
            );
        }

        log.debug("Agent Configuration:");
        log.debug("  Auto Discovery: {}", configuration.agents().autoDiscovery());
        log.debug("  Scan Packages: {}", configuration.agents().getAllScanPackages());

        if (!configuration.agents().properties().isEmpty()) {
            log.debug("  Properties:");
            configuration.agents().properties().forEach((key, value) ->
                    log.debug("    {}: {}", key, value)
            );
        }
    }

    /**
     * Reports the state of the optional-module seam once per {@link #start()}.
     *
     * <p>Two things degrade silently without this. Which optional modules actually resolved is
     * invisible — {@code ServiceLoader} finding nothing looks exactly like finding everything
     * from the outside — so one INFO line names them. And an annotation whose only processor
     * lives in an absent module is simply never acted on, leaving an agent running without the
     * protection it declares; each such annotation gets one aggregated WARN naming the agents,
     * rather than one line per agent.
     *
     * <p>The runtime deliberately does not name the module to install: {@code agenor-core}
     * knows the annotation types (they are its own) but not which artifact ships their
     * processor, and teaching it would be exactly the coupling ADR-027 removed.
     */
    private void logOptionalModuleDiagnostics() {
        log.info("Optional modules — registration extensions: {}; agent discovery: {}; "
                        + "HITL support: {}; LLM memory: {}; ext behaviors: {}",
                registrationExtensions.isEmpty() ? "none" : registrationExtensions.stream()
                        .map(e -> e.getClass().getSimpleName()).toList(),
                describe(discoveryEngine), describe(hitlSupportProvider),
                describe(llmMemoryManagerProvider), describe(behaviorAnnotationExtension));

        Set<Class<? extends Annotation>> claimed = registrationExtensions.stream()
                .flatMap(extension -> extension.handledAnnotations().stream())
                .collect(java.util.stream.Collectors.toSet());

        for (Class<? extends Annotation> annotation
                : AgentRegistrationExtension.OPTIONAL_FEATURE_ANNOTATIONS) {
            if (claimed.contains(annotation)) {
                continue;
            }
            // getAnnotation() on the concrete class, matching how the processors themselves
            // look it up: these annotations are not @Inherited, so a hierarchy walk here would
            // warn about agents whose annotation was never going to be read anyway.
            List<String> declaringAgents = agents.values().stream()
                    .map(agent -> agent.getClass())
                    .filter(clazz -> clazz.getAnnotation(annotation) != null)
                    .map(Class::getSimpleName)
                    .distinct()
                    .toList();
            if (declaringAgents.isEmpty()) {
                continue;
            }
            log.warn("{} agent(s) declare @{} but no registered AgentRegistrationExtension "
                            + "handles it — the annotation will have no effect. Agents: {}. "
                            + "The module providing this feature is probably missing from the "
                            + "classpath.",
                    declaringAgents.size(), annotation.getSimpleName(),
                    String.join(", ", declaringAgents));
        }
    }

    private static String describe(Optional<?> resolved) {
        return resolved.map(value -> value.getClass().getSimpleName()).orElse("absent");
    }

    /**
     * Discover agents from configured packages and create instances
     */
    private void discoverAndCreateAgents() {
        AgentDiscoveryEngine engine = discoveryEngine.orElseThrow(() -> new IllegalStateException(
                "Package scanning requires agenor-runtime-scanning on the classpath"));

        List<String> packages = configuration.agents().getAllScanPackages();
        log.info("Starting agent discovery in {} packages", packages.size());

        // Scan for agent classes
        String[] packageArray = packages.toArray(new String[0]);
        Set<Class<? extends dev.agenor.core.Agent>> agentClasses = engine.scanForAgents(packageArray);

        if (agentClasses.isEmpty()) {
            log.info("No agent classes found in scanned packages");
            return;
        }

        // Create agent instances
        Map<String, dev.agenor.core.Agent> discoveredAgents = engine.createAgents(agentClasses);

        // Register discovered agents
        for (dev.agenor.core.Agent agent : discoveredAgents.values()) {
            registerAgent(agent);
        }

        log.info("Agent discovery completed. Created {} agents from {} classes",
                discoveredAgents.size(), agentClasses.size());
    }

    /**
     * Process annotations for all registered agents.
     *
     * <p>Runs unconditionally regardless of which optional modules are present —
     * {@code @AgenorMessageHandler} and the core {@code @Behavior} types have no
     * optional-module dependency. An ext-only {@code @Behavior} type used without
     * {@code agenor-runtime-ext} on the classpath fails {@link #start()} entirely
     * (see {@link AgentAnnotationProcessor}) rather than being skipped for just that
     * agent — a missing module is an application-wide configuration error, not a
     * per-agent one.
     */
    private void processAgentAnnotations() {
        log.info("Processing annotations for {} agents", agents.size());

        for (dev.agenor.core.Agent agent : agents.values()) {
            try {
                annotationProcessor.processAnnotations(agent);
            } catch (IllegalStateException e) {
                // missing agenor-runtime-ext for an ext-only @Behavior type — fail all
                // of start(), not just this agent
                throw e;
            } catch (Exception e) {
                log.error("Failed to process annotations for agent: {}", agent.getAgentId(), e);
            }
        }

        log.info("Annotation processing completed");
    }

    /**
     * Start all registered agents
     */
    private void startAllAgents() {
        List<CompletableFuture<Void>> startFutures = new ArrayList<>();

        for (dev.agenor.core.Agent agent : agents.values()) {
            // Check if the agent should auto-start
            if (shouldAutoStart(agent)) {
                // Use LifecycleManager for proper state tracking and timeout handling
                CompletableFuture<Void> startFuture = lifecycleManager
                        .startAgent(agent, DEFAULT_STARTUP_TIMEOUT)
                        .thenCompose(v -> {
                            // Update agent status in the directory after a successful start
                            log.debug("Updating agent {} status to RUNNING in directory",
                                    agent.getAgentId());
                            return agentDirectory.updateStatus(agent.getAgentId(), AgentStatus.RUNNING);
                        })
                        .exceptionally(throwable -> {
                            log.error("Failed to start agent: {} - {}",
                                    agent.getAgentId(), throwable.getMessage());
                            // Update status to ERROR in the directory
                            agentDirectory.updateStatus(agent.getAgentId(), AgentStatus.ERROR)
                                    .exceptionally(ex -> {
                                        log.warn("Could not update error status for agent {}",
                                                agent.getAgentId());
                                        return null;
                                    });
                            // Don't fail the entire startup for one agent
                            return null;
                        });
                startFutures.add(startFuture);
            }
        }

        // Wait for all agents to start
        CompletableFuture.allOf(startFutures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Check if the agent should auto-start based on annotation
     */
    private boolean shouldAutoStart(dev.agenor.core.Agent agent) {
        Agent annotation = agent.getClass().getAnnotation(Agent.class);
        return annotation == null || annotation.autoStart();
    }

    /**
     * Log summary of discovered and registered agents
     */
    private void logAgentSummary() {
        if (agents.isEmpty()) {
            log.info("No agents registered");
            return;
        }

        log.info("Agent Summary:");
        agents.values().forEach(agent -> {
            Agent annotation = agent.getClass().getAnnotation(Agent.class);
            String type = annotation != null ? annotation.type() : "unknown";
            String[] capabilities = annotation != null ? annotation.capabilities() : new String[0];

            log.info("  - {} ({}) - Type: {}, Capabilities: [{}], Running: {}",
                    agent.getAgentName(),
                    agent.getAgentId(),
                    type.isEmpty() ? agent.getClass().getSimpleName() : type,
                    String.join(", ", capabilities),
                    agent.isRunning());
        });
    }

    /**
     * Unregister an agent from runtime and directory
     */
    public CompletableFuture<Void> unregisterAgent(String agentId) {
        dev.agenor.core.Agent agent = agents.remove(agentId);

        if (agent == null) {
            log.warn("Attempted to unregister non-existent agent: {}", agentId);
            return CompletableFuture.completedFuture(null);
        }

        log.info("Unregistering agent: {} ({})", agent.getAgentName(), agentId);

        // Stop if running
        CompletableFuture<Void> stopFuture = agent.isRunning()
                ? agent.stop()
                : CompletableFuture.completedFuture(null);

        // Then unregister from the directory
        return stopFuture
                .thenCompose(v -> agentDirectory.unregister(agentId))
                .exceptionally(throwable -> {
                    log.error("Error unregistering agent {}: {}",
                            agentId, throwable.getMessage());
                    return null;
                });
    }

    // ========== BUILDER ==========

    /**
     * Create a new runtime builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AgenorConfiguration configuration;
        private MessageDispatcher messageDispatcher;
        private AgentRegistry agentRegistry;
        private AgentResolver agentResolver;
        private AgentDiscovery agentDiscovery;
        private AgentPresence agentPresence;
        private BehaviorScheduler behaviorScheduler;
        private MemoryStore memoryStore;
        private Function<String, LLMMemoryManager> llmMemoryManagerFactory;
        private AgenorTelemetry telemetry;
        private ApprovalGate approvalGate;
        private final Set<String> scanPackages = new HashSet<>();
        private final Map<Class<?>, Object> serviceInstances = new HashMap<>();

        /**
         * Load configuration from the YAML / JSON file
         *
         * @param configPath path to a configuration file
         * @return this builder
         * @throws ConfigurationException if loading fails or the configuration is invalid
         */
        public Builder fromFilesystemConfig(String configPath) {
            ConfigurationLoader loader = new DefaultConfigurationLoader();
            this.configuration = loader.loadFromFile(configPath);
            loader.validate(this.configuration);
            log.info("Loaded configuration from file: {}", configPath);
            return this;
        }

        /**
         * Load configuration from a classpath resource
         *
         * @param resourcePath classpath resource path
         * @return this builder
         * @throws ConfigurationException if loading fails
         */
        public Builder fromClasspathConfig(String resourcePath) {
            ConfigurationLoader loader = new DefaultConfigurationLoader();
            this.configuration = loader.loadFromClasspath(resourcePath);
            loader.validate(this.configuration);
            log.info("Loaded configuration from classpath: {}", resourcePath);
            return this;
        }

        /**
         * Use the provided configuration object
         *
         * @param config the configuration to use
         * @return this builder
         * @throws ConfigurationException if the configuration is null or invalid
         */
        public Builder withConfiguration(AgenorConfiguration config) {
            if (config == null) {
                throw new ConfigurationException("Configuration cannot be null");
            }
            new DefaultConfigurationLoader().validate(config);
            this.configuration = config;
            log.info("Using provided configuration: {}", config.runtime().name());
            return this;
        }

        /**
         * Load the default configuration (agenor.yml from filesystem or classpath)
         *
         * @return this builder
         * @throws ConfigurationException if loading fails or the configuration is invalid
         */
        public Builder withDefaultConfig() {
            ConfigurationLoader loader = new DefaultConfigurationLoader();
            this.configuration = loader.loadDefault();
            loader.validate(this.configuration);
            log.info("Loaded default configuration");
            return this;
        }

        /**
         * Sets the message dispatcher.
         *
         * @param dispatcher the dispatcher to use; must not be null
         * @return this builder
         * @since 0.20.0
         */
        public Builder messageDispatcher(MessageDispatcher dispatcher) {
            this.messageDispatcher = dispatcher;
            return this;
        }

        /**
         * Sets the agent registry capability.
         *
         * <p>When individual capability setters are used, the runtime assembles a composite
         * directory from all provided capabilities.
         *
         * @param registry the registry implementation; must not be null
         * @return this builder
         * @since 0.20.0
         */
        public Builder agentRegistry(AgentRegistry registry) {
            this.agentRegistry = registry;
            return this;
        }

        /**
         * Sets the agent resolver capability.
         *
         * @param resolver the resolver implementation; must not be null
         * @return this builder
         * @since 0.20.0
         */
        public Builder agentResolver(AgentResolver resolver) {
            this.agentResolver = resolver;
            return this;
        }

        /**
         * Sets the agent discovery capability.
         *
         * @param discovery the discovery implementation; must not be null
         * @return this builder
         * @since 0.20.0
         */
        public Builder agentDiscovery(AgentDiscovery discovery) {
            this.agentDiscovery = discovery;
            return this;
        }

        /**
         * Sets the agent presence capability.
         *
         * @param presence the presence implementation; must not be null
         * @return this builder
         * @since 0.20.0
         */
        public Builder agentPresence(AgentPresence presence) {
            this.agentPresence = presence;
            return this;
        }

        public Builder behaviorScheduler(BehaviorScheduler behaviorScheduler) {
            this.behaviorScheduler = behaviorScheduler;
            return this;
        }

        public Builder memoryStore(MemoryStore memoryStore) {
           this.memoryStore = memoryStore;
           return this;
        }

        public Builder llmMemoryManagerFactory(Function<String, LLMMemoryManager> factory) {
            this.llmMemoryManagerFactory = factory;
            return this;
        }

        /**
         * Sets the telemetry instance used to emit spans for LLM calls, guardrails,
         * behavior execution, and HITL approvals.
         *
         * @param telemetry the telemetry instance; {@code null} uses noop
         * @return {@code this}
         * @since 0.19.0
         */
        public Builder telemetry(AgenorTelemetry telemetry) {
            this.telemetry = telemetry;
            return this;
        }

        /**
         * Sets the approval gate used for HITL workflows.
         *
         * <p>Defaults to an in-memory gate supplied by {@code agenor-runtime-ext}
         * when not set, or {@code null} if that module is absent. Provide a
         * persistent implementation (e.g. {@code JdbcApprovalGate}) to survive
         * JVM restarts.
         *
         * @param gate the approval gate; must not be null
         * @return {@code this}
         * @since 0.23.0
         */
        public Builder approvalGate(ApprovalGate gate) {
            this.approvalGate = gate;
            return this;
        }

        public Builder scanPackage(String packageName) {
            if (packageName != null && !packageName.trim().isEmpty()) {
                this.scanPackages.add(packageName.trim());
            }
            return this;
        }

        public Builder scanPackages(String... packageNames) {
            for (String packageName : packageNames) {
                scanPackage(packageName);
            }
            return this;
        }

        public Builder scanPackages(Collection<String> packageNames) {
            for (String packageName : packageNames) {
                scanPackage(packageName);
            }
            return this;
        }

        public <T> Builder service(Class<T> serviceClass, T instance) {
            if (serviceClass != null && instance != null) {
                this.serviceInstances.put(serviceClass, instance);
            }
            return this;
        }

        public AgenorRuntime build() {
            // Use default config if none provided
            if (this.configuration == null) {
                log.debug("No configuration provided, using defaults");
                this.configuration = AgenorConfiguration.defaults();
            }

            // Merge builder scan packages with configuration
            if (!scanPackages.isEmpty()) {
                log.debug("Merging {} builder scan packages with configuration", scanPackages.size());

                List<String> allPackages = new ArrayList<>(configuration.agents().getAllScanPackages());
                allPackages.addAll(scanPackages);

                AgenorConfiguration.AgentsConfig updatedAgentsConfig =
                        new AgenorConfiguration.AgentsConfig(
                                configuration.agents().autoDiscovery(),
                                configuration.agents().basePackage(),
                                configuration.agents().scanPaths(),
                                allPackages,  // scanPackages
                                configuration.agents().properties()
                        );

                this.configuration = new AgenorConfiguration(
                        configuration.runtime(),
                        updatedAgentsConfig,
                        configuration.messaging(),
                        configuration.directory(),
                        configuration.scheduler()
                );
            }
            return new AgenorRuntime(this);
        }
    }

    // ========== RUNTIME STATS ==========

    /**
     * Runtime statistics record
     */
    public record RuntimeStats(
            int totalAgents,
            int runningAgents,
            int scannedPackages,
            int registeredServices
    ) {
        @Override
        public String toString() {
            return String.format("RuntimeStats[agents=%d/%d, packages=%d, services=%d]",
                    runningAgents, totalAgents, scannedPackages, registeredServices);
        }
    }
}
