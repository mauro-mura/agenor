package dev.agenor.core;

import java.util.concurrent.CompletableFuture;

/**
 * Scheduler interface for managing behavior execution in the Jentic framework.
 *
 * <p>The {@code BehaviorScheduler} is responsible for orchestrating the
 * execution of agent behaviors according to their types and schedules. It
 * acts as the execution engine that brings behaviors to life by:
 * <ul>
 *   <li>Scheduling behaviors based on their {@link BehaviorType}</li>
 *   <li>Managing execution timing (intervals, delays, schedules)</li>
 *   <li>Handling concurrent behavior execution</li>
 *   <li>Providing lifecycle control (start, stop, cancel)</li>
 * </ul>
 *
 * <p><strong>Core Responsibilities:</strong>
 * <table border="1">
 *   <caption>Scheduler core responsibilities</caption>
 *   <tr>
 *     <th>Responsibility</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>Scheduling</td>
 *     <td>Determine when behaviors should execute based on their type</td>
 *   </tr>
 *   <tr>
 *     <td>Execution</td>
 *     <td>Invoke behavior {@link Behavior#execute()} at appropriate times</td>
 *   </tr>
 *   <tr>
 *     <td>Lifecycle</td>
 *     <td>Start, stop, and cancel behaviors</td>
 *   </tr>
 *   <tr>
 *     <td>Concurrency</td>
 *     <td>Manage thread pools and parallel execution</td>
 *   </tr>
 *   <tr>
 *     <td>Error Handling</td>
 *     <td>Handle exceptions in behavior execution</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Scheduling Strategies by Type:</strong>
 * Different behavior types require different scheduling approaches:
 *
 * <ul>
 *   <li><strong>ONE_SHOT</strong>: Execute once immediately
 *       <pre>{@code
 * scheduler.schedule(oneShot);  // Executes now, then completes
 *       }</pre>
 *   </li>
 *
 *   <li><strong>CYCLIC</strong>: Execute repeatedly at fixed intervals
 *       <pre>{@code
 * Behavior cyclic = new CyclicBehavior("monitor", Duration.ofSeconds(30));
 * scheduler.schedule(cyclic);  // Executes every 30 seconds
 *       }</pre>
 *   </li>
 *
 *   <li><strong>EVENT_DRIVEN</strong>: No scheduling, triggered by events
 *       <pre>{@code
 * scheduler.schedule(eventDriven);  // Registered but not scheduled
 *       }</pre>
 *   </li>
 *
 *   <li><strong>WAKER</strong>: Custom scheduling based on conditions
 *       <pre>{@code
 * scheduler.schedule(waker);  // Checks wake conditions periodically
 *       }</pre>
 *   </li>
 * </ul>
 *
 * <p><strong>Implementation Strategies:</strong>
 * Different use cases may require different scheduler implementations:
 *
 * <table border="1">
 *   <caption>Scheduler implementation strategies</caption>
 *   <tr>
 *     <th>Strategy</th>
 *     <th>Characteristics</th>
 *     <th>Use Case</th>
 *   </tr>
 *   <tr>
 *     <td>Thread Pool</td>
 *     <td>Fixed pool of threads, standard Java scheduling</td>
 *     <td>General purpose, moderate load</td>
 *   </tr>
 *   <tr>
 *     <td>Virtual Threads</td>
 *     <td>Java 21+ virtual threads, lightweight concurrency</td>
 *     <td>High concurrency, many behaviors</td>
 *   </tr>
 *   <tr>
 *     <td>Event Loop</td>
 *     <td>Single-threaded, non-blocking</td>
 *     <td>Low latency, predictable timing</td>
 *   </tr>
 *   <tr>
 *     <td>Distributed</td>
 *     <td>Cluster-aware scheduling</td>
 *     <td>Multi-node deployments</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Concurrency and Thread Safety:</strong>
 * The scheduler must handle multiple concurrent concerns:
 * <ul>
 *   <li>Multiple behaviors executing simultaneously</li>
 *   <li>Behaviors being added/removed while scheduler is running</li>
 *   <li>Concurrent start/stop operations</li>
 *   <li>Thread pool management and resource limits</li>
 * </ul>
 *
 * <p><strong>Error Handling:</strong>
 * The scheduler should be resilient to behavior failures:
 * <pre>{@code
 * // Cyclic behaviors should continue despite exceptions
 * try {
 *     behavior.execute().join();
 * } catch (Exception e) {
 *     log.error("Behavior execution failed: {}", behavior.getBehaviorId(), e);
 *     // Continue scheduling for cyclic behaviors
 * }
 * }</pre>
 *
 * <p><strong>Lifecycle Management:</strong>
 * The scheduler has its own lifecycle:
 * <ol>
 *   <li><strong>Created</strong> - Scheduler instantiated but not active</li>
 *   <li><strong>Starting</strong> - {@link #start()} called, initializing resources</li>
 *   <li><strong>Running</strong> - Actively scheduling and executing behaviors</li>
 *   <li><strong>Stopping</strong> - {@link #stop()} called, canceling behaviors</li>
 *   <li><strong>Stopped</strong> - All behaviors canceled, resources released</li>
 * </ol>
 *
 * <p><strong>Example Usage:</strong>
 * <pre>{@code
 * // Create and start scheduler
 * BehaviorScheduler scheduler = new SimpleBehaviorScheduler(4); // 4 threads
 * scheduler.start().join();
 *
 * // Schedule different behavior types
 * scheduler.schedule(oneShotBehavior);
 * scheduler.schedule(cyclicBehavior);
 * scheduler.schedule(eventDrivenBehavior);
 *
 * // Later, cancel a specific behavior
 * scheduler.cancel("cyclic-monitor");
 *
 * // Shutdown gracefully
 * scheduler.stop().join();
 * }</pre>
 *
 * <p><strong>Integration with Agents:</strong>
 * Agents typically have an associated scheduler that manages all their behaviors:
 * <pre>{@code
 * public class MyAgent implements Agent {
 *     private final BehaviorScheduler scheduler;
 *
 *     @Override
 *     public void addBehavior(Behavior behavior) {
 *         behaviors.put(behavior.getBehaviorId(), behavior);
 *         if (isRunning()) {
 *             scheduler.schedule(behavior);  // Schedule immediately if running
 *         }
 *     }
 *
 *     @Override
 *     public CompletableFuture<Void> stop() {
 *         return scheduler.stop()  // Stop all behaviors
 *             .thenRun(() -> {
 *                 // Additional cleanup
 *             });
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Performance Considerations:</strong>
 * <ul>
 *   <li><strong>Thread Pool Sizing</strong> - Balance between parallelism and overhead.
 *       Too few threads = behaviors wait; too many = context switching overhead.</li>
 *   <li><strong>Scheduling Overhead</strong> - Very short intervals ({@literal <} 100ms) can
 *       saturate the scheduler. Consider event-driven or batching approaches.</li>
 *   <li><strong>Behavior Duration</strong> - Long-running behaviors can block others.
 *       Use async operations or dedicated executors for I/O-intensive behaviors.</li>
 *   <li><strong>Memory</strong> - Keep tracking structures lean. Cancel completed
 *       behaviors to free resources.</li>
 * </ul>
 *
 * <p><strong>Advanced Features (Implementation-Specific):</strong>
 * Implementations may provide additional features:
 * <ul>
 *   <li>Priority-based scheduling</li>
 *   <li>Behavior dependencies and ordering</li>
 *   <li>Resource quotas and throttling</li>
 *   <li>Metrics and monitoring</li>
 *   <li>Distributed scheduling across nodes</li>
 * </ul>
 *
 * @since 0.1.0
 * @see Behavior
 * @see BehaviorType
 * @see Agent
 */
