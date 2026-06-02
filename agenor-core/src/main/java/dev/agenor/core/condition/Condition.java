package dev.agenor.core.condition;

import dev.agenor.core.Agent;

/**
 * Functional interface for defining conditions that control behavior execution
 * in the Jentic multi-agent framework.
 *
 * <p>Conditions are predicates that evaluate to {@code true} or {@code false}
 * based on agent state, system metrics, time, or any custom criteria. They are
 * primarily used with conditional behaviors to enable smart, context-aware
 * agent actions.
 *
 * <p><strong>Core Concept:</strong>
 * A condition is a gatekeeper that determines whether a behavior should execute.
 * Instead of executing blindly, behaviors can check conditions first:
 * <pre>
 * If (condition is satisfied) → Execute behavior
 * Else → Skip execution
 * </pre>
 *
 * <p><strong>Common Use Cases:</strong>
 * <table border="1">
 *   <caption>Common condition usage scenarios</caption>
 *   <tr>
 *     <th>Use Case</th>
 *     <th>Example Condition</th>
 *   </tr>
 *   <tr>
 *     <td>Resource Management</td>
 *     <td>Only process when CPU &lt; 80%</td>
 *   </tr>
 *   <tr>
 *     <td>Time-Based Logic</td>
 *     <td>Only run during business hours</td>
 *   </tr>
 *   <tr>
 *     <td>Agent State</td>
 *     <td>Only execute if agent is RUNNING</td>
 *   </tr>
 *   <tr>
 *     <td>External State</td>
 *     <td>Only send if queue size &gt; threshold</td>
 *   </tr>
 *   <tr>
 *     <td>Complex Logic</td>
 *     <td>Weekday AND (CPU &lt; 80% OR Memory &lt; 70%)</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Functional Interface:</strong>
 * Being a {@code @FunctionalInterface}, conditions can be created using:
 * <ul>
 *   <li>Lambda expressions</li>
 *   <li>Method references</li>
 *   <li>Static factory methods</li>
 *   <li>Pre-built condition libraries</li>
 * </ul>
 *
 * <p><strong>Condition Composition:</strong>
 * Conditions can be combined using logical operators to create complex logic:
 * <ul>
 *   <li><strong>AND</strong> ({@link #and(Condition)}) - Both conditions must be true</li>
 *   <li><strong>OR</strong> ({@link #or(Condition)}) - Either condition must be true</li>
 *   <li><strong>NOT</strong> ({@link #negate()}) - Inverts the condition result</li>
 * </ul>
 *
 * <p><strong>Example - Simple Lambda Condition:</strong>
 * <pre>{@code
 * // Check if CPU usage is below 80%
 * Condition lowCpu = agent -> {
 *     SystemMetrics metrics = SystemMetrics.current();
 *     return metrics.cpuUsage() < 80.0;
 * };
 *
 * // Use in conditional behavior
 * ConditionalBehavior behavior = new ConditionalBehavior("process-data", lowCpu) {
 *     @Override
 *     protected void conditionalAction() {
 *         processData();  // Only runs when CPU is low
 *     }
 * };
 * }</pre>
 *
 * <p><strong>Example - Composed Conditions:</strong>
 * <pre>{@code
 * // Business hours: Weekday AND between 9 AM - 5 PM
 * Condition isWeekday = agent -> {
 *     DayOfWeek day = LocalDateTime.now().getDayOfWeek();
 *     return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
 * };
 *
 * Condition isBusinessHours = agent -> {
 *     LocalTime now = LocalTime.now();
 *     return now.isAfter(LocalTime.of(9, 0)) &&
 *            now.isBefore(LocalTime.of(17, 0));
 * };
 *
 * // Combine conditions
 * Condition businessHours = isWeekday.and(isBusinessHours);
 *
 * // Complex condition: Business hours AND (low CPU OR low memory)
 * Condition safeToProcess = businessHours.and(
 *     lowCpu.or(lowMemory)
 * );
 * }</pre>
 *
 * <p><strong>Example - Agent State Conditions:</strong>
 * <pre>{@code
 * // Check if agent is running
 * Condition isRunning = Agent::isRunning;
 *
 * // Check agent ID pattern
 * Condition isProcessorAgent = agent ->
 *     agent.getAgentId().startsWith("processor-");
 *
 * // Only execute if specific agent type is running
 * Condition processorRunning = isRunning.and(isProcessorAgent);
 * }</pre>
 *
 * <p><strong>Example - Custom Domain Conditions:</strong>
 * <pre>{@code
 * // Check if order queue exceeds threshold
 * Condition queueOverloaded = agent -> {
 *     OrderQueue queue = getOrderQueue();
 *     return queue.size() > 100;
 * };
 *
 * // Check if external service is healthy
 * Condition serviceHealthy = agent -> {
 *     return healthCheckService.isHealthy("payment-service");
 * };
 *
 * // Only process orders if queue is not overloaded AND service is healthy
 * Condition canProcessOrders = queueOverloaded.negate().and(serviceHealthy);
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong>
 * Condition implementations should be thread-safe as they may be evaluated:
 * <ul>
 *   <li>Concurrently by multiple behaviors</li>
 *   <li>From different threads in the scheduler</li>
 *   <li>While system state is changing</li>
 * </ul>
 *
 * <p><strong>Performance Considerations:</strong>
 * <ul>
 *   <li><strong>Keep evaluation fast</strong> - Conditions are checked before each
 *       execution. Expensive operations (I/O, network calls) should be avoided.</li>
 *   <li><strong>Cache when appropriate</strong> - For slow-changing state, cache
 *       results with a TTL rather than recomputing on every evaluation.</li>
 *   <li><strong>Short-circuit evaluation</strong> - AND/OR operations stop as soon
 *       as the result is known, so order conditions by evaluation cost.</li>
 * </ul>
 *
 * <p><strong>Error Handling:</strong>
 * If a condition throws an exception during evaluation:
 * <ul>
 *   <li>The exception should be caught by the behavior or scheduler</li>
 *   <li>The condition is typically treated as {@code false} (fail-safe)</li>
 *   <li>The error should be logged for debugging</li>
 *   <li>Behavior execution is skipped for safety</li>
 * </ul>
 *
 * <pre>{@code
 * // Safe condition with error handling
 * Condition safeCondition = agent -> {
 *     try {
 *         return expensiveCheck();
 *     } catch (Exception e) {
 *         log.error("Condition evaluation failed", e);
 *         return false;  // Fail-safe: skip execution
 *     }
 * };
 * }</pre>
 *
 * <p><strong>Best Practices:</strong>
 * <ul>
 *   <li>Name conditions descriptively (e.g., {@code lowCpu}, {@code businessHours})</li>
 *   <li>Keep evaluation logic simple and fast</li>
 *   <li>Reuse common conditions across behaviors</li>
 *   <li>Document complex condition logic</li>
 *   <li>Test conditions independently of behaviors</li>
 *   <li>Use composition instead of creating monolithic conditions</li>
 * </ul>
 *
 * <p><strong>Integration with Behaviors:</strong>
 * Conditions are primarily used with conditional behaviors, but can also be
 * used in:
 * <ul>
 *   <li>Message filters (filter messages based on conditions)</li>
 *   <li>Agent lifecycle hooks (conditional startup/shutdown)</li>
 *   <li>Dynamic behavior scheduling (schedule when conditions change)</li>
 *   <li>Workflow decisions (FSM state transitions)</li>
 * </ul>
 *
 * @since 0.2.0
 * @see ConditionContext
 * @see Agent
 */
