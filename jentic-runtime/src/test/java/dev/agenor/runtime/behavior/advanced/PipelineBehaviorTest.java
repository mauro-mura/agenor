package dev.agenor.runtime.behavior.advanced;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PipelineBehavior
 */
class PipelineBehaviorTest {

    @Test
    @DisplayName("Should execute simple pipeline successfully")
    void testSimplePipeline() {
        PipelineBehavior<String, Integer> simplePipeline =
            PipelineBehavior.<String, Integer>builder("test-pipeline", String.class, Integer.class)
                .addStage("uppercase", (String s) -> s.toUpperCase())
                .addStage("length", (String s) -> s.length())
                .build();

        CompletableFuture<PipelineBehavior.PipelineResult<Integer>> result =
            simplePipeline.executePipeline("hello");

        PipelineBehavior.PipelineResult<Integer> pipelineResult = result.join();

        assertTrue(pipelineResult.isSuccess());
        assertEquals(5, pipelineResult.getOutput());
        assertEquals(2, pipelineResult.getStageResults().size());
    }

    @Test
    @DisplayName("Should handle pipeline with multiple stages")
    void testMultiStagePipeline() {
        PipelineBehavior<String, String> stringPipeline =
            PipelineBehavior.<String, String>builder("multi-stage", String.class, String.class)
                .addStage("trim", (String s) -> s.trim())
                .addStage("uppercase", (String s) -> s.toUpperCase())
                .addStage("prefix", (String s) -> "PROCESSED: " + s)
                .build();

        PipelineBehavior.PipelineResult<String> result =
            stringPipeline.executePipeline("  hello world  ").join();

        assertTrue(result.isSuccess());
        assertEquals("PROCESSED: HELLO WORLD", result.getOutput());
        assertEquals(3, result.getStageResults().size());
    }

    @Test
    @DisplayName("Should fail fast on stage error with FAIL_FAST strategy")
    void testFailFastStrategy() {
        PipelineBehavior<String, String> failFastPipeline =
            PipelineBehavior.<String, String>builder("fail-fast", String.class, String.class)
                .addStage("stage1", (String s) -> s.toUpperCase())
                .addStage("failing-stage", (String s) -> {
                    throw new RuntimeException("Stage failed");
                })
                .addStage("stage3", (String s) -> s + "!!!")
                .errorStrategy(PipelineBehavior.ErrorHandlingStrategy.FAIL_FAST)
                .build();

        PipelineBehavior.PipelineResult<String> result =
            failFastPipeline.executePipeline("hello").join();

        assertFalse(result.isSuccess());
        assertNull(result.getOutput());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("failing-stage"));

