package dev.agenor.core;

import dev.agenor.core.messaging.MessageDispatcher;

import java.util.concurrent.CompletableFuture;

// ============================================================================
// 1. Agent Interface
// ============================================================================

/**
 * Core agent interface defining the lifecycle and basic operations of an agent
 * in the Jentic multi-agent framework.
 *
 * <p>All agents in Jentic must implement this interface, which provides the
 * fundamental contract for agent behavior, lifecycle management, and
 * inter-agent communication. An agent is an autonomous entity that can:
 * <ul>
 *   <li>Execute behaviors independently</li>
 *   <li>Communicate with other agents via messages</li>
 *   <li>Be started and stopped asynchronously</li>
 *   <li>Manage its own lifecycle state</li>
 * </ul>
 *
 * <p><strong>Lifecycle:</strong> Agents follow a simple lifecycle model:
 * <ol>
 *   <li><strong>Created</strong> - Agent is instantiated but not running</li>
 *   <li><strong>Starting</strong> - {@link #start()} is called, behaviors are being initialized</li>
 *   <li><strong>Running</strong> - Agent is active, behaviors are executing</li>
 *   <li><strong>Stopping</strong> - {@link #stop()} is called, behaviors are being shut down</li>
 *   <li><strong>Stopped</strong> - Agent is inactive, can be restarted</li>
 * </ol>
 *
 * <p><strong>Behaviors:</strong> Agents execute behaviors, which are units of
 * autonomous activity. Behaviors can be:
 * <ul>
 *   <li>One-shot (execute once)</li>
 *   <li>Cyclic (execute repeatedly)</li>
 *   <li>Triggered by messages or events</li>
 * </ul>
 *
 * <p><strong>Communication:</strong> Agents communicate through a
 * {@link dev.agenor.core.messaging.MessageDispatcher}, which provides publish-subscribe
 * messaging with topic-based routing and point-to-point communication.
 *
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe.
 * Multiple behaviors may execute concurrently, and lifecycle methods may be
 * called from different threads.
 *
 * <p><strong>Error States:</strong> When an error occurs during lifecycle
 *  operations, the agent transitions to {@link AgentStatus#ERROR} state.
 *  The running flag is set to false, but the agent remains in the ERROR state
 *  rather than transitioning to STOPPED.
 *
 * <p><strong>Example Usage:</strong>
 * <pre>{@code
 * // Creating a simple agent
 * Agent agent = new MyCustomAgent("agent-1", "My Agent");
 *
 * // Adding behaviors
 * agent.addBehavior(new CyclicBehavior("monitor", this::monitorSystem));
 * agent.addBehavior(new MessageBehavior("handler", this::handleMessage));
 *
 * // Starting the agent asynchronously
 * agent.start().thenRun(() -> {
 *     System.out.println("Agent started: " + agent.getAgentName());
 * });
 *
 * // Later, stopping the agent
 * agent.stop().thenRun(() -> {
 *     System.out.println("Agent stopped");
 * });
 * }</pre>
 *
 * <p><strong>Implementation Notes:</strong>
 * <ul>
 *   <li>Implementations should provide common base functionality</li>
 *   <li>Use {@code @Agent} annotation for automatic discovery</li>
 *   <li>Ensure proper cleanup in {@link #stop()} to release resources</li>
 *   <li>Handle failures gracefully during startup and shutdown</li>
 * </ul>
 *
 * @since 0.1.0
 * @see Behavior
 * @see dev.agenor.core.messaging.MessageDispatcher
 * @see AgentDescriptor
 * @see AgentStatus
 */
public interface Agent {

    /**
     * Returns the unique identifier for this agent.
     *
     * <p>The agent ID must be unique within the runtime environment and is used
     * for agent discovery, message routing, and state management. Once assigned,
     * the ID should never change during the agent's lifetime.
     *
     * <p>Recommended format: lowercase alphanumeric with hyphens (e.g., "order-processor-1").
     *
     * @return the agent's unique identifier, never null or empty
     */
    String getAgentId();

    /**
     * Returns the human-readable name for this agent.
     *
     * <p>The agent name is primarily for display and logging purposes. Unlike
     * the agent ID, the name doesn't need to be unique and can be changed
     * during the agent's lifetime (though this is discouraged).
     *
     * <p>Names should be descriptive and help identify the agent's purpose
     * (e.g., "Customer Support Agent", "Inventory Monitor").
     *
     * @return the agent's display name, never null or empty
     */
    String getAgentName();