public interface BehaviorScheduler {

    /**
     * Schedules a behavior for execution according to its type and configuration.
     *
     * <p>This method registers the behavior with the scheduler and begins its
     * execution lifecycle. The actual scheduling strategy depends on the
     * behavior's {@link BehaviorType}:
     *
     * <ul>
     *   <li><strong>ONE_SHOT</strong>: Executed once immediately, then marked inactive</li>
     *   <li><strong>CYCLIC</strong>: Executed repeatedly at the interval specified by
     *       {@link Behavior#getInterval()}</li>
     *   <li><strong>EVENT_DRIVEN</strong>: Registered but not scheduled (responds to
     *       events/messages)</li>
     *   <li><strong>WAKER</strong>: Checks wake conditions periodically</li>
     *   <li><strong>CUSTOM</strong>: Implementation-defined scheduling</li>
     * </ul>
     *
     * <p><strong>Preconditions:</strong>
     * <ul>
     *   <li>Scheduler must be running ({@link #isRunning()} returns true)</li>
     *   <li>Behavior must be active ({@link Behavior#isActive()} returns true)</li>
     *   <li>CYCLIC behaviors must have a non-null interval</li>
     * </ul>
     *
     * <p><strong>Scheduling Timing:</strong>
     * <ul>
     *   <li><strong>Immediate</strong> - ONE_SHOT behaviors execute immediately upon
     *       scheduling</li>
     *   <li><strong>Delayed Start</strong> - CYCLIC behaviors may have an initial
     *       delay before first execution (implementation-dependent)</li>
     *   <li><strong>No Execution</strong> - EVENT_DRIVEN behaviors are registered
     *       but don't execute until triggered</li>
     * </ul>
     *
     * <p><strong>Idempotency:</strong>
     * Scheduling the same behavior multiple times (same behavior ID) should:
     * <ul>
     *   <li>Either replace the existing scheduled instance</li>
     *   <li>Or ignore the duplicate request (log a warning)</li>
     *   <li>Not create duplicate concurrent executions</li>
     * </ul>
     *
     * <p><strong>Error Handling:</strong>
     * The returned future completes exceptionally if:
     * <ul>
     *   <li>Scheduler is not running</li>
     *   <li>Behavior configuration is invalid (e.g., CYCLIC without interval)</li>
     *   <li>Scheduling resources are exhausted</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe and can be called concurrently from multiple
     * threads. Behaviors may be added while the scheduler is actively executing
     * other behaviors.
     *
     * <p>Example - Scheduling different types:
     * <pre>{@code
     * // One-shot initialization
     * Behavior init = new OneShotBehavior("init", this::initialize);
     * scheduler.schedule(init)
     *     .thenRun(() -> log.info("Initialization scheduled"));
     *
     * // Cyclic monitoring (every 30 seconds)
     * Behavior monitor = new CyclicBehavior("monitor",
     *     Duration.ofSeconds(30),
     *     this::checkHealth);
     * scheduler.schedule(monitor);
     *
     * // Event-driven message handler
     * Behavior handler = new EventDrivenBehavior("order-handler");
     * scheduler.schedule(handler);  // Registered, waits for events
     * }</pre>
     *
     * <p>Example - Scheduling with error handling:
     * <pre>{@code
     * scheduler.schedule(behavior)
     *     .thenRun(() -> {
     *         log.info("Behavior {} scheduled successfully",
     *                  behavior.getBehaviorId());
     *     })
     *     .exceptionally(ex -> {
     *         log.error("Failed to schedule behavior {}: {}",
     *                   behavior.getBehaviorId(), ex.getMessage());
     *         // Retry or handle failure
     *         return null;
     *     });
     * }</pre>
     *
     * @param behavior the behavior to schedule, must not be null
     * @return a {@code CompletableFuture} that completes when the behavior is
     *         successfully scheduled, or completes exceptionally if scheduling fails
     * @throws NullPointerException if behavior is null
     * @see #cancel(String)
     * @see Behavior#getType()
     * @see Behavior#getInterval()
     */
    CompletableFuture<Void> schedule(Behavior behavior);

