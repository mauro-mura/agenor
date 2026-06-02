package dev.agenor.core;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for agent behaviors in the Jentic multi-agent framework.
 *
 * <p>Behaviors define <strong>what an agent does over time</strong>. They represent
 * units of autonomous activity that can be executed independently, scheduled
 * periodically, or triggered by events. Behaviors are the primary mechanism
 * for implementing agent logic and decision-making.
 *
 * <p><strong>Core Concepts:</strong>
 * <ul>
 *   <li><strong>Autonomous Execution</strong> - Behaviors run independently without
 *       external control</li>
 *   <li><strong>Lifecycle Management</strong> - Behaviors have start, execute, and
 *       stop phases</li>
 *   <li><strong>Type-Based Scheduling</strong> - Different behavior types enable
 *       various execution patterns</li>
 *   <li><strong>Agent Association</strong> - Each behavior belongs to exactly one
 *       agent</li>
 * </ul>
 *
 * <p><strong>Behavior Types:</strong>
 * Jentic supports multiple behavior types to accommodate different execution
 * patterns:
 *
 * <table border="1">
 *   <caption>Behavior execution strategies and use cases</caption>
 *   <tr>
 *     <th>Type</th>
 *     <th>Execution Pattern</th>
 *     <th>Use Case</th>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#ONE_SHOT ONE_SHOT}</td>
 *     <td>Execute once and stop</td>
 *     <td>Initialization tasks, one-time operations</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#CYCLIC CYCLIC}</td>
 *     <td>Execute repeatedly at fixed intervals</td>
 *     <td>Polling, monitoring, periodic checks</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#EVENT_DRIVEN EVENT_DRIVEN}</td>
 *     <td>Execute when triggered by events/messages</td>
 *     <td>Message handlers, reactive behaviors</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#WAKER WAKER}</td>
 *     <td>Execute at specific times or conditions</td>
 *     <td>Scheduled tasks, time-based triggers</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#CONDITIONAL CONDITIONAL}</td>
 *     <td>Execute only when conditions are met</td>
 *     <td>Guarded operations, state-dependent logic</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#SEQUENTIAL SEQUENTIAL}</td>
 *     <td>Execute child behaviors in sequence</td>
 *     <td>Workflows, multi-step processes</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#PARALLEL PARALLEL}</td>
 *     <td>Execute child behaviors concurrently</td>
 *     <td>Parallel processing, concurrent tasks</td>
 *   </tr>
 *   <tr>
 *     <td>{@link BehaviorType#FSM FSM}</td>
 *     <td>State machine with transitions</td>
 *     <td>Complex state-based logic, protocols</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Lifecycle States:</strong>
 * <ol>
 *   <li><strong>Created</strong> - Behavior instantiated, not yet active</li>
 *   <li><strong>Active</strong> - Behavior is running and can execute</li>
 *   <li><strong>Stopped</strong> - Behavior has been terminated</li>
 * </ol>
 *
 * <p><strong>Execution Model:</strong>
 * Behaviors execute asynchronously via {@link #execute()}, which returns a
 * {@code CompletableFuture}. This enables:
 * <ul>
 *   <li>Non-blocking behavior execution</li>
 *   <li>Parallel execution of multiple behaviors</li>
 *   <li>Composition and chaining of behaviors</li>
 *   <li>Error handling and recovery</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong>
 * Implementations must be thread-safe as behaviors may be:
 * <ul>
 *   <li>Executed concurrently by the scheduler</li>
 *   <li>Stopped while executing</li>
 *   <li>Accessed from multiple agent threads</li>
 * </ul>
 *
 * <p><strong>Example - Simple Cyclic Behavior:</strong>
 * <pre>{@code
 * // Create a behavior that monitors system health every 30 seconds
 * Behavior healthCheck = new BaseBehavior(BehaviorType.CYCLIC, Duration.ofSeconds(30)) {
 *
 *     @Override
 *     public CompletableFuture<Void> execute() {
 *         return CompletableFuture.runAsync(() -> {
 *             if (!isActive()) return;
 *
 *             SystemHealth health = checkSystemHealth();
 *             if (health.isCritical()) {
 *                 Message alert = Message.builder()
 *                         .topic("alerts.health")
 *                         .content(health)
 *                         .build();
 *                 agent.getMessageDispatcher().publish("alerts.health", alert);
 *             }
 *
 *             log.info("Health check: {}", health);
 *         });
 *     }
 * };
 *
 * // Add to agent
 * agent.addBehavior(healthCheck);
 * }</pre>
 *
 * <p><strong>Example - Event-Driven Behavior:</strong>
 * <pre>{@code
 * // Create a behavior that processes incoming orders
 * Behavior orderProcessor = new BaseBehavior(BehaviorType.EVENT_DRIVEN) {
 *
 *     @Override
 *     public CompletableFuture<Void> execute() {
 *         // Event-driven behaviors typically don't implement execute()
 *         // They respond to messages via the agent's message handlers
 *         return CompletableFuture.completedFuture(null);
 *     }
 * };
 *
 * // Subscribe to order messages
 * agent.getMessageDispatcher().subscribeTopic("orders.new", message -> {
 *     if (orderProcessor.isActive()) {
 *         Order order = message.getContent(Order.class);
 *         processOrder(order);
 *     }
 * });
 * }</pre>
 *
 * <p><strong>Example - Composite Behavior:</strong>
 * <pre>{@code
 * // Create a sequential workflow — addBehavior() is sufficient to start it
 * SequentialBehavior orderWorkflow = new SequentialBehavior("order-workflow");
 * orderWorkflow.addChildBehavior(validateOrder);
 * orderWorkflow.addChildBehavior(checkInventory);
 * orderWorkflow.addChildBehavior(processPayment);
 * orderWorkflow.addChildBehavior(shipOrder);
 *
 * agent.addBehavior(orderWorkflow); // registers and triggers automatically
 * }</pre>
 *
 * <p><strong>Implementation Guidelines:</strong>
 * <ul>
 *   <li>Keep {@link #execute()} implementations short and focused</li>
 *   <li>Check {@link #isActive()} before performing work</li>
 *   <li>Handle exceptions gracefully within behaviors</li>
 *   <li>Use {@link CompletableFuture} for long-running operations</li>
 *   <li>Clean up resources in response to {@link #stop()}</li>
 *   <li>Use atomic operations for state management</li>
 * </ul>
 *
 * <p><strong>Performance Considerations:</strong>
 * <ul>
 *   <li><strong>Cyclic behaviors</strong> - Keep interval reasonable to avoid
 *       overwhelming the scheduler</li>
 *   <li><strong>Blocking operations</strong> - Use async APIs or separate thread
 *       pools for I/O</li>
 *   <li><strong>Memory</strong> - Be mindful of object creation in frequently
 *       executed behaviors</li>
 *   <li><strong>Error handling</strong> - Uncaught exceptions can stop behavior
 *       execution</li>
 * </ul>
 *
 * @since 0.1.0
 * @see Agent#addBehavior(Behavior)
 * @see BehaviorScheduler
 * @see BehaviorType
 */
public interface Behavior {

    /**
     * Returns the unique identifier for this behavior.
     *
     * <p>The behavior ID must be unique within the owning agent's scope. It is
     * used for behavior management operations such as removal and cancellation.
     *
     * <p><strong>Uniqueness:</strong> Two behaviors in the same agent cannot
     * have the same ID. Attempting to add a behavior with a duplicate ID may
     * either replace the existing behavior or fail, depending on implementation.
     *
     * <p><strong>Naming Convention:</strong> Use descriptive IDs that indicate
     * the behavior's purpose:
     * <ul>
     *   <li>Good: "health-monitor", "order-processor", "data-collector"</li>
     *   <li>Avoid: "behavior1", "temp", "test"</li>
     * </ul>
     *
     * @return the behavior's unique identifier, never null or empty
     * @see Agent#removeBehavior(String)
     */
    String getBehaviorId();

    /**
     * Returns the agent that owns this behavior.
     *
     * <p>Every behavior belongs to exactly one agent. This relationship is
     * established when the behavior is added to the agent via
     * {@link Agent#addBehavior(Behavior)} and provides the behavior with:
     * <ul>
     *   <li>Access to the agent's {@link dev.agenor.core.messaging.MessageDispatcher}</li>
     *   <li>Agent identity for logging and monitoring</li>
     *   <li>Context for behavior execution</li>
     * </ul>
     *
     * <p><strong>Lifecycle:</strong> The agent reference is typically set when
     * the behavior is added to the agent and remains constant for the behavior's
     * lifetime.
     *
     * <p><strong>Null Safety:</strong> May return {@code null} if the behavior
     * has not yet been added to an agent. Implementations should handle this
     * case gracefully.
     *
     * @return the owning agent, or null if not yet associated with an agent
     * @see Agent#addBehavior(Behavior)
     */
    Agent getAgent();

    /**
     * Executes this behavior once, asynchronously.
     *
     * <p>This method performs a single execution cycle of the behavior's logic.
     * The actual execution pattern (one-shot, cyclic, etc.) is determined by
     * the {@link BehaviorType} and managed by the {@link BehaviorScheduler}.
     *
     * <p><strong>Asynchronous Execution:</strong>
     * This method is non-blocking and returns immediately with a
     * {@code CompletableFuture} that completes when the behavior's work is done.
     * This enables:
     * <ul>
     *   <li>Parallel execution of multiple behaviors</li>
     *   <li>Non-blocking agent operations</li>
     *   <li>Compositional behavior chains</li>
     *   <li>Centralized error handling</li>
     * </ul>
     *
     * <p><strong>Active State Check:</strong>
     * Implementations should check {@link #isActive()} before performing work
     * and return early if the behavior has been stopped:
     * <pre>{@code
     * public CompletableFuture<Void> execute() {
     *     if (!isActive()) {
     *         return CompletableFuture.completedFuture(null);
     *     }
     *     // Perform behavior logic...
     * }
     * }</pre>
     *
     * <p><strong>Error Handling:</strong>
     * Exceptions thrown during execution should be handled within the behavior
     * or propagated via the returned future. Uncaught exceptions may:
     * <ul>
     *   <li>Stop cyclic behavior execution</li>
     *   <li>Mark the behavior as failed</li>
     *   <li>Trigger agent error handlers</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method may be called concurrently from different threads, especially
     * for event-driven behaviors. Implementations must be thread-safe.
     *
     * <p><strong>Performance:</strong>
     * Keep execution time reasonable, especially for cyclic behaviors. Long-running
     * operations should be delegated to separate threads or use async APIs.
     *
     * <p>Example:
     * <pre>{@code
     * @Override
     * public CompletableFuture<Void> execute() {
     *     return CompletableFuture.supplyAsync(() -> {
     *         if (!isActive()) return null;
     *
     *         try {
     *             // Perform behavior work
     *             Data data = collectData();
     *             processData(data);
     *
     *             // Send results
     *             Message result = Message.builder()
     *                     .topic("data.processed")
     *                     .content(data)
     *                     .build();
     *             agent.getMessageDispatcher().publish("data.processed", result);
     *
     *             return null;
     *
     *         } catch (Exception e) {
     *             log.error("Behavior execution failed", e);
     *             throw new CompletionException(e);
     *         }
     *     });
     * }
     * }</pre>
     *
     * @return a {@code CompletableFuture} that completes when execution is finished,
     *         or completes exceptionally if execution fails
     * @see #isActive()
     * @see #stop()
     */
    CompletableFuture<Void> execute();

    /**
     * Checks whether this behavior should continue running.
     *
     * <p>A behavior is considered active if it has not been explicitly stopped
     * and is eligible for execution. The scheduler uses this flag to determine
     * whether to continue scheduling the behavior.
     *
     * <p><strong>Active State Semantics:</strong>
     * <ul>
     *   <li>{@code true} - Behavior can execute and should be scheduled</li>
     *   <li>{@code false} - Behavior should not execute and may be unscheduled</li>
     * </ul>
     *
     * <p><strong>Transition to Inactive:</strong>
     * A behavior becomes inactive when:
     * <ul>
     *   <li>{@link #stop()} is called explicitly</li>
     *   <li>One-shot behaviors complete their execution</li>
     *   <li>The owning agent is stopped</li>
     *   <li>Internal logic determines the behavior should end</li>
     * </ul>
     *
     * <p><strong>Usage in Execute:</strong>
     * Always check this flag at the beginning of {@link #execute()}:
     * <pre>{@code
     * public CompletableFuture<Void> execute() {
     *     if (!isActive()) {
     *         return CompletableFuture.completedFuture(null);
     *     }
     *     // Behavior logic...
     * }
     * }</pre>
     *
     * <p><strong>For Long-Running Operations:</strong>
     * Check the active flag periodically during execution to enable responsive
     * cancellation:
     * <pre>{@code
     * while (isActive() && hasMoreWork()) {
     *     processNextItem();
     * }
     * }</pre>
     *
     * <p><strong>Thread Safety:</strong>
     * This method must be thread-safe as it may be called concurrently with
     * {@link #stop()} from different threads.
     *
     * @return {@code true} if the behavior is active and should continue executing,
     *         {@code false} if the behavior has been stopped
     * @see #stop()
     * @see #execute()
     */
    boolean isActive();

    /**
     * Stops this behavior, preventing further execution.
     *
     * <p>This method transitions the behavior to an inactive state. After calling
     * {@code stop()}, {@link #isActive()} will return {@code false} and the
     * behavior will no longer be executed by the scheduler.
     *
     * <p><strong>Stopping Semantics:</strong>
     * <ul>
     *   <li><strong>Immediate Effect</strong> - The behavior is marked inactive
     *       immediately</li>
     *   <li><strong>Graceful</strong> - Current execution completes naturally</li>
     *   <li><strong>Idempotent</strong> - Multiple calls to stop() are safe</li>
     *   <li><strong>Permanent</strong> - Stopped behaviors cannot be restarted
     *       (create a new instance instead)</li>
     * </ul>
     *
     * <p><strong>Scheduler Integration:</strong>
     * When a behavior is stopped:
     * <ol>
     *   <li>The active flag is set to {@code false}</li>
     *   <li>The scheduler is notified to cancel future executions</li>
     *   <li>Current execution (if any) continues to completion</li>
     *   <li>No new executions are scheduled</li>
     * </ol>
     *
     * <p><strong>Resource Cleanup:</strong>
     * Implementations should use this method to clean up resources:
     * <pre>{@code
     * @Override
     * public void stop() {
     *     super.stop();  // Mark inactive
     *
     *     // Clean up resources
     *     if (connection != null) {
     *         connection.close();
     *     }
     *
     *     if (executor != null) {
     *         executor.shutdown();
     *     }
     * }
     * }</pre>
     *
     * <p><strong>Composite Behaviors:</strong>
     * For composite behaviors (SEQUENTIAL, PARALLEL, FSM), stopping the parent
     * should propagate to all child behaviors:
     * <pre>{@code
     * @Override
     * public void stop() {
     *     super.stop();
     *     childBehaviors.forEach(Behavior::stop);
     * }
     * }</pre>
     *
     * <p><strong>Thread Safety:</strong>
     * This method must be thread-safe and may be called:
     * <ul>
     *   <li>While the behavior is executing</li>
     *   <li>From a different thread than execution</li>
     *   <li>Multiple times concurrently</li>
     * </ul>
     *
     * <p><strong>Agent Lifecycle:</strong>
     * When an agent stops, it calls {@code stop()} on all its behaviors. There
     * is no need to explicitly stop behaviors during agent shutdown.
     *
     * @see #isActive()
     * @see Agent#stop()
     * @see Agent#removeBehavior(String)
     */
    void stop();

    /**
     * Returns the type of this behavior.
     *
     * <p>The behavior type determines how the {@link BehaviorScheduler} manages
     * its execution. Different types have different scheduling strategies and
     * lifecycle patterns.
     *
     * <p><strong>Type Impact on Scheduling:</strong>
     * <table border="1">
     *   <caption>Behavior scheduling characteristics by type</caption>
     *   <tr>
     *     <th>Type</th>
     *     <th>Scheduling</th>
     *     <th>Interval Required</th>
     *   </tr>
     *   <tr>
     *     <td>ONE_SHOT</td>
     *     <td>Executed once immediately</td>
     *     <td>No</td>
     *   </tr>
     *   <tr>
     *     <td>CYCLIC</td>
     *     <td>Repeated at fixed intervals</td>
     *     <td>Yes - via {@link #getInterval()}</td>
     *   </tr>
     *   <tr>
     *     <td>EVENT_DRIVEN</td>
     *     <td>Not scheduled, responds to events</td>
     *     <td>No</td>
     *   </tr>
     *   <tr>
     *     <td>WAKER</td>
     *     <td>Custom wake-up logic</td>
     *     <td>No</td>
     *   </tr>
     * </table>
     *
     * <p><strong>Type Immutability:</strong>
     * The behavior type is typically set at construction time and should not
     * change during the behavior's lifetime. This allows the scheduler to make
     * optimization decisions based on the type.
     *
     * <p><strong>Custom Types:</strong>
     * Use {@link BehaviorType#CUSTOM} for behaviors that don't fit standard
     * patterns and implement custom scheduling logic.
     *
     * @return the behavior type, never null
     * @see BehaviorType
     * @see BehaviorScheduler#schedule(Behavior)
     * @see #getInterval()
     */
    BehaviorType getType();

    /**
     * Returns the execution interval for cyclic behaviors.
     *
     * <p>The interval specifies how much time should elapse between consecutive
     * executions of a {@link BehaviorType#CYCLIC CYCLIC} behavior. This value
     * is used by the scheduler to determine the fixed rate or fixed delay for
     * repeated execution.
     *
     * <p><strong>Applicability:</strong>
     * This method is primarily relevant for:
     * <ul>
     *   <li>{@link BehaviorType#CYCLIC CYCLIC} - Required, must not be null</li>
     *   <li>Other types - Typically returns null (interval not applicable)</li>
     * </ul>
     *
     * <p><strong>Scheduling Semantics:</strong>
     * The interval represents the time between the <em>start</em> of consecutive
     * executions (fixed rate), not between the end of one and start of the next:
     * <pre>
     * Time: 0s        10s       20s       30s
     *       |---------|---------|---------|
     *       Execute   Execute   Execute   Execute
     *
     * Interval = 10 seconds (fixed rate)
     * </pre>
     *
     * <p><strong>Choosing Intervals:</strong>
     * <ul>
     *   <li><strong>Short intervals</strong> (&lt; 1s) - Polling, real-time monitoring.
     *       Use cautiously to avoid scheduler overhead.</li>
     *   <li><strong>Medium intervals</strong> (1s-1m) - Regular checks, health monitoring,
     *       periodic data collection.</li>
     *   <li><strong>Long intervals</strong> (&gt; 1m) - Cleanup tasks, batch processing,
     *       low-priority maintenance.</li>
     * </ul>
     *
     * <p><strong>Performance Considerations:</strong>
     * <ul>
     *   <li>Ensure execution time is less than the interval to prevent overlap</li>
     *   <li>Very short intervals can saturate the scheduler thread pool</li>
     *   <li>Consider using event-driven behaviors for immediate responses</li>
     * </ul>
     *
     * <p>Example:
     * <pre>{@code
     * // Health check every 30 seconds
     * Behavior healthCheck = new BaseBehavior(
     *     BehaviorType.CYCLIC,
     *     Duration.ofSeconds(30)
     * ) {
     *     @Override
     *     public CompletableFuture<Void> execute() {
     *         return CompletableFuture.runAsync(() -> {
     *             checkSystemHealth();
     *         });
     *     }
     * };
     *
     * // Data collection every 5 minutes
     * Behavior dataCollector = new BaseBehavior(
     *     BehaviorType.CYCLIC,
     *     Duration.ofMinutes(5)
     * ) {
     *     @Override
     *     public CompletableFuture<Void> execute() {
     *         return CompletableFuture.runAsync(() -> {
     *             collectAndStoreData();
     *         });
     *     }
     * };
     * }</pre>
     *
     * <p><strong>Validation:</strong>
     * Schedulers should validate that:
     * <ul>
     *   <li>CYCLIC behaviors have a non-null interval</li>
     *   <li>Interval is positive and reasonable (e.g., &gt; 1ms)</li>
     * </ul>
     *
     * @return the execution interval for cyclic behaviors, or null for non-cyclic
     *         behaviors or when interval is not applicable
     * @see BehaviorType#CYCLIC
     * @see BehaviorScheduler#schedule(Behavior)
     */
    Duration getInterval();
}
