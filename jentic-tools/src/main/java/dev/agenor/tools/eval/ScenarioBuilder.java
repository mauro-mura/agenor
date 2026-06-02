package dev.agenor.tools.eval;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.agenor.runtime.JenticRuntime;

/**
 * Fluent builder for creating evaluation scenarios.
 *
 * <p>Example usage:
 * <pre>{@code
 * Scenario scenario = Scenario.builder("my-scenario")
 *     .description("Tests agent communication")
 *     .timeout(Duration.ofSeconds(10))
 *     .setup(runtime -> {
 *         runtime.registerAgent(new MyAgent());
 *     })
 *     .execute(runtime -> {
 *         runtime.getMessageService().send(testMessage);
 *     })
 *     .verify(ctx -> List.of(
 *         ctx.assertAgentRunning("my-agent"),
 *         ctx.assertMessageReceived("response.topic")
 *     ))
 *     .build();
 * }</pre>
 *
 * @since 0.5.0
 */
public class ScenarioBuilder {

    private final String id;
    private String description;
    private Duration timeout = Duration.ofSeconds(30);
    private Consumer<JenticRuntime> setupAction = runtime -> {};
    private Consumer<JenticRuntime> executeAction = runtime -> {};
    private Function<EvaluationContext, List<AssertionResult>> verifyAction = ctx -> List.of();
    private Consumer<JenticRuntime> teardownAction = runtime -> {};

    /**
     * Creates a new builder with the given scenario ID.
     *
     * @param id unique scenario identifier
     */
    public ScenarioBuilder(String id) {
        this.id = Objects.requireNonNull(id, "Scenario ID cannot be null");
    }

    /**
     * Sets the scenario description.
     *
     * @param description human-readable description
     * @return this builder
     */
    public ScenarioBuilder description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the scenario timeout.
     *
     * @param timeout maximum execution time
     * @return this builder
     */
    public ScenarioBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "Timeout cannot be null");
        return this;
    }

    /**
     * Sets the setup action.
     *
     * @param action setup logic
     * @return this builder
     */
    public ScenarioBuilder setup(Consumer<JenticRuntime> action) {
        this.setupAction = Objects.requireNonNull(action, "Setup action cannot be null");
        return this;
    }

    /**
     * Sets the execution action.
     *
     * @param action execution logic
     * @return this builder
     */
    public ScenarioBuilder execute(Consumer<JenticRuntime> action) {
        this.executeAction = Objects.requireNonNull(action, "Execute action cannot be null");
        return this;
    }

    /**
     * Sets the verification action.
     *
     * @param action verification logic returning assertion results
     * @return this builder
     */
    public ScenarioBuilder verify(Function<EvaluationContext, List<AssertionResult>> action) {
        this.verifyAction = Objects.requireNonNull(action, "Verify action cannot be null");
        return this;
    }

    /**
     * Sets the teardown action.
     *
     * @param action teardown logic
     * @return this builder
     */
    public ScenarioBuilder teardown(Consumer<JenticRuntime> action) {
        this.teardownAction = Objects.requireNonNull(action, "Teardown action cannot be null");
        return this;
    }

    /**
     * Builds the scenario.
     *
     * @return the constructed scenario
     */
    public Scenario build() {
        return new DefaultScenario(
            id, description, timeout,
            setupAction, executeAction, verifyAction, teardownAction
        );
    }

    /**
     * Default scenario implementation.
     */
    private record DefaultScenario(
        String id,
        String description,
        Duration timeout,
        Consumer<JenticRuntime> setupAction,
        Consumer<JenticRuntime> executeAction,
        Function<EvaluationContext, List<AssertionResult>> verifyAction,
        Consumer<JenticRuntime> teardownAction
    ) implements Scenario {

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Duration getTimeout() {
            return timeout;
        }

        @Override
        public void setup(JenticRuntime runtime) {
            setupAction.accept(runtime);
        }

        @Override
        public void execute(JenticRuntime runtime) {
            executeAction.accept(runtime);
        }

        @Override
        public List<AssertionResult> verify(EvaluationContext context) {
            return verifyAction.apply(context);
        }

        @Override
        public void teardown(JenticRuntime runtime) {
            teardownAction.accept(runtime);
        }
    }
}