    /**
     * Cancels a scheduled behavior, stopping its execution.
     *
     * <p>This method removes the behavior from the scheduler and prevents any
     * future executions. The behavior is also marked as inactive via
     * {@link Behavior#stop()}.
     *
     * <p><strong>Cancellation Semantics:</strong>
     * <ul>
     *   <li><strong>Immediate</strong> - No new executions will be scheduled</li>
     *   <li><strong>Graceful</strong> - Current execution (if any) completes naturally</li>
     *   <li><strong>Cleanup</strong> - Scheduler resources for this behavior are freed</li>
     * </ul>
     *
     * <p><strong>Behavior State:</strong>
     * After cancellation:
     * <ul>
     *   <li>{@link Behavior#isActive()} returns {@code false}</li>
     *   <li>Behavior cannot be rescheduled (create new instance instead)</li>
     *   <li>Any resources held by the behavior should be cleaned up</li>
     * </ul>
     *
     * <p><strong>Return Value:</strong>
     * <ul>
     *   <li>{@code true} - Behavior was found and successfully canceled</li>
     *   <li>{@code false} - No behavior with given ID was scheduled</li>
     * </ul>
     *
     * <p><strong>Idempotency:</strong>
     * Multiple calls to {@code cancel()} with the same ID are safe:
     * <ul>
     *   <li>First call: returns {@code true}, behavior is canceled</li>
     *   <li>Subsequent calls: return {@code false} (behavior already gone)</li>
     * </ul>
     *
     * <p><strong>Concurrent Execution:</strong>
     * If the behavior is currently executing when {@code cancel()} is called:
     * <ol>
     *   <li>The behavior is marked inactive immediately</li>
     *   <li>Current execution continues to completion</li>
     *   <li>No new execution is scheduled</li>
     * </ol>
     *
     * <pre>{@code
     * // Example: Behavior checks active flag during execution
     * public CompletableFuture<Void> execute() {
     *     return CompletableFuture.runAsync(() -> {
     *         while (isActive() && hasMoreWork()) {
     *             // Will exit loop when cancel() is called
     *             processNextItem();
     *         }
     *     });
     * }
     * }</pre>
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe and can be called:
     * <ul>
     *   <li>While the behavior is executing</li>
     *   <li>Concurrently with {@link #schedule(Behavior)}</li>
     *   <li>From multiple threads simultaneously</li>
     * </ul>
     *
     * <p><strong>Use Cases:</strong>
     * <ul>
     *   <li>Dynamic behavior management (remove temporary behaviors)</li>
     *   <li>Agent reconfiguration (cancel old, schedule new)</li>
     *   <li>Resource cleanup (cancel idle or errored behaviors)</li>
     *   <li>Graceful shutdown (cancel all behaviors before stop)</li>
     * </ul>
     *
     * <p>Example - Simple cancellation:
     * <pre>{@code
     * if (scheduler.cancel("monitor")) {
     *     log.info("Monitor behavior canceled");
     * } else {
     *     log.warn("Monitor behavior not found");
     * }
     * }</pre>
     *
     * <p>Example - Conditional cancellation:
     * <pre>{@code
     * // Cancel all behaviors matching a criteria
     * behaviors.stream()
     *     .filter(b -> b.getType() == BehaviorType.CYCLIC)
     *     .filter(b -> b.getInterval().toSeconds() < 60)
     *     .forEach(b -> scheduler.cancel(b.getBehaviorId()));
     * }</pre>
     *
     * @param behaviorId the unique identifier of the behavior to cancel, must not be null
     * @return {@code true} if the behavior was found and canceled, {@code false} if no
     *         behavior with the given ID was scheduled
     * @throws NullPointerException if behaviorId is null
     * @see #schedule(Behavior)
     * @see Behavior#stop()
     * @see #stop()
     */
    boolean cancel(String behaviorId);