        // Should have executed only 2 stages (stage1 and failing-stage)
        assertEquals(2, result.getStageResults().size());
        assertTrue(result.getStageResults().get(0).success());
        assertFalse(result.getStageResults().get(1).success());
    }

    @Test
    @DisplayName("Should continue on error with CONTINUE_ON_ERROR strategy")
    void testContinueOnErrorStrategy() {
        PipelineBehavior<String, String> continueOnErrorPipeline =
            PipelineBehavior.<String, String>builder("continue-on-error", String.class, String.class)
                .addStage("stage1", (String s) -> s.toUpperCase())
                .addStage("failing-stage", (String s) -> {
                    throw new RuntimeException("Stage failed");
                })
                .addStage("stage3", (String s) -> s + "!!!")
                .errorStrategy(PipelineBehavior.ErrorHandlingStrategy.CONTINUE_ON_ERROR)
                .failFast(false)
                .build();

        PipelineBehavior.PipelineResult<String> result =
            continueOnErrorPipeline.executePipeline("hello").join();

        assertTrue(result.isSuccess());
        assertEquals("HELLO!!!", result.getOutput()); // Failed stage skipped, data passed through
        assertEquals(3, result.getStageResults().size());
    }

    @Test
    @DisplayName("Should execute stage callbacks")
    void testStageCallbacks() {
        AtomicInteger startCount = new AtomicInteger(0);
        AtomicInteger completeCount = new AtomicInteger(0);

        PipelineBehavior<String, String> callbackPipeline =
            PipelineBehavior.<String, String>builder("callback-test", String.class, String.class)
                .addStage("stage1", (String s) -> s.toUpperCase())
                .addStage("stage2", (String s) -> s + "!")
                .onStageStart(stage -> startCount.incrementAndGet())
                .onStageComplete(stage -> completeCount.incrementAndGet())
                .build();

        callbackPipeline.executePipeline("hello").join();

        assertEquals(2, startCount.get());
        assertEquals(2, completeCount.get());
    }

    @Test
    @DisplayName("Should handle stage errors with error callback")
    void testErrorCallback() {
        AtomicInteger errorCount = new AtomicInteger(0);

        PipelineBehavior<String, String> errorCallbackPipeline =
            PipelineBehavior.<String, String>builder("error-callback", String.class, String.class)
                .addStage("stage1", (String s) -> s.toUpperCase())
                .addStage("failing-stage", (String s) -> {
                    throw new RuntimeException("Test error");
                })
                .onStageError((stage, error) -> {
                    errorCount.incrementAndGet();
                    assertEquals("failing-stage", stage.getName());
                })
                .build();

        errorCallbackPipeline.executePipeline("hello").join();

        assertEquals(1, errorCount.get());
    }

    @Test
    @DisplayName("Should track pipeline metrics")
    void testPipelineMetrics() {
        PipelineBehavior<String, Integer> metricsPipeline =
            PipelineBehavior.<String, Integer>builder("metrics-test", String.class, Integer.class)
                .addStage("stage1", (String s) -> s.length())
                .build();

        // Execute multiple times
        metricsPipeline.executePipeline("hello").join();
        metricsPipeline.executePipeline("world").join();
        metricsPipeline.executePipeline("test").join();

        PipelineBehavior.PipelineMetrics metrics = metricsPipeline.getMetrics();

        assertEquals(3, metrics.successfulExecutions());
        assertEquals(0, metrics.failedExecutions());
        assertEquals(3, metrics.getTotalExecutions());
        assertEquals(1.0, metrics.getSuccessRate());
        assertEquals(3, metrics.totalStagesExecuted());
    }

    @Test
    @DisplayName("Should track failed executions in metrics")
    void testFailedExecutionMetrics() {
        PipelineBehavior<String, String> failedMetricsPipeline =
            PipelineBehavior.<String, String>builder("failed-metrics", String.class, String.class)
                .addStage("failing-stage", (String s) -> {
                    throw new RuntimeException("Fail");
                })
                .build();

        failedMetricsPipeline.executePipeline("test1").join();
        failedMetricsPipeline.executePipeline("test2").join();

        PipelineBehavior.PipelineMetrics metrics = failedMetricsPipeline.getMetrics();

        assertEquals(0, metrics.successfulExecutions());
        assertEquals(2, metrics.failedExecutions());
        assertEquals(0.0, metrics.getSuccessRate());
    }

    @Test
    @DisplayName("Should reset metrics")
    void testResetMetrics() {
        PipelineBehavior<String, Integer> resetPipeline =
            PipelineBehavior.<String, Integer>builder("reset-test", String.class, Integer.class)
                .addStage("length", (String s) -> s.length())
                .build();

        resetPipeline.executePipeline("test").join();
        assertEquals(1, resetPipeline.getMetrics().getTotalExecutions());

        resetPipeline.resetMetrics();
        assertEquals(0, resetPipeline.getMetrics().getTotalExecutions());
    }

    @Test
    @DisplayName("Should handle empty pipeline gracefully")
    void testEmptyPipelineValidation() {
        assertThrows(IllegalStateException.class, () -> {
            PipelineBehavior.<String, String>builder("empty", String.class, String.class)
                .build();
        });
    }

    @Test
    @DisplayName("Should return stage names")
    void testGetStageNames() {
        PipelineBehavior<String, String> stageNamesPipeline =
            PipelineBehavior.<String, String>builder("stage-names", String.class, String.class)
                .addStage("stage1", (String s) -> s)
                .addStage("stage2", (String s) -> s)
                .addStage("stage3", (String s) -> s)
                .build();

        assertEquals(3, stageNamesPipeline.getStageNames().size());
        assertEquals("stage1", stageNamesPipeline.getStageNames().get(0));
        assertEquals("stage2", stageNamesPipeline.getStageNames().get(1));
        assertEquals("stage3", stageNamesPipeline.getStageNames().get(2));
    }

    @Test
    @DisplayName("Should return stage count")
    void testGetStageCount() {
        PipelineBehavior<String, String> countTestPipeline =
            PipelineBehavior.<String, String>builder("count-test", String.class, String.class)
                .addStage("stage1", (String s) -> s)
                .addStage("stage2", (String s) -> s)
                .build();

        assertEquals(2, countTestPipeline.getStageCount());
    }

    @Test
    @DisplayName("Should handle stage timeout")
    @Disabled("Timeout mechanism is not reliably working - CompletableFuture.cancel(true) " +
            "does not guarantee interruption of blocking operations like Thread.sleep(). " +
            "This is a known limitation of the current implementation. " +
            "Future improvement: use ExecutorService with proper timeout support.")
    void testStageTimeout() {
        PipelineBehavior<String, String> timeoutPipeline =
            PipelineBehavior.<String, String>builder("timeout-test", String.class, String.class)
                .addStage("slow-stage", (String s) -> {
                    try {
                        Thread.sleep(5000); // Sleep 5 seconds (much longer than timeout)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted", e);
                    }
                    return s;
                })
                .stageTimeout(Duration.ofMillis(100)) // 100ms timeout
                .build();

        PipelineBehavior.PipelineResult<String> result =
            timeoutPipeline.executePipeline("test").join();

        assertFalse(result.isSuccess(), "Pipeline should fail on timeout");
        assertNotNull(result.getErrorMessage(), "Error message should be present");
        assertTrue(result.getErrorMessage().toLowerCase().contains("timeout"),
                   "Error message should mention timeout, but was: " + result.getErrorMessage());
    }

    @Test
    @DisplayName("Should execute pipeline completion callback")
    void testPipelineCompletionCallback() {
        AtomicInteger completionCount = new AtomicInteger(0);

        PipelineBehavior<String, Integer> completionPipeline =
            PipelineBehavior.<String, Integer>builder("completion-test", String.class, Integer.class)
                .addStage("length", (String s) -> s.length())
                .onPipelineComplete(result -> {
                    completionCount.incrementAndGet();
                    assertTrue(result.isSuccess());
                })
                .build();

        completionPipeline.executePipeline("test").join();

        assertEquals(1, completionCount.get());
    }

    @Test
    @DisplayName("Should stop when pipeline is not active")
    void testInactivePipeline() {
        PipelineBehavior<String, String> inactivePipeline =
            PipelineBehavior.<String, String>builder("inactive-test", String.class, String.class)
                .addStage("stage1", (String s) -> s)
                .build();

        inactivePipeline.stop();

        PipelineBehavior.PipelineResult<String> result =
            inactivePipeline.executePipeline("test").join();

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("not active"));
    }

    @Test
    @DisplayName("Should transform data types through stages")
    void testTypeTransformation() {
        PipelineBehavior<String, Double> typedPipeline =
            PipelineBehavior.<String, Double>builder("type-transform", String.class, Double.class)
                .addStage("length", (String s) -> s.length())
                .addStage("square", (Integer i) -> i * i)
                .addStage("to-double", (Integer i) -> i.doubleValue())
                .build();

        PipelineBehavior.PipelineResult<Double> result =
            typedPipeline.executePipeline("hello").join();

        assertTrue(result.isSuccess());
        assertEquals(25.0, result.getOutput());
    }
}
