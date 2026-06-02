package dev.agenor.runtime.behavior;

import dev.agenor.core.reflection.CritiqueResult;
import dev.agenor.core.reflection.ReflectionConfig;
import dev.agenor.core.reflection.ReflectionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReflectionBehaviorTest {

    private static final String TASK = "Explain Java virtual threads";

    // -------------------------------------------------------------------------
    // 1 iteration — good score on the first attempt
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("good score on first attempt → single iteration, no revision")
    void goodScoreOnFirstAttempt_singleIteration() {
        AtomicInteger reviseCount = new AtomicInteger(0);

        ReflectionBehavior behavior = ReflectionBehavior.builder("test-good")
                .task(TASK)
                .action(() -> "Great explanation")
                .revise((prev, feedback) -> { reviseCount.incrementAndGet(); return "revised"; })
                .strategy(fixedStrategy(CritiqueResult.accepted(0.95)))
                .config(ReflectionConfig.defaults())
                .build();

        behavior.execute().join();

        assertThat(behavior.getResult()).isEqualTo("Great explanation");
        assertThat(reviseCount.get()).isZero();
    }

    // -------------------------------------------------------------------------
    // 2 iterations — poor then good
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("poor score then good → 2 iterations, result is revised output")
    void poorThenGood_twoIterations() {
        List<CritiqueResult> critiques = List.of(
                CritiqueResult.revise("Add more examples", 0.5),
                CritiqueResult.accepted(0.9)
        );
        AtomicInteger callIdx = new AtomicInteger(0);
        AtomicInteger reviseCount = new AtomicInteger(0);

        ReflectionBehavior behavior = ReflectionBehavior.builder("test-two-iter")
                .task(TASK)
                .action(() -> "Initial output")
                .revise((prev, feedback) -> {
                    reviseCount.incrementAndGet();
                    return "Revised with: " + feedback;
                })
                .strategy((output, task, config) ->
                        CompletableFuture.completedFuture(critiques.get(callIdx.getAndIncrement())))
                .config(ReflectionConfig.defaults())
                .build();

        behavior.execute().join();

        assertThat(reviseCount.get()).isEqualTo(1);
        assertThat(behavior.getResult()).startsWith("Revised with:");
    }

    // -------------------------------------------------------------------------
    // Early stop by scoreThreshold
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("score >= scoreThreshold → early stop before maxIterations")
    void earlyStop_scoreThresholdReached() {
        AtomicInteger critiqueCount = new AtomicInteger(0);

        // Config: maxIterations=3, threshold=0.8 — first critique returns 0.9 → stops at 1
        ReflectionConfig config = new ReflectionConfig(3, 0.8, null);

        ReflectionBehavior behavior = ReflectionBehavior.builder("test-early-stop")
                .task(TASK)
                .action(() -> "output")
                .revise((prev, feedback) -> "revised")
                .strategy((output, task, cfg) -> {
                    critiqueCount.incrementAndGet();
                    return CompletableFuture.completedFuture(CritiqueResult.accepted(0.9));
                })
                .config(config)
                .build();

        behavior.execute().join();

        assertThat(critiqueCount.get()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // maxIterations reached without convergence → returns best-so-far
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("maxIterations reached without convergence → returns best output")
    void maxIterationsReached_returnsBestSoFar() {
        // Scores: 0.4, 0.6, 0.3 — best is iteration 2 (score 0.6)
        List<Double> scores = List.of(0.4, 0.6, 0.3);
        AtomicInteger idx = new AtomicInteger(0);

        List<String> outputs = new ArrayList<>();

        ReflectionBehavior behavior = ReflectionBehavior.builder("test-max-iter")
                .task(TASK)
                .action(() -> "output-0")
                .revise((prev, feedback) -> "output-" + (idx.get()))
                .strategy((output, task, config) -> {
                    int i = idx.getAndIncrement();
                    double score = scores.get(Math.min(i, scores.size() - 1));
                    outputs.add(output);
                    return CompletableFuture.completedFuture(
                            CritiqueResult.revise("improve it", score));
                })
                .config(new ReflectionConfig(3, 0.8, null))
                .build();

        behavior.execute().join();

        // The result must be the output that scored 0.6 (second critique)
        assertThat(behavior.getResult()).isNotNull();
        assertThat(behavior.getResult()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // onResult callback
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("onResult callback is invoked with the final output")
    void onResultCallback_invoked() {
        List<String> captured = new ArrayList<>();

        ReflectionBehavior behavior = ReflectionBehavior.builder("test-callback")
                .task(TASK)
                .action(() -> "final answer")
                .revise((prev, feedback) -> "revised")
                .strategy(fixedStrategy(CritiqueResult.accepted(0.95)))
                .onResult(captured::add)
                .build();

        behavior.execute().join();

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).isEqualTo("final answer");
    }

    // -------------------------------------------------------------------------
    // getResult before execution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getResult returns empty string before execution")
    void getResult_beforeExecution_isEmpty() {
        ReflectionBehavior behavior = ReflectionBehavior.builder("test-empty")
                .task(TASK)
                .action(() -> "output")
                .revise((prev, fb) -> "revised")
                .strategy(fixedStrategy(CritiqueResult.accepted(0.9)))
                .build();

        assertThat(behavior.getResult()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("builder without task → NullPointerException")
    void builder_missingTask_throwsNpe() {
        assertThatThrownBy(() ->
                ReflectionBehavior.builder("b")
                        .action(() -> "out")
                        .revise((p, f) -> "rev")
                        .strategy(fixedStrategy(CritiqueResult.accepted(1.0)))
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("task");
    }

    @Test
    @DisplayName("builder without action → NullPointerException")
    void builder_missingAction_throwsNpe() {
        assertThatThrownBy(() ->
                ReflectionBehavior.builder("b")
                        .task(TASK)
                        .revise((p, f) -> "rev")
                        .strategy(fixedStrategy(CritiqueResult.accepted(1.0)))
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
    }

    @Test
    @DisplayName("builder without revise → NullPointerException")
    void builder_missingRevise_throwsNpe() {
        assertThatThrownBy(() ->
                ReflectionBehavior.builder("b")
                        .task(TASK)
                        .action(() -> "out")
                        .strategy(fixedStrategy(CritiqueResult.accepted(1.0)))
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("revise");
    }

    @Test
    @DisplayName("builder without strategy → NullPointerException")
    void builder_missingStrategy_throwsNpe() {
        assertThatThrownBy(() ->
                ReflectionBehavior.builder("b")
                        .task(TASK)
                        .action(() -> "out")
                        .revise((p, f) -> "rev")
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("strategy");
    }

    // -------------------------------------------------------------------------
    // Behavior type
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ReflectionBehavior is a ONE_SHOT behavior")
    void behaviorType_isOneShot() {
        ReflectionBehavior behavior = ReflectionBehavior.builder("b")
                .task(TASK)
                .action(() -> "out")
                .revise((p, f) -> "rev")
                .strategy(fixedStrategy(CritiqueResult.accepted(1.0)))
                .build();

        assertThat(behavior.getType().name()).isEqualTo("ONE_SHOT");
    }

    @Test
    @DisplayName("behavior is inactive after execution")
    void behavior_inactiveAfterExecution() {
        ReflectionBehavior behavior = ReflectionBehavior.builder("b")
                .task(TASK)
                .action(() -> "out")
                .revise((p, f) -> "rev")
                .strategy(fixedStrategy(CritiqueResult.accepted(1.0)))
                .build();

        behavior.execute().join();

        assertThat(behavior.isActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static ReflectionStrategy fixedStrategy(CritiqueResult fixed) {
        return (output, task, config) -> CompletableFuture.completedFuture(fixed);
    }
}