    /**
     * Checks whether the scheduler is currently running.
     *
     * <p>A scheduler is considered running if it has been started and not yet
     * stopped. Only running schedulers can schedule new behaviors.
     *
     * <p><strong>State Semantics:</strong>
     * <ul>
     *   <li>{@code true} - Scheduler is active and can schedule behaviors</li>
     *   <li>{@code false} - Scheduler is stopped or not yet started</li>
     * </ul>
     *
     * <p><strong>State Transitions:</strong>
     * <ul>
     *   <li>After construction: {@code false}</li>
     *   <li>After {@link #start()}: {@code true}</li>
     *   <li>After {@link #stop()}: {@code false}</li>
     * </ul>
     *
     * <p><strong>Usage:</strong>
     * Check this flag before scheduling behaviors:
     * <pre>{@code
     * if (scheduler.isRunning()) {
     *     scheduler.schedule(behavior);
     * } else {
     *     log.warn("Cannot schedule: scheduler not running");
     * }
     * }</pre>
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe and can be called concurrently with
     * {@link #start()} and {@link #stop()}.
     *
     * @return {@code true} if the scheduler is running and can accept new behaviors,
     *         {@code false} otherwise
     * @see #start()
     * @see #stop()
     * @see #schedule(Behavior)
     */
    boolean isRunning();