    /**
     * Starts the agent and all its registered behaviors asynchronously.
     *
     * <p>This method initiates the agent's lifecycle, performing the following:
     * <ol>
     *   <li>Transitions the agent to the STARTING state</li>
     *   <li>Initializes all registered behaviors</li>
     *   <li>Starts behavior execution (cyclic, scheduled, etc.)</li>
     *   <li>Registers the agent with the directory (if enabled)</li>
     *   <li>Transitions to RUNNING state when complete</li>
     * </ol>
     *
     * <p><strong>Idempotency:</strong> Calling {@code start()} on an already
     * running agent should be a no-op and return immediately.
     *
     * <p><strong>Concurrency:</strong> This method is asynchronous and non-blocking.
     * Use the returned {@code CompletableFuture} to wait for startup completion
     * or chain subsequent operations.
     *
     * <p><strong>Error Handling:</strong> If startup fails, the returned future
     * completes exceptionally. The agent transitions to the ERROR state and set
     * s running to false.
     *
     * <p>Example:
     * <pre>{@code
     * agent.start()
     *     .thenRun(() -> log.info("Agent started successfully"))
     *     .exceptionally(ex -> {
     *         log.error("Failed to start agent", ex);
     *         return null;
     *     });
     * }</pre>
     *
     * @return a {@code CompletableFuture} that completes when the agent is fully
     *         started, or completes exceptionally if startup fails
     * @see #stop()
     * @see #isRunning()
     */
    CompletableFuture<Void> start();

    /**
     * Stops the agent and all its running behaviors gracefully.
     *
     * <p>This method initiates graceful shutdown, performing the following:
     * <ol>
     *   <li>Transitions the agent to the STOPPING state</li>
     *   <li>Stops all running behaviors (allowing current executions to complete)</li>
     *   <li>Unregisters the agent from the directory (if registered)</li>
     *   <li>Releases allocated resources (threads, connections, etc.)</li>
     *   <li>Transitions to STOPPED state when complete</li>
     * </ol>
     *
     * <p><strong>Graceful Shutdown:</strong> The agent attempts to stop cleanly,
     * allowing behaviors to complete their current execution cycle. A timeout
     * may be enforced to prevent indefinite waiting.
     *
     * <p><strong>Idempotency:</strong> Calling {@code stop()} on an already
     * stopped agent should be a no-op and return immediately.
     *
     * <p><strong>Concurrency:</strong> This method is asynchronous and non-blocking.
     * Multiple concurrent calls to {@code stop()} should be safe and result in
     * a single shutdown sequence.
     *
     * <p><strong>Resource Cleanup:</strong> Implementations must ensure proper
     * cleanup of:
     * <ul>
     *   <li>Thread pools and executors</li>
     *   <li>Open connections and file handles</li>
     *   <li>Scheduled tasks</li>
     *   <li>Message subscriptions</li>
     * </ul>
     *
     * <p>Example:
     * <pre>{@code
     * agent.stop()
     *     .thenRun(() -> log.info("Agent stopped gracefully"))
     *     .exceptionally(ex -> {
     *         log.warn("Error during shutdown (resources may leak)", ex);
     *         return null;
     *     });
     * }</pre>
     *
     * @return a {@code CompletableFuture} that completes when the agent is fully
     *         stopped, or completes exceptionally if shutdown encounters errors
     * @see #start()
     * @see #isRunning()
     */
    CompletableFuture<Void> stop();

    /**
     * Checks whether the agent is currently running.
     *
     * <p>An agent is considered running if it has completed startup and is
     * actively executing behaviors. This method returns:
     * <ul>
     *   <li>{@code true} - Agent is in RUNNING state</li>
     *   <li>{@code false} - Agent is in CREATED, STARTING, STOPPING, or STOPPED state</li>
     * </ul>
     *
     * <p><strong>State Transitions:</strong> During startup and shutdown, this
     * method may temporarily return values that don't reflect the final state:
     * <ul>
     *   <li>During {@link #start()}: returns {@code false} until fully started</li>
     *   <li>During {@link #stop()}: returns {@code true} until fully stopped</li>
     * </ul>
     *
     * <p>For more detailed state information, use the agent's status in its
     * {@link AgentDescriptor}.
     *
     * @return {@code true} if the agent is currently running and processing
     *         behaviors, {@code false} otherwise
     * @see #start()
     * @see #stop()
     */
    boolean isRunning();

