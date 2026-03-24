package dev.jentic.runtime.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.jentic.core.context.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jentic.core.Agent;
import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentDirectory;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorScheduler;
import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.MessageService;
import dev.jentic.core.memory.MemoryEntry;
import dev.jentic.core.memory.MemoryQuery;
import dev.jentic.core.memory.MemoryScope;
import dev.jentic.core.memory.MemoryStats;
import dev.jentic.core.memory.MemoryStore;
import dev.jentic.runtime.behavior.BaseBehavior;
import dev.jentic.runtime.messaging.InMemoryMessageService;

/**
 * Base implementation for all agents in the Jentic framework.
 * 
 * <p>Provides core functionality including:
 * <ul>
 *   <li>Lifecycle management (start/stop)</li>
 *   <li>Message handling</li>
 *   <li>Behavior scheduling</li>
 *   <li>Agent directory integration</li>
 *   <li>Memory management (since 0.6.0)</li>
 * </ul>
 * 
 * <p>All concrete agents should extend this class and implement
 * the required lifecycle methods.
 * 
 * <p><b>Memory Support (since 0.6.0):</b>
 * Agents can optionally use memory features by calling memory methods.
 * Memory features are only available if {@link #setMemoryStore(MemoryStore)}
 * is called during initialization.
 * 
 * <p>Example usage:
 * <pre>{@code
 * @JenticAgent("my-agent")
 * public class MyAgent extends BaseAgent {
 *     
 *     @Override
 *     protected void onStart() {
 *         // Use memory if available
 *         rememberShort("session-id", "abc123", Duration.ofMinutes(30));
 *         rememberLong("user-preference", "dark-mode");
 *     }
 *     
 *     @JenticMessageHandler("process-request")
 *     public void handleRequest(Message message) {
 *         recall("session-id", MemoryScope.SHORT_TERM)
 *             .thenAccept(sessionId -> {
 *                 log.info("Processing for session: {}", sessionId);
 *             });
 *     }
 * }
 * }</pre>
 * 
 * @since 0.1.0
 */
public abstract class BaseAgent implements Agent {
    
    protected final Logger log = LoggerFactory.getLogger(getClass());
    
    private final String agentId;
    private final String agentName;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Behavior> behaviors = new ConcurrentHashMap<>();
    private volatile AgentStatus currentStatus = AgentStatus.STOPPED;

    private String directMessageSubscriptionId;

    // Lifecycle hooks - thread-safe collections
    private final List<Runnable> startHooks = new CopyOnWriteArrayList<>();
    private final List<Runnable> stopHooks = new CopyOnWriteArrayList<>();

    // Core services (injected by runtime)
    protected MessageService messageService;
    protected BehaviorScheduler behaviorScheduler;
    protected AgentDirectory agentDirectory;
    
    // Memory support (injected by runtime, optional - since 0.6.0)
    protected MemoryStore memoryStore;
    private String memoryNamespace;

    protected AgentDescriptor agentDescriptor;
    
    /**
     * Create an agent with auto-generated ID
     */
    protected BaseAgent() {
        this(UUID.randomUUID().toString());
    }
    
    /**
     * Create agent with specific ID
     */
    protected BaseAgent(String agentId) {
        this(agentId, agentId);
    }
    
    /**
     * Create an agent with ID and name
     * @param agentId the agent identifier
     * @param agentName the agent display name
     */
    protected BaseAgent(String agentId, String agentName) {
        this.agentId = agentId;
        this.agentName = agentName;
        
        this.agentDescriptor = createDefaultDescriptor();
        this.memoryNamespace = "agent:" + agentId + ":";
    }

    /**
     * Create an agent with a specific ID, receiving all core services via {@link AgentContext}.
     *
     * <p>Convenience for subclasses that want to accept an {@code AgentContext}
     * in their own constructor and propagate it upward:
     *
     * <pre>{@code
     * public class MyAgent extends BaseAgent {
     *     public MyAgent(AgentContext ctx) {
     *         super("my-agent", ctx);
     *     }
     * }
     * }</pre>
     *
     * @param agentId the agent identifier
     * @param ctx     the aggregated core services, not null
     * @since 0.10.0
     */
    protected BaseAgent(String agentId, AgentContext ctx) {
        this(agentId, agentId, ctx);
    }