    /**
     * Starts the scheduler, enabling it to schedule and execute behaviors.
     *
     * <p>This method initializes the scheduler's resources (thread pools,
     * executors, etc.) and transitions it to the running state. After a
     * successful start, behaviors can be scheduled for execution.
     *
     * <p><strong>Initialization Steps:</strong>
     * <ol>
     *   <li>Allocate thread pool or execution resources</li>
     *   <li>Initialize internal data structures</li>
     *   <li>Set running flag to {@code true}</li>
     *   <li>Begin accepting behavior scheduling requests</li>
     * </ol>
     *
     * <p><strong>Idempotency:</strong>
     * Calling {@code start()} on an already running scheduler should:
     * <ul>
     *   <li>Be a no-op (return immediately)</li>
     *   <li>Return a completed future</li>
     *   <li>Log a warning (optional)</li>
     * </ul>
     *
     * <p><strong>Asynchronous Operation:</strong>
     * The returned {@code CompletableFuture} completes when the scheduler is
     * fully started and ready to accept behaviors. Use this to ensure
     * initialization is complete before scheduling:
     * <pre>{@code
     * scheduler.start()
     *     .thenRun(() -> {
     *         // Scheduler is ready
     *         scheduler.schedule(behavior1);
     *         scheduler.schedule(behavior2);
     *     })
     *     .exceptionally(ex -> {
     *         log.error("Failed to start scheduler", ex);
     *         return null;
     *     });
     * }</pre>
     *
     * <p><strong>Error Handling:</strong>
     * The returned future completes exceptionally if:
     * <ul>
     *   <li>Thread pool creation fails</li>
     *   <li>Required resources are unavailable</li>
     *   <li>Configuration is invalid</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe. Concurrent calls to {@code start()} should
     * result in only one actual initialization.
     *
     * <p><strong>Lifecycle Pattern:</strong>
     * <pre>{@code
     * // Creation
     * BehaviorScheduler scheduler = new SimpleBehaviorScheduler();
     *
     * // Start (asynchronous)
     * scheduler.start().join();
     *
     * // Use
     * scheduler.schedule(behavior);
     *
     * // Stop (asynchronous)
     * scheduler.stop().join();
     * }</pre>
     *
     * @return a {@code CompletableFuture} that completes when the scheduler is
     *         fully started and ready to accept behaviors, or completes
     *         exceptionally if startup fails
     * @see #stop()
     * @see #isRunning()
     */
    CompletableFuture<Void> start();

