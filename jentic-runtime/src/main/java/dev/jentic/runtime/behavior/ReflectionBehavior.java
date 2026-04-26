package dev.jentic.runtime.behavior;

import dev.jentic.core.reflection.CritiqueResult;
import dev.jentic.core.reflection.ReflectionConfig;
import dev.jentic.core.reflection.ReflectionStrategy;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link OneShotBehavior} that wraps a generate/revise action with a
 * Generate → Critique → Revise loop driven by a {@link ReflectionStrategy}.
 *
 * <p>Execution steps:
 * <ol>
 *   <li>Call {@code action} to produce the initial output.</li>
 *   <li>Call {@link ReflectionStrategy#critique} on the output.</li>
 *   <li>If {@code shouldRevise && iterations < maxIterations} → call {@code revise}
 *       with the previous output and critique feedback, then repeat from step 2.</li>
 *   <li>Stop early when {@code score >= scoreThreshold}.</li>
 *   <li>When the loop ends, store the best output and invoke {@code onResult} if set.</li>
 * </ol>
 *
 * <p>The best output produced so far is always retained even when {@code maxIterations}
 * is reached without convergence.
 *
 * <p>Use the {@link Builder} to construct instances:
 * <pre>{@code
 * ReflectionBehavior behavior = ReflectionBehavior.builder("review-task")
 *     .task("Summarise the Q3 earnings report")
 *     .action(() -> agent.generate(task))
 *     .revise((prev, feedback) -> agent.generate(task + "\nFeedback: " + feedback))
 *     .strategy(new DefaultReflectionStrategy(llmProvider))
 *     .config(ReflectionConfig.defaults())
 *     .onResult(output -> log.info("Final output: {}", output))
 *     .build();
 *
 * agent.addBehavior(behavior);
 * }</pre>
 *
 * @see ReflectionStrategy
 * @see ReflectionConfig
 * 
 * @since 0.12.0
 */
public class ReflectionBehavior extends OneShotBehavior {

    private static final Logger log = LoggerFactory.getLogger(ReflectionBehavior.class);

    private final String task;
    private final Supplier<String> action;
    private final BiFunction<String, String, String> revise;
    private final ReflectionStrategy strategy;
    private final ReflectionConfig config;
    private final Consumer<String> onResult;
    private final JenticTelemetry telemetry;

    /** Stores the best output produced during the loop; readable after execution. */
    private final AtomicReference<String> result = new AtomicReference<>("");

    private ReflectionBehavior(Builder builder) {
        super(builder.behaviorId);
        this.task = builder.task;
        this.action = builder.action;
        this.revise = builder.revise;
        this.strategy = builder.strategy;
        this.config = builder.config;
        this.onResult = builder.onResult;
        this.telemetry = builder.telemetry != null ? builder.telemetry : JenticTelemetry.noop();
    }

    // -------------------------------------------------------------------------
    // Core loop
    // -------------------------------------------------------------------------

    @Override
    protected void action() {
        String currentOutput = action.get();
        String bestOutput = currentOutput;
        double bestScore = 0.0;

        for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
            log.debug("Reflection iteration {}/{} for task '{}'",
                    iteration + 1, config.maxIterations(), task);

            CritiqueResult critique = strategy.critique(currentOutput, task, config).join();

            boolean accepted = critique.score() >= config.scoreThreshold();

            var span = telemetry.spanBuilder("reflection.iteration")
                    .setAttribute("reflection.iteration", (long) (iteration + 1))
                    .setAttribute("reflection.score", String.valueOf(critique.score()))
                    .setAttribute("reflection.accepted", accepted)
                    .startSpan();

            try {
                if (critique.score() > bestScore) {
                    bestScore = critique.score();
                    bestOutput = currentOutput;
                }

                log.debug("Critique score={} shouldRevise={}", critique.score(), critique.shouldRevise());

                span.setStatus(SpanStatus.OK);

                // Early stop: a quality threshold reached
                if (accepted) {
                    log.debug("Early stop: score {} >= threshold {}", critique.score(), config.scoreThreshold());
                    break;
                }

                // No more iterations after the last round
                if (!critique.shouldRevise() || iteration == config.maxIterations() - 1) {
                    break;
                }

                currentOutput = revise.apply(currentOutput, critique.feedback());
            } catch (Exception e) {
                span.recordException(e).setStatus(SpanStatus.ERROR);
                throw e;
            } finally {
                span.end();
            }
        }

        result.set(bestOutput);

        if (onResult != null) {
            onResult.accept(bestOutput);
        }
    }

    // -------------------------------------------------------------------------
    // Result accessor
    // -------------------------------------------------------------------------

    /**
     * Returns the best output produced during the reflection loop.
     * Returns an empty string before the behavior has executed.
     *
     * @return best output (never {@code null})
     */
    public String getResult() {
        return result.get();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@link Builder} with the given behavior ID.
     *
     * @param behaviorId unique identifier for the behavior (non-null)
     * @return a new builder
     */
    public static Builder builder(String behaviorId) {
        return new Builder(behaviorId);
    }

    /**
     * Creates a new {@link Builder} with an auto-generated behavior ID.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder(null);
    }

    /** Fluent builder for {@link ReflectionBehavior}. */
    public static final class Builder {

        private final String behaviorId;
        private String task;
        private Supplier<String> action;
        private BiFunction<String, String, String> revise;
        private ReflectionStrategy strategy;
        private ReflectionConfig config = ReflectionConfig.defaults();
        private Consumer<String> onResult;
        private JenticTelemetry telemetry;

        private Builder(String behaviorId) {
            this.behaviorId = behaviorId;
        }

        /** Sets the task description used in critique prompts. */
        public Builder task(String task) {
            this.task = task;
            return this;
        }

        /** Sets the supplier that generates the initial output. */
        public Builder action(Supplier<String> action) {
            this.action = action;
            return this;
        }

        /**
         * Sets the revision function invoked when critique requests changes.
         *
         * @param revise {@code (previousOutput, feedback) → revisedOutput}
         */
        public Builder revise(BiFunction<String, String, String> revise) {
            this.revise = revise;
            return this;
        }

        /** Sets the {@link ReflectionStrategy} used to critique outputs. */
        public Builder strategy(ReflectionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /** Sets the {@link ReflectionConfig}; defaults to {@link ReflectionConfig#defaults()}. */
        public Builder config(ReflectionConfig config) {
            this.config = config;
            return this;
        }

        /** Optional callback invoked with the final best output after the loop completes. */
        public Builder onResult(Consumer<String> onResult) {
            this.onResult = onResult;
            return this;
        }

        /**
         * Sets the telemetry instance used to emit {@code reflection.iteration} spans.
         *
         * @param telemetry the telemetry instance; {@code null} uses noop
         * @return {@code this}
         * @since 0.19.0
         */
        public Builder telemetry(JenticTelemetry telemetry) {
            this.telemetry = telemetry;
            return this;
        }

        /** Builds the {@link ReflectionBehavior}. */
        public ReflectionBehavior build() {
            Objects.requireNonNull(task, "task must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(revise, "revise must not be null");
            Objects.requireNonNull(strategy, "strategy must not be null");
            Objects.requireNonNull(config, "config must not be null");
            return new ReflectionBehavior(this);
        }
    }
}