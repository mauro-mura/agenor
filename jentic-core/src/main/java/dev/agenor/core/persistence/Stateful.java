package dev.agenor.core.persistence;

import dev.agenor.core.annotations.PersistenceConfig;

/**
 * Mixin interface for agents that support state persistence.
 *
 * <p>Agents implementing this interface participate in the Jentic persistence
 * lifecycle: the framework (via a persistence manager or equivalent)
 * calls {@link #captureState()} to snapshot the agent's state and
 * {@link #restoreState(AgentState)} to recover it after a restart or failure.
 *
 * <p><strong>Typical usage pattern:</strong>
 * <pre>{@code
 * @Agent("order-processor")
 * @PersistenceConfig(strategy = PersistenceStrategy.ON_STOP)
 * public class OrderProcessorAgent extends BaseAgent implements Stateful {
 *
 *     private int ordersProcessed = 0;
 *
 *     @Override
 *     public AgentState captureState() {
 *         return AgentState.builder(getAgentId())
 *             .agentName(getAgentName())
 *             .agentType("processor")
 *             .status(isRunning() ? AgentStatus.RUNNING : AgentStatus.STOPPED)
 *             .data("ordersProcessed", ordersProcessed)
 *             .build();
 *     }
 *
 *     @Override
 *     public void restoreState(AgentState state) {
 *         Integer saved = state.getData("ordersProcessed", Integer.class);
 *         if (saved != null) {
 *             ordersProcessed = saved;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> Implementations must ensure that
 * {@link #captureState()} produces a consistent snapshot even when called
 * concurrently with ongoing behavior execution. Use defensive copies or
 * atomic reads where necessary.
 *
 * @since 0.2.0
 * @see AgentState
 * @see PersistenceService
 * @see PersistenceStrategy
 * @see PersistenceConfig
 */
public interface Stateful {

	/**
     * Captures a consistent snapshot of the agent's current state for persistence.
     *
     * <p>This method is called by the framework according to the
     * {@link PersistenceStrategy} configured on the agent (e.g., on stop, periodically,
     * or on explicit request). The returned {@link AgentState} must contain all
     * data necessary to fully reconstruct the agent's operational state via
     * {@link #restoreState(AgentState)}.
     *
     * <p><strong>Implementation guidelines:</strong>
     * <ul>
     *   <li>Capture a point-in-time snapshot; avoid holding locks across the call</li>
     *   <li>Include all mutable fields that must survive a restart</li>
     *   <li>Increment or preserve {@link AgentState#version()} for optimistic locking</li>
     *   <li>Use {@link AgentState#builder(String)} to construct the result</li>
     * </ul>
     *
     * @return a non-null {@link AgentState} representing the current agent state
     * @see AgentState#builder(String)
     * @see #restoreState(AgentState)
     */
    AgentState captureState();

    /**
     * Restores the agent's internal state from a previously persisted snapshot.
     *
     * <p>This method is called by the framework during agent startup when a
     * saved state is found for this agent's ID. Implementations should
     * reinitialize all mutable fields from the provided {@link AgentState},
     * using {@link AgentState#getData(String, Class)} for type-safe retrieval.
     *
     * <p><strong>Implementation guidelines:</strong>
     * <ul>
     *   <li>Handle missing keys gracefully (use defaults when data is absent)</li>
     *   <li>Do not start behaviors or connect to external systems here;
     *       use the agent's {@code onStart()} lifecycle hook for that</li>
     *   <li>This method may be called before {@code start()}; avoid assumptions
     *       about service availability</li>
     * </ul>
     *
     * <p>Example:
     * <pre>{@code
     * @Override
     * public void restoreState(AgentState state) {
     *     Integer count = state.getData("ordersProcessed", Integer.class);
     *     this.ordersProcessed = count != null ? count : 0;
     * }
     * }</pre>
     *
     * @param state the previously persisted state to restore from, never null
     * @throws NullPointerException if {@code state} is null
     * @see AgentState#getData(String, Class)
     * @see #captureState()
     */
    void restoreState(AgentState state);

    /**
     * Returns the version of the agent's current persisted state.
     *
     * <p>Used by the framework for optimistic concurrency control: when saving
     * state, the version in the new {@link AgentState} should be greater than
     * the previously saved version to detect concurrent modifications.
     *
     * <p>The default implementation returns {@code 1L}. Override this method
     * if the agent tracks its own version counter.
     *
     * @return the current state version; {@code 1L} by default
     * @see AgentState#version()
     */
    default long getStateVersion() {
        return 1L;
    }
}