@FunctionalInterface
public interface Condition {

    /**
     * Evaluates this condition in the context of an agent.
     *
     * <p>This method determines whether the condition is currently satisfied
     * based on the agent's state, system metrics, time, or any other relevant
     * criteria. It should return {@code true} if the condition is met and the
     * associated behavior should execute, or {@code false} otherwise.
     *
     * <p><strong>Evaluation Context:</strong>
     * The agent parameter provides access to:
     * <ul>
     *   <li>Agent identity ({@link Agent#getAgentId()}, {@link Agent#getAgentName()})</li>
     *   <li>Running state ({@link Agent#isRunning()})</li>
     *   <li>Message service for checking message queues</li>
     *   <li>Custom agent state (if agent implements additional methods)</li>
     * </ul>
     *
     * <p><strong>Pure Function Recommendation:</strong>
     * Ideally, condition evaluation should be a pure function with no side effects.
     * It should only observe state, not modify it. This ensures:
     * <ul>
     *   <li>Predictable behavior</li>
     *   <li>Safe concurrent evaluation</li>
     *   <li>Easy testing and debugging</li>
     *   <li>Cacheable results</li>
     * </ul>
     *
     * <p><strong>Performance:</strong>
     * This method may be called frequently (potentially on every behavior execution
     * cycle), so it should be fast. Avoid:
     * <ul>
     *   <li>Blocking I/O operations</li>
     *   <li>Network calls</li>
     *   <li>Heavy computations</li>
     *   <li>Database queries</li>
     * </ul>
     *
     * If expensive checks are necessary, consider:
     * <ul>
     *   <li>Caching results with a TTL</li>
     *   <li>Asynchronous pre-computation</li>
     *   <li>Throttling evaluation frequency</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method must be thread-safe as it may be called concurrently from
     * multiple threads. If accessing shared state, ensure proper synchronization.
     *
     * <p><strong>Exception Handling:</strong>
     * If this method throws an exception:
     * <ul>
     *   <li>The behavior framework will catch it</li>
     *   <li>The condition is treated as {@code false} (fail-safe)</li>
     *   <li>The exception is logged</li>
     *   <li>Behavior execution is skipped</li>
     * </ul>
     *
     * To handle errors explicitly:
     * <pre>{@code
     * @Override
     * public boolean evaluate(Agent agent) {
     *     try {
     *         return riskyCheck();
     *     } catch (Exception e) {
     *         log.warn("Condition check failed: {}", e.getMessage());
     *         return false;  // Explicit fail-safe behavior
     *     }
     * }
     * }</pre>
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Simple boolean check:
     * <pre>{@code
     * public boolean evaluate(Agent agent) {
     *     return agent.isRunning();
     * }
     * }</pre>
     *
     * <p>System metrics check:
     * <pre>{@code
     * public boolean evaluate(Agent agent) {
     *     SystemMetrics metrics = SystemMetrics.current();
     *     return metrics.cpuUsage() < 80.0 && metrics.memoryUsage() < 80.0;
     * }
     * }</pre>
     *
     * <p>Time-based check:
     * <pre>{@code
     * public boolean evaluate(Agent agent) {
     *     LocalTime now = LocalTime.now();
     *     return now.isAfter(LocalTime.of(9, 0)) &&
     *            now.isBefore(LocalTime.of(17, 0));
     * }
     * }</pre>
     *
     * <p>Custom domain check:
     * <pre>{@code
     * public boolean evaluate(Agent agent) {
     *     if (agent instanceof OrderProcessorAgent orderAgent) {
     *         return orderAgent.getQueueSize() < 100 &&
     *                orderAgent.isPaymentServiceHealthy();
     *     }
     *     return false;
     * }
     * }</pre>
     *
     * @param agent the agent in whose context the condition is evaluated, never null
     * @return {@code true} if the condition is satisfied and behavior should execute,
     *         {@code false} otherwise
     * @throws RuntimeException if evaluation fails (will be caught and logged by framework)
     * @see Agent
     */
    boolean evaluate(Agent agent);