    /**
     * Adds a behavior to this agent's behavior set.
     *
     * <p>Behaviors represent units of autonomous activity. When a behavior is
     * added to a running agent, it should start executing immediately according
     * to its type (one-shot, cyclic, triggered, etc.).
     *
     * <p><strong>Dynamic Addition:</strong> Behaviors can be added at any time:
     * <ul>
     *   <li>Before {@link #start()} - Behavior will start when agent starts</li>
     *   <li>While running - Behavior starts immediately</li>
     *   <li>After {@link #stop()} - Behavior is added but won't execute until restart</li>
     * </ul>
     *
     * <p><strong>Behavior Identity:</strong> Each behavior must have a unique ID
     * within the agent's scope. Adding a behavior with a duplicate ID may either
     * replace the existing behavior or throw an exception, depending on the
     * implementation.
     *
     * <p><strong>Thread Safety:</strong> This method must be thread-safe and can
     * be called concurrently with other lifecycle operations.
     *
     * <p>Example:
     * <pre>{@code
     * // Add a cyclic behavior that runs every 5 seconds
     * agent.addBehavior(new CyclicBehavior("monitor",
     *     () -> checkSystemHealth(),
     *     Duration.ofSeconds(5)
     * ));
     *
     * // Add a message-triggered behavior
     * agent.addBehavior(new MessageBehavior("order-handler",
     *     message -> processOrder(message),
     *     TopicFilter.matching("orders.*")
     * ));
     * }</pre>
     *
     * @param behavior the behavior to add, must not be null
     * @throws NullPointerException if behavior is null
     * @throws IllegalArgumentException if a behavior with the same ID already exists
     *         (implementation-dependent)
     * @see #removeBehavior(String)
     * @see Behavior
     */
    void addBehavior(Behavior behavior);

    /**
     * Removes a behavior from this agent by its ID.
     *
     * <p>If the agent is running, the behavior will be stopped gracefully:
     * <ul>
     *   <li>For one-shot behaviors: current execution completes</li>
     *   <li>For cyclic behaviors: current cycle completes, no new cycles start</li>
     *   <li>For triggered behaviors: message subscriptions are cancelled</li>
     * </ul>
     *
     * <p><strong>Not Found:</strong> If no behavior exists with the given ID,
     * this method is a no-op.
     *
     * <p><strong>Thread Safety:</strong> This method must be thread-safe and can
     * be called concurrently with behavior execution and other lifecycle operations.
     *
     * <p>Example:
     * <pre>{@code
     * // Remove a behavior dynamically
     * agent.removeBehavior("monitor");
     * }</pre>
     *
     * @param behaviorId the unique identifier of the behavior to remove, must not be null
     * @throws NullPointerException if behaviorId is null
     * @see #addBehavior(Behavior)
     * @see Behavior#getBehaviorId()
     */
    void removeBehavior(String behaviorId);

    /**
     * Returns the message dispatcher for this agent.
     *
     * <p>The {@link MessageDispatcher} is the communication backbone that enables
     * this agent to:
     * <ul>
     *   <li>Publish messages to named topics (one-to-many)</li>
     *   <li>Subscribe to topics and receive messages</li>
     *   <li>Send messages directly to another agent by ID (one-to-one)</li>
     *   <li>Subscribe to direct messages addressed to this agent</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong> The returned {@code MessageDispatcher}
     * must be thread-safe, as it may be accessed from multiple behaviors
     * concurrently.
     *
     * <p>Example:
     * <pre>{@code
     * // Publish to a topic
     * agent.getMessageDispatcher().publish("orders.new", message);
     *
     * // Subscribe to a topic
     * agent.getMessageDispatcher().subscribeTopic("orders.new", msg -> handleOrder(msg));
     *
     * // Send directly to another agent
     * agent.getMessageDispatcher().sendTo("inventory-agent", message);
     * }</pre>
     *
     * @return the message dispatcher for this agent, never null
     * @since 0.20.0
     * @see MessageDispatcher
     * @see Message
     */
    MessageDispatcher getMessageDispatcher();

}