    /**
     * Create an agent with ID, name, and all core services via {@link AgentContext}.
     *
     * @param agentId   the agent identifier
     * @param agentName the agent display name
     * @param ctx       the aggregated core services, not null
     * @since 0.10.0
     */
    protected BaseAgent(String agentId, String agentName, AgentContext ctx) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.agentDescriptor = createDefaultDescriptor();
        this.memoryNamespace = "agent:" + agentId + ":";

        // Pre-wire services from context; AgentFactory setter-injection is still
        // called for BaseAgent subclasses and will be a no-op (overwrite with same refs).
        this.messageService = ctx.messageService();
        this.agentDirectory = ctx.agentDirectory();
        this.behaviorScheduler = ctx.behaviorScheduler();
        this.memoryStore = ctx.memoryStore();
    }
    
    @Override
    public String getAgentId() {
        return agentId;
    }
    
    @Override
    public String getAgentName() {
        return agentName;
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public CompletableFuture<Void> start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting agent: {} ({})", agentName, agentId);
            currentStatus = AgentStatus.STARTING;

            return CompletableFuture.runAsync(() -> {
                try {
                    // Initialize services if not already set
                    initializeServices();

                    // Auto-Subscribe to direct messages
                    autoSubscribeDirectMessages();

                    // Register with directory
                    registerWithDirectory();
                    
                    // Start behaviors
                    startBehaviors();
                    
                    // Call lifecycle hook
                    onStart();

                    // Execute registered start hooks (e.g., for persistence)
                    executeStartHooks();

                    currentStatus = AgentStatus.RUNNING;
                    log.info("Agent started successfully: {} ({})", agentName, agentId);
                    
                } catch (Exception e) {
                    currentStatus = AgentStatus.ERROR;
                    running.set(false);
                    log.error("Failed to start agent: {}", agentId, e);
                    throw new RuntimeException("Failed to start agent: " + agentId, e);
                }
            });
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping agent: {} ({})", agentName, agentId);
            currentStatus = AgentStatus.STOPPING;
            
            return CompletableFuture.runAsync(() -> {
                try {
                    // Execute registered stop hooks FIRST (e.g., for persistence)
                    executeStopHooks();

                    // Call lifecycle hook
                    onStop();
                    
                    // Stop behaviors
                    stopBehaviors();

                    // Unsubscribe from direct messages
                    unsubscribeDirectMessages();
                    
                    // Unregister from directory
                    unregisterFromDirectory();

                    currentStatus = AgentStatus.STOPPED;
                    log.info("Agent stopped successfully: {} ({})", agentName, agentId);
                    
                } catch (Exception e) {
                    currentStatus = AgentStatus.ERROR;
                    log.error("Error stopping agent: " + agentId, e);
                }
            });
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public void addBehavior(Behavior behavior) {
        behaviors.put(behavior.getBehaviorId(), behavior);
        if (running.get() && behaviorScheduler != null) {
            behaviorScheduler.schedule(behavior);
        }
    }
    
    @Override
    public void removeBehavior(String behaviorId) {
        Behavior behavior = behaviors.remove(behaviorId);
        if (behavior != null) {
            behavior.stop();
            if (behaviorScheduler != null) {
                behaviorScheduler.cancel(behaviorId);
            }
        }
    }

    /**
     * Returns a snapshot of all behaviors currently registered on this agent.
     *
     * <p>The returned list is a point-in-time copy; modifications to it do not
     * affect the agent's behavior collection. Used by
     * {@code HitlAnnotationProcessor} during agent registration.
     *
     * @return immutable snapshot of registered behaviors
     * @since 0.13.0
     */
    public List<Behavior> getBehaviors() {
        return List.copyOf(behaviors.values());
    }


    @Override
    public MessageService getMessageService() {
        return messageService;
    }
    
    /**
     * Set the message service for this agent
     */
    public void setMessageService(MessageService messageService) {
        this.messageService = messageService;
    }
    
    /**
     * Set the behavior scheduler for this agent
     */
    public void setBehaviorScheduler(BehaviorScheduler behaviorScheduler) {
        this.behaviorScheduler = behaviorScheduler;
    }

    /**
     * Get current agent status
     */
    public AgentStatus getStatus() {
        return currentStatus;
    }

    /**
     * Set the agent directory for this agent
     */
    public void setAgentDirectory(AgentDirectory agentDirectory) {
        this.agentDirectory = agentDirectory;
    }
    
    /**
     * Method for AgentFactory to set descriptor
     */
    public void setAgentDescriptor(AgentDescriptor descriptor) {
        this.agentDescriptor = descriptor;
        log.debug("Agent descriptor set: type={}, capabilities={}", 
                 descriptor.agentType(), descriptor.capabilities());
    }

    /**
     * Injects the memory store (optional).
     * Called by the runtime during agent initialization.
     * 
     * <p>If not set, memory operations will throw {@link IllegalStateException}.
     * 
     * @param memoryStore the memory store
     * @since 0.6.0
     */
    public void setMemoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        log.debug("Memory store configured for agent: {}", agentId);
    }

    // =========================================================================
    // LIFECYCLE HOOKS API
    // =========================================================================

    /**
     * Register a hook to be executed when the agent starts.
     * Hooks are executed AFTER onStart() is called.
     *
     * This is useful for external components (like PersistenceManager) to
     * perform actions during agent startup.
     *
     * @param hook the runnable to execute on start
     */
    public void onStartHook(Runnable hook) {
        if (hook != null) {
            startHooks.add(hook);
            log.trace("Registered start hook for agent {}", agentId);
        }
    }

    /**
     * Register a hook to be executed when the agent stops.
     * Hooks are executed BEFORE onStop() is called.
     *
     * This is critical for persistence - it ensures state is saved
     * before the agent shuts down completely.
     *
     * @param hook the runnable to execute on stop
     */
    public void onStopHook(Runnable hook) {
        if (hook != null) {
            stopHooks.add(hook);
            log.trace("Registered stop hook for agent {}", agentId);
        }
    }

    /**
     * Remove a previously registered start hook
     *
     * @param hook the hook to remove
     * @return true if the hook was found and removed
     */
    public boolean removeStartHook(Runnable hook) {
        return startHooks.remove(hook);
    }

    /**
     * Remove a previously registered stop hook
     *
     * @param hook the hook to remove
     * @return true if the hook was found and removed
     */
    public boolean removeStopHook(Runnable hook) {
        return stopHooks.remove(hook);
    }

    /**
     * Clear all registered start hooks
     */
    public void clearStartHooks() {
        startHooks.clear();
    }

    /**
     * Clear all registered stop hooks
     */
    public void clearStopHooks() {
        stopHooks.clear();
    }

    /**
     * Execute all registered start hooks
     */
    private void executeStartHooks() {
        if (startHooks.isEmpty()) {
            return;
        }

        log.debug("Executing {} start hooks for agent {}", startHooks.size(), agentId);

        for (Runnable hook : startHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                log.error("Error executing start hook for agent {}", agentId, e);
                // Continue with other hooks even if one fails
            }
        }
    }

    /**
     * Execute all registered stop hooks
     */
    private void executeStopHooks() {
        if (stopHooks.isEmpty()) {
            return;
        }

        log.debug("Executing {} stop hooks for agent {}", stopHooks.size(), agentId);

        for (Runnable hook : stopHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                log.error("Error executing stop hook for agent {}", agentId, e);
                // Continue with other hooks even if one fails
            }
        }
    }

    /**
     * Initialize services with defaults if not set
     */
    protected void initializeServices() {
        if (messageService == null) {
            messageService = new InMemoryMessageService();
        }
    }
    
    /**
     * Lifecycle hook called when agent starts
     */
    protected void onStart() {
        // Override in subclasses
    }
    
    /**
     * Lifecycle hook called when agent stops
     */
    protected void onStop() {
        // Override in subclasses
    }

    /**
     * Automatically subscribe to direct messages addressed to this agent.
     * Called during agent start.
     */
    private void autoSubscribeDirectMessages() {
        if (messageService == null) {
            log.warn("Agent '{}': Cannot auto-subscribe - no message service", agentId);
            return;
        }

        try {
            // Subscribe to messages with receiverId = this agent's ID
            directMessageSubscriptionId = messageService.subscribeToReceiver(
                    agentId,
                    MessageHandler.sync(this::handleDirectMessage)
            );

            log.debug("Agent '{}' auto-subscribed for direct messages", agentId);

        } catch (Exception e) {
            log.error("Failed to auto-subscribe direct messages for agent '{}': {}",
                    agentId, e.getMessage());
        }
    }

    /**
     * Unsubscribe from direct messages during agent shutdown.
     */
    private void unsubscribeDirectMessages() {
        if (directMessageSubscriptionId != null && messageService != null) {
            try {
                messageService.unsubscribe(directMessageSubscriptionId);
                directMessageSubscriptionId = null;
                log.debug("Agent '{}' unsubscribed from direct messages", agentId);
            } catch (Exception e) {
                log.error("Error unsubscribing direct messages for agent '{}': {}",
                        agentId, e.getMessage());
            }
        }
    }

    /**
     * Handle direct messages received by this agent.
     * Override to customize handling, or use @JenticMessageHandler annotations.
     */
    protected void handleDirectMessage(Message message) {
        log.trace("Agent '{}' received direct message from '{}': {}",
                agentId, message.senderId(), message.content());

        // Delegate to user hook
        onDirectMessage(message);
    }

    /**
     * Called when agent receives a direct message.
     * Override to handle direct messages in a centralized way.
     *
     * Note: This is only called if no @JenticMessageHandler matches.
     *
     * @param message the received message
     */
    protected void onDirectMessage(Message message) {
        // Default: log and ignore
        log.trace("Agent '{}' received unhandled direct message from '{}': {}",
                agentId, message.senderId(), message.content());
    }

    /**
     * Send a direct message to another agent (fire-and-forget).
     *
     * @param receiverAgentId the target agent ID
     * @param content the message content
     * @return CompletableFuture that completes when sent
     */
    protected CompletableFuture<Void> sendTo(String receiverAgentId, Object content) {
        Message message = Message.builder()
                .senderId(agentId)
                .receiverId(receiverAgentId)
                .content(content)
                .build();

        log.trace("Agent '{}' sending to '{}': {}", agentId, receiverAgentId, content);

        return messageService.send(message);
    }

    /**
     * Send a request to another agent and wait for response.
     * Uses the request/response pattern with correlation ID.
     *
     * @param receiverAgentId the target agent ID
     * @param content the request content
     * @return CompletableFuture with the response message
     */
    protected CompletableFuture<Message> requestFrom(String receiverAgentId, Object content) {
        return requestFrom(receiverAgentId, content, 5000);
    }

    /**
     * Send a request with custom timeout.
     *
     * @param receiverAgentId the target agent ID
     * @param content the request content
     * @param timeoutMillis timeout in milliseconds
     * @return CompletableFuture with the response message
     */
    protected CompletableFuture<Message> requestFrom(String receiverAgentId,
                                                     Object content,
                                                     long timeoutMillis) {
        Message request = Message.builder()
                .senderId(agentId)
                .receiverId(receiverAgentId)
                .content(content)
                .build();

        log.debug("Agent '{}' requesting from '{}' (timeout: {}ms)",
                agentId, receiverAgentId, timeoutMillis);

        return messageService.sendAndWait(request, timeoutMillis)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Request from '{}' to '{}' failed: {}",
                                agentId, receiverAgentId, error.getMessage());
                    } else {
                        log.debug("Agent '{}' received response from '{}'",
                                agentId, receiverAgentId);
                    }
                });
    }

    /**
     * Reply to a received message.
     * Automatically sets correlation ID and receiver.
     *
     * @param originalMessage the message to reply to
     * @param content the reply content
     * @return CompletableFuture that completes when reply is sent
     */
    protected CompletableFuture<Void> replyTo(Message originalMessage, Object content) {
        Message reply = originalMessage.reply(content)
                .senderId(agentId)
                .build();

        log.trace("Agent '{}' replying to message {} from '{}'",
                agentId, originalMessage.id(), originalMessage.senderId());

        return messageService.send(reply);
    }

    private void startBehaviors() {
        if (behaviorScheduler != null) {
            behaviors.values().forEach(behavior -> {
                // Reactivate behavior if it was stopped (e.g., after agent restart)
                if (behavior instanceof BaseBehavior baseBehavior) {
                    baseBehavior.activate();
                }
                behaviorScheduler.schedule(behavior);
            });
        }
    }
    
    private void stopBehaviors() {
        behaviors.values().forEach(Behavior::stop);
    }
    
    private void registerWithDirectory() {
        if (agentDirectory != null) {
            var descriptor = AgentDescriptor.builder(agentDescriptor.agentId())
                    .agentName(agentDescriptor.agentName())
                    .agentType(agentDescriptor.agentType())  // From annotation
                    .capabilities(agentDescriptor.capabilities())  // From annotation
                    .metadata(agentDescriptor.metadata())  // From annotation
                    .status(AgentStatus.RUNNING)  // Update current status
                    .registeredAt(agentDescriptor.registeredAt())
                    .build();
                
            agentDirectory.register(descriptor).join();
        }
    }
    
    private void unregisterFromDirectory() {
        if (agentDirectory != null) {
            agentDirectory.unregister(agentId).join();
        }
    }
    