    /**
     * Returns a composed condition that represents a logical AND of this condition
     * and another condition.
     *
     * <p>The composed condition short-circuits: if this condition evaluates to
     * {@code false}, the other condition is not evaluated. This is both a
     * performance optimization and important for conditions with side effects.
     *
     * <p><strong>Evaluation Order:</strong>
     * <ol>
     *   <li>This condition is evaluated first</li>
     *   <li>If {@code false}, return {@code false} immediately (short-circuit)</li>
     *   <li>If {@code true}, evaluate the other condition</li>
     *   <li>Return the result of the other condition</li>
     * </ol>
     *
     * <p><strong>Truth Table:</strong>
     * <table border="1">
     *   <caption>Truth table for logical AND operation</caption>
     *   <tr><th>this</th><th>other</th><th>result</th></tr>
     *   <tr><td>false</td><td>-</td><td>false</td></tr>
     *   <tr><td>true</td><td>false</td><td>false</td></tr>
     *   <tr><td>true</td><td>true</td><td>true</td></tr>
     * </table>
     *
     * <p><strong>Performance Optimization:</strong>
     * Order conditions by evaluation cost to minimize unnecessary evaluations:
     * <pre>{@code
     * // GOOD: Check cheap condition first
     * Condition efficient = cheapCheck.and(expensiveCheck);
     *
     * // LESS EFFICIENT: Expensive check runs even if cheap check fails
     * Condition lessEfficient = expensiveCheck.and(cheapCheck);
     * }</pre>
     *
     * <p><strong>Chaining Multiple Conditions:</strong>
     * <pre>{@code
     * // All conditions must be true
     * Condition allMustPass = condition1
     *     .and(condition2)
     *     .and(condition3)
     *     .and(condition4);
     *
     * // Execution during business hours when system is healthy
     * Condition safeToRun = TimeCondition.businessHours()
     *     .and(SystemCondition.cpuBelow(80.0))
     *     .and(SystemCondition.memoryBelow(80.0))
     *     .and(customHealthCheck);
     * }</pre>
     *
     * <p><strong>Error Handling:</strong>
     * If either condition throws an exception:
     * <ul>
     *   <li>The exception propagates to the caller</li>
     *   <li>No further conditions are evaluated</li>
     *   <li>The composed condition is treated as {@code false}</li>
     * </ul>
     *
     * @param other the condition to be logically ANDed with this condition, must not be null
     * @return a composed condition that represents the logical AND of this condition
     *         and the other condition
     * @throws NullPointerException if other is null
     * @see #or(Condition)
     * @see #negate()
     */
    default Condition and(Condition other) {
        return agent -> this.evaluate(agent) && other.evaluate(agent);
    }

