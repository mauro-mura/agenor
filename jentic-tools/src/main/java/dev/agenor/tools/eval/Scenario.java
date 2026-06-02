package dev.agenor.tools.eval;

import dev.agenor.runtime.JenticRuntime;

import java.time.Duration;
import java.util.List;

/**
 * Defines an evaluation scenario for testing agent behavior.
 *
 * <p>A scenario encapsulates the complete lifecycle of an evaluation:
 * setup, execution, verification, and teardown. Scenarios are designed
 * to be reusable and composable.
 *
 * <p>Example usage:
 * <pre>{@code
 * Scenario scenario = Scenario.builder("order-processing")
 *     .description("Tests order processing workflow")
 *     .timeout(Duration.ofSeconds(30))
 *     .setup(runtime -> runtime.registerAgent(new OrderAgent()))
 *     .execute(runtime -> runtime.sendMessage(...))
 *     .verify(ctx -> ctx.assertMessageReceived("orders.completed"))
 *     .build();
 * }</pre>
 *
 * @since 0.5.0
 * @see ScenarioRunner
 * @see EvaluationResult
 */
public interface Scenario {

    /**
     * Unique identifier for this scenario.
     *
     * @return the scenario ID, never null
     */
    String getId();

    /**
     * Human-readable description of what this scenario tests.
     *
     * @return the description, may be null
     */
    String getDescription();

    /**
     * Maximum time allowed for scenario execution.
     *
     * @return the timeout duration, never null
     */
    Duration getTimeout();

    /**
     * Setup phase - prepare the runtime environment.
     *
     * <p>Called before execution. Use this to register agents,
     * configure services, or prepare test data.
     *
     * @param runtime the Jentic runtime
     */
    void setup(JenticRuntime runtime);

    /**
     * Execution phase - trigger agent behavior.
     *
     * <p>Called after setup. Use this to send messages, start agents,
     * or trigger the behavior being tested.
     *
     * @param runtime the Jentic runtime
     */
    void execute(JenticRuntime runtime);

    /**
     * Verification phase - check outcomes.
     *
     * <p>Called after execution completes. Return a list of assertion
     * results indicating which expectations were met.
     *
     * @param context the evaluation context with collected metrics and messages
     * @return list of assertion results
     */
    List<AssertionResult> verify(EvaluationContext context);

    /**
     * Teardown phase - cleanup resources.
     *
     * <p>Called after verification, regardless of success or failure.
     * Use this to stop agents, release resources, or reset state.
     *
     * @param runtime the Jentic runtime
     */
    default void teardown(JenticRuntime runtime) {
        // Default: no cleanup needed
    }

    /**
     * Creates a new scenario builder.
     *
     * @param id unique scenario identifier
     * @return a new builder instance
     */
    static ScenarioBuilder builder(String id) {
        return new ScenarioBuilder(id);
    }
}