// ========== MEMORY API (since 0.6.0) ==========
    
    /**
     * Stores a short-term memory (volatile, cleared on restart).
     * 
     * <p>Short-term memories are suitable for:
     * <ul>
     *   <li>Temporary state during task execution</li>
     *   <li>Caching of computed values</li>
     *   <li>Session-specific information</li>
     * </ul>
     * 
     * @param key the memory key (will be namespaced automatically)
     * @param content the memory content
     * @return a future that completes when stored
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> rememberShort(String key, String content) {
        return rememberShort(key, content, null);
    }
    
    /**
     * Stores a short-term memory with expiration.
     * 
     * @param key the memory key (will be namespaced automatically)
     * @param content the memory content
     * @param ttl time-to-live (null = never expires)
     * @return a future that completes when stored
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> rememberShort(String key, String content, Duration ttl) {
        ensureMemoryStore();
        
        var entry = MemoryEntry.builder(content)
            .ownerId(agentId)
            .expiresAt(ttl != null ? java.time.Instant.now().plus(ttl) : null)
            .metadata("type", "agent-memory")
            .build();
        
        return memoryStore.store(namespaced(key), entry, MemoryScope.SHORT_TERM)
            .whenComplete((v, ex) -> {
                if (ex == null) {
                    log.trace("Stored short-term memory: key={}, ttl={}", key, ttl);
                } else {
                    log.error("Failed to store short-term memory: key={}", key, ex);
                }
            });
    }
    
    /**
     * Stores a long-term memory (persistent, survives restart).
     * 
     * <p>Long-term memories are suitable for:
     * <ul>
     *   <li>Learned facts and knowledge</li>
     *   <li>User preferences</li>
     *   <li>Historical patterns</li>
     * </ul>
     * 
     * @param key the memory key (will be namespaced automatically)
     * @param content the memory content
     * @return a future that completes when stored
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> rememberLong(String key, String content) {
        ensureMemoryStore();
        
        var entry = MemoryEntry.builder(content)
            .ownerId(agentId)
            .metadata("type", "agent-memory")
            .metadata("stored_at", java.time.Instant.now().toString())
            .build();
        
        return memoryStore.store(namespaced(key), entry, MemoryScope.LONG_TERM)
            .whenComplete((v, ex) -> {
                if (ex == null) {
                    log.trace("Stored long-term memory: key={}", key);
                } else {
                    log.error("Failed to store long-term memory: key={}", key, ex);
                }
            });
    }
    
    /**
     * Stores a long-term memory with metadata.
     * 
     * @param key the memory key
     * @param content the memory content
     * @param metadata additional metadata
     * @return a future that completes when stored
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> rememberLong(String key, String content, 
                                                    Map<String, Object> metadata) {
        ensureMemoryStore();
        
        var builder = MemoryEntry.builder(content)
            .ownerId(agentId)
            .metadata("type", "agent-memory");
        
        metadata.forEach(builder::metadata);
        
        return memoryStore.store(namespaced(key), builder.build(), MemoryScope.LONG_TERM)
            .whenComplete((v, ex) -> {
                if (ex == null) {
                    log.trace("Stored long-term memory with metadata: key={}", key);
                } else {
                    log.error("Failed to store long-term memory: key={}", key, ex);
                }
            });
    }
    
    /**
     * Shares a memory with other agents (for coordination).
     * 
     * <p>Shared memories allow multiple agents to access the same information,
     * useful for orchestrated workflows and multi-agent collaboration.
     * 
     * @param key the shared memory key (not namespaced)
     * @param content the memory content
     * @param agentIds the agent IDs to share with
     * @return a future that completes when stored
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> shareMemory(String key, String content, 
                                                   String... agentIds) {
        ensureMemoryStore();
        
        var entry = MemoryEntry.builder(content)
            .ownerId(agentId)
            .sharedWith(agentIds)
            .metadata("type", "shared-memory")
            .metadata("created_by", agentId)
            .build();
        
        return memoryStore.store("shared:" + key, entry, MemoryScope.SHORT_TERM)
            .whenComplete((v, ex) -> {
                if (ex == null) {
                    log.debug("Shared memory with {} agents: key={}", agentIds.length, key);
                } else {
                    log.error("Failed to share memory: key={}", key, ex);
                }
            });
    }
    
    /**
     * Recalls a memory from the specified scope.
     * 
     * @param key the memory key (will be namespaced automatically)
     * @param scope the memory scope to search in
     * @return a future containing the memory content, or empty if not found
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Optional<String>> recall(String key, MemoryScope scope) {
        ensureMemoryStore();
        
        return memoryStore.retrieve(namespaced(key), scope)
            .thenApply(opt -> opt.map(MemoryEntry::content))
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    log.trace("Recalled memory: key={}, scope={}, found={}", 
                             key, scope, result.isPresent());
                } else {
                    log.error("Failed to recall memory: key={}, scope={}", key, scope, ex);
                }
            });
    }
    
    /**
     * Recalls a shared memory.
     * 
     * @param key the shared memory key (not namespaced)
     * @return a future containing the memory content, or empty if not found
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Optional<String>> recallShared(String key) {
        ensureMemoryStore();
        
        return memoryStore.retrieve("shared:" + key, MemoryScope.SHORT_TERM)
            .thenApply(opt -> opt.filter(e -> e.canAccess(agentId))
                                 .map(MemoryEntry::content))
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    log.trace("Recalled shared memory: key={}, found={}", key, result.isPresent());
                } else {
                    log.error("Failed to recall shared memory: key={}", key, ex);
                }
            });
    }
    
    /**
     * Searches through agent's memories.
     * 
     * @param query the search text
     * @param scope the memory scope to search in
     * @return a future containing matching memory contents
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<List<String>> searchMemory(String query, MemoryScope scope) {
        ensureMemoryStore();
        
        var memoryQuery = MemoryQuery.builder()
            .text(query)
            .scope(scope)
            .ownerId(agentId)
            .limit(20)
            .build();
        
        return memoryStore.search(memoryQuery)
            .thenApply(entries -> entries.stream()
                .map(MemoryEntry::content)
                .toList())
            .whenComplete((results, ex) -> {
                if (ex == null) {
                    log.trace("Searched memory: query={}, scope={}, found={}", 
                             query, scope, results.size());
                } else {
                    log.error("Failed to search memory: query={}, scope={}", query, scope, ex);
                }
            });
    }
    
    /**
     * Searches with custom filters.
     * 
     * @param query the memory query
     * @return a future containing matching entries
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<List<MemoryEntry>> searchMemory(MemoryQuery query) {
        ensureMemoryStore();
        return memoryStore.search(query);
    }
    
    /**
     * Forgets a memory.
     * 
     * @param key the memory key (will be namespaced automatically)
     * @param scope the memory scope
     * @return a future that completes when deleted
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> forget(String key, MemoryScope scope) {
        ensureMemoryStore();
        
        return memoryStore.delete(namespaced(key), scope)
            .whenComplete((v, ex) -> {
                if (ex == null) {
                    log.trace("Forgot memory: key={}, scope={}", key, scope);
                } else {
                    log.error("Failed to forget memory: key={}, scope={}", key, scope, ex);
                }
            });
    }
    
    /**
     * Clears all memories in the specified scope.
     * 
     * <p><b>Warning:</b> This operation cannot be undone.
     * 
     * @param scope the memory scope to clear
     * @return a future that completes when cleared
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    protected CompletableFuture<Void> clearMemory(MemoryScope scope) {
        ensureMemoryStore();
        
        return memoryStore.clear(scope)
            .whenComplete((v, ex) -> {
                if (ex == null) {
                    log.info("Cleared all {} memories", scope);
                } else {
                    log.error("Failed to clear {} memories", scope, ex);
                }
            });
    }
    
    /**
     * Gets memory statistics for this agent.
     * 
     * @return memory usage statistics
     * @throws IllegalStateException if memory store not configured
     * @since 0.6.0
     */
    public MemoryStats getMemoryStats() {
        ensureMemoryStore();
        return memoryStore.getStats();
    }

    // ========== PRIVATE HELPERS ==========
    
    private String namespaced(String key) {
        return memoryNamespace + key;
    }
    
    private void ensureMemoryStore() {
        if (memoryStore == null) {
            throw new IllegalStateException(
                "MemoryStore not configured. Enable memory feature in runtime configuration " +
                "or call setMemoryStore() during initialization."
            );
        }
    }
    
    private AgentDescriptor createDefaultDescriptor() {
        return AgentDescriptor.builder(agentId)
            .agentName(agentName)
            .agentType(getClass().getSimpleName())  // Fallback only
            .status(AgentStatus.STOPPED)
            .build();
    }
}