    /**
     * Returns a composed condition that represents a logical OR of this condition
     * and another condition.
     *
     * <p>The composed condition short-circuits: if this condition evaluates to
     * {@code true}, the other condition is not evaluated.
     *
     * <p><strong>Evaluation Order:</strong>
     * <ol>
     *   <li>This condition is evaluated first</li>
     *   <li>If {@code true}, return {@code true} immediately (short-circuit)</li>
     *   <li>If {@code false}, evaluate the other condition</li>
     *   <li>Return the result of the other condition</li>
     * </ol>
     *
     * <p><strong>Truth Table:</strong>
     * <table border="1">
     *   <caption>Truth table for logical OR operation</caption>
     *   <tr><th>this</th><th>other</th><th>result</th></tr>
     *   <tr><td>true</td><td>-</td><td>true</td></tr>
     *   <tr><td>false</td><td>true</td><td>true</td></tr>
     *   <tr><td>false</td><td>false</td><td>false</td></tr>
     * </table>
     *
     * <p><strong>Use Cases:</strong>
     * OR conditions are useful for fallback logic or multiple acceptable states:
     * <ul>
     *   <li>Execute during business hours OR when emergency flag is set</li>
     *   <li>Process when CPU is low OR memory is low (either resource available)</li>
     *   <li>Proceed if primary service is up OR backup service is up</li>
     * </ul>
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Resource availability:
     * <pre>{@code
     * // Execute if either CPU or memory is available
     * Condition resourceAvailable = SystemCondition.cpuBelow(70.0)
     *     .or(SystemCondition.memoryBelow(70.0));
     * }</pre>
     *
     * <p>Time windows:
     * <pre>{@code
     * // Execute during business hours OR in maintenance window
     * Condition executionAllowed = TimeCondition.businessHours()
     *     .or(TimeCondition.betweenHours(22, 24));  // 10 PM - Midnight
     * }</pre>
     *
     * <p>Service availability:
     * <pre>{@code
     * // Use primary or fallback service
     * Condition serviceAvailable = primaryServiceHealthy
     *     .or(fallbackServiceHealthy);
     * }</pre>
     *
     * <p><strong>Complex Logic:</strong>
     * Combine AND and OR for complex conditions (use parentheses carefully):
     * <pre>{@code
     * // (Weekday AND business hours) OR emergency mode
     * Condition canProcess = TimeCondition.weekday()
     *     .and(TimeCondition.businessHours())
     *     .or(emergencyMode);
     *
     * // Process if: (CPU &lt; 80% OR Memory &lt; 80%) AND queue not empty
     * Condition shouldProcess = cpuLow.or(memoryLow).and(queueNotEmpty);
     * }</pre>
     *
     * @param other the condition to be logically ORed with this condition, must not be null
     * @return a composed condition that represents the logical OR of this condition
     *         and the other condition
     * @throws NullPointerException if other is null
     * @see #and(Condition)
     * @see #negate()
     */
    default Condition or(Condition other) {
        return agent -> this.evaluate(agent) || other.evaluate(agent);
    }