    /**
     * Stops the scheduler and cancels all scheduled behaviors gracefully.
     *
     * <p>This method initiates a graceful shutdown of the scheduler:
     * <ol>
     *   <li>Stop accepting new behaviors</li>
     *   <li>Cancel all currently scheduled behaviors</li>
     *   <li>Allow current executions to complete</li>
     *   <li>Shutdown thread pools and release resources</li>
     *   <li>Transition to stopped state</li>
     * </ol>
     *
     * <p><strong>Graceful Shutdown:</strong>
     * The scheduler attempts to stop cleanly:
     * <ul>
     *   <li><strong>Current executions</strong> - Allowed to complete naturally</li>
     *   <li><strong>Future executions</strong> - Canceled immediately</li>
     *   <li><strong>Resources</strong> - Released after all work completes</li>
     * </ul>
     *
     * <p><strong>Timeout Behavior:</strong>
     * Implementations may enforce a timeout for shutdown:
     * <ul>
     *   <li>Wait for current executions (e.g., 30 seconds)</li>
     *   <li>Forcefully interrupt if timeout exceeded</li>
     *   <li>Log warnings for behaviors that didn't stop cleanly</li>
     * </ul>
     *
     * <p><strong>Idempotency:</strong>
     * Calling {@code stop()} on an already stopped scheduler should:
     * <ul>
     *   <li>Be a no-op (return immediately)</li>
     *   <li>Return a completed future</li>
     *   <li>Not throw exceptions</li>
     * </ul>
     *
     * <p><strong>Behavior State After Stop:</strong>
     * All scheduled behaviors will have:
     * <ul>
     *   <li>{@link Behavior#isActive()} returning {@code false}</li>
     *   <li>{@link Behavior#stop()} called automatically</li>
     *   <li>No pending or future executions</li>
     * </ul>
     *
     * <p><strong>Resource Cleanup:</strong>
     * The scheduler must clean up:
     * <ul>
     *   <li>Thread pools and executors</li>
     *   <li>Scheduled tasks and timers</li>
     *   <li>Behavior tracking structures</li>
     *   <li>Any native resources (connections, files, etc.)</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe. Concurrent calls to {@code stop()} should
     * result in only one actual shutdown sequence.
     *
     * <p><strong>Async Completion:</strong>
     * The returned future completes when shutdown is complete:
     * <pre>{@code
     * scheduler.stop()
     *     .thenRun(() -> {
     *         log.info("Scheduler stopped cleanly");
     *         // Safe to release other resources
     *     })
     *     .exceptionally(ex -> {
     *         log.error("Error during scheduler shutdown", ex);
     *         return null;
     *     });
     * }</pre>
     *
     * <p><strong>Agent Integration:</strong>
     * Agents typically stop their schedulers during shutdown:
     * <pre>{@code
     * @Override
     * public CompletableFuture<Void> stop() {
     *     return scheduler.stop()
     *         .thenCompose(v -> unregisterFromDirectory())
     *         .thenRun(() -> releaseResources());
     * }
     * }</pre>
     *
     * <p><strong>Restart Behavior:</strong>
     * After stopping, a scheduler typically cannot be restarted. Create a new
     * instance if needed:
     * <pre>{@code
     * // Stop old scheduler
     * oldScheduler.stop().join();
     *
     * // Create and start new scheduler
     * BehaviorScheduler newScheduler = new SimpleBehaviorScheduler();
     * newScheduler.start().join();
     *
     * // Re-schedule behaviors
     * behaviors.forEach(newScheduler::schedule);
     * }</pre>
     *
     * @return a {@code CompletableFuture} that completes when the scheduler and
     *         all its behaviors are fully stopped, or completes exceptionally
     *         if shutdown encounters errors
     * @see #start()
     * @see #isRunning()
     * @see #cancel(String)
     * @see Behavior#stop()
     */
    CompletableFuture<Void> stop();
}