    /**
     * Returns a condition that represents the logical negation of this condition.
     *
     * <p>The negated condition returns {@code true} when this condition returns
     * {@code false}, and vice versa. This is useful for inverting condition logic
     * without creating a separate condition.
     *
     * <p><strong>Truth Table:</strong>
     * <table border="1">
     *   <caption>Truth table for logical NOT operation</caption>
     *   <tr><th>this</th><th>result</th></tr>
     *   <tr><td>true</td><td>false</td></tr>
     *   <tr><td>false</td><td>true</td></tr>
     * </table>
     *
     * <p><strong>Use Cases:</strong>
     * <ul>
     *   <li>Execute when system is NOT overloaded</li>
     *   <li>Proceed if queue is NOT empty</li>
     *   <li>Run if agent is NOT in maintenance mode</li>
     *   <li>Process when it's NOT weekend</li>
     * </ul>
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Inverting simple conditions:
     * <pre>{@code
     * // Execute when CPU is NOT high (i.e., below threshold)
     * Condition cpuHigh = SystemCondition.cpuAbove(80.0);
     * Condition cpuOk = cpuHigh.negate();
     *
     * // Same as:
     * Condition cpuOk = SystemCondition.cpuBelow(80.0);
     * }</pre>
     *
     * <p>Negating composed conditions:
     * <pre>{@code
     * // Execute when it's NOT (weekend OR after hours)
     * Condition offTime = TimeCondition.weekend()
     *     .or(TimeCondition.afterHour(18));
     * Condition workTime = offTime.negate();
     *
     * // Execute when NOT (low CPU AND low memory)
     * // i.e., when resources are constrained
     * Condition resourcesGood = cpuLow.and(memoryLow);
     * Condition resourcesConstrained = resourcesGood.negate();
     * }</pre>
     *
     * <p><strong>De Morgan's Laws:</strong>
     * When negating composed conditions, be aware of logical equivalences:
     * <ul>
     *   <li>NOT (A AND B) = (NOT A) OR (NOT B)</li>
     *   <li>NOT (A OR B) = (NOT A) AND (NOT B)</li>
     * </ul>
     *
     * <pre>{@code
     * // These are equivalent:
     * Condition notBoth = a.and(b).negate();
     * Condition eitherNot = a.negate().or(b.negate());
     *
     * // These are equivalent:
     * Condition notEither = a.or(b).negate();
     * Condition bothNot = a.negate().and(b.negate());
     * }</pre>
     *
     * <p><strong>Double Negation:</strong>
     * Negating twice returns to the original condition:
     * <pre>{@code
     * Condition original = someCondition;
     * Condition doubleNegated = original.negate().negate();
     * // doubleNegated is logically equivalent to original
     * }</pre>
     *
     * @return a condition that represents the logical negation of this condition
     * @see #and(Condition)
     * @see #or(Condition)
     */
    default Condition negate() {
        return agent -> !this.evaluate(agent);
    }

    /**
     * Returns a condition that always evaluates to {@code true}.
     *
     * <p>This is a constant condition that ignores all inputs and always returns
     * {@code true}. It's useful as:
     * <ul>
     *   <li>A default condition when none is specified</li>
     *   <li>A placeholder during development or testing</li>
     *   <li>A base case for conditional logic</li>
     *   <li>Temporarily disabling condition checks</li>
     * </ul>
     *
     * <p><strong>Use Cases:</strong>
     *
     * <p>Default condition:
     * <pre>{@code
     * public ConditionalBehavior(String id, Runnable action) {
     *     this(id, Condition.always(), action);  // No condition = always run
     * }
     * }</pre>
     *
     * <p>Testing/debugging:
     * <pre>{@code
     * // Temporarily bypass condition during debugging
     * Condition debugMode = true ? Condition.always() : complexCondition;
     * }</pre>
     *
     * <p>Conditional logic building:
     * <pre>{@code
     * Condition condition = Condition.always();
     * if (checkCpu) {
     *     condition = condition.and(SystemCondition.cpuBelow(80.0));
     * }
     * if (checkMemory) {
     *     condition = condition.and(SystemCondition.memoryBelow(80.0));
     * }
     * }</pre>
     *
     * @return a condition that always evaluates to {@code true}
     * @see #never()
     */
    static Condition always() {
        return agent -> true;
    }

    /**
     * Returns a condition that always evaluates to {@code false}.
     *
     * <p>This is a constant condition that ignores all inputs and always returns
     * {@code false}. It's useful for:
     * <ul>
     *   <li>Temporarily disabling a behavior without removing it</li>
     *   <li>Placeholder for unimplemented conditions</li>
     *   <li>Testing condition-based logic</li>
     *   <li>Explicit "never execute" scenarios</li>
     * </ul>
     *
     * <p><strong>Use Cases:</strong>
     *
     * <p>Temporary disable:
     * <pre>{@code
     * // Disable behavior without removing it
     * ConditionalBehavior debugBehavior = new ConditionalBehavior(
     *     "debug-only",
     *     Condition.never(),  // Disabled
     *     this::debugAction
     * );
     * }</pre>
     *
     * <p>Feature flags:
     * <pre>{@code
     * Condition featureCondition = featureEnabled
     *     ? actualCondition
     *     : Condition.never();
     * }</pre>
     *
     * <p>Conditional factory:
     * <pre>{@code
     * public static Condition createCondition(boolean enabled) {
     *     return enabled ? realCondition : Condition.never();
     * }
     * }</pre>
     *
     * @return a condition that always evaluates to {@code false}
     * @see #always()
     */
    static Condition never() {
        return agent -> false;
    }
}
