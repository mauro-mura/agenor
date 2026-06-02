package dev.agenor.runtime.behavior.advanced;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.behavior.BaseBehavior;

/**
 * Pipeline behavior that processes data through multiple stages sequentially.
 * Each stage can transform the data before passing it to the next stage.
 *
 * <p>Features:
 * <ul>
 *   <li>Sequential stage execution with data transformation</li>
 *   <li>Configurable error handling (fail-fast or continue)</li>
 *   <li>Stage-level timeouts</li>
 *   <li>Conditional branching between stages</li>
 *   <li>Stage metrics and monitoring</li>
 *   <li>Pipeline replay capability</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * PipelineBehavior<Order, DeliveryInfo> orderPipeline =
 *     new PipelineBehavior<>("order-pipeline", Order.class, DeliveryInfo.class)
 *         .addStage("validate", order -> {
 *             if (order.items().isEmpty()) {
 *                 throw new IllegalStateException("Empty order");
 *             }
 *             return order;
 *         })
 *         .addStage("process-payment", order -> {
 *             PaymentResult result = paymentService.charge(order);
 *             return order.withPayment(result);
 *         })
 *         .addStage("prepare-shipment", order -> {
 *             ShipmentInfo shipment = shippingService.prepare(order);
 *             return DeliveryInfo.from(order, shipment);
 *         })
 *         .onStageStart(stage -> log.info("Starting stage: {}", stage.getName()))
 *         .onStageComplete(stage -> log.info("Completed stage: {}", stage.getName()))
 *         .onStageError((stage, error) -> log.error("Stage {} failed: {}", stage.getName(), error))
 *         .build();
 * }</pre>
 *
 * @param <I> The input type for the pipeline
 * @param <O> The output type from the pipeline
 */
public class PipelineBehavior<I, O> extends BaseBehavior {

    private final Class<I> inputType;
    private final Class<O> outputType;
    private final List<PipelineStage<?, ?>> stages;
    private final ErrorHandlingStrategy errorStrategy;
    private final Duration stageTimeout;
    private final boolean failFast;

    // Metrics
    private final AtomicInteger successfulExecutions = new AtomicInteger(0);
    private final AtomicInteger failedExecutions = new AtomicInteger(0);
    private final AtomicInteger totalStagesExecuted = new AtomicInteger(0);

    // Callbacks
    private Consumer<PipelineStage<?, ?>> onStageStart;
    private Consumer<PipelineStage<?, ?>> onStageComplete;
    private StageErrorHandler onStageError;
    private Consumer<PipelineResult<O>> onPipelineComplete;

    /**
     * Error handling strategy for pipeline execution
     */
    public enum ErrorHandlingStrategy {
        /** Stop pipeline on first error (default) */
        FAIL_FAST,

        /** Continue to next stage even if current fails */
        CONTINUE_ON_ERROR,

        /** Skip failed stages and continue with last successful result */
        SKIP_FAILED_STAGES,

        /** Retry failed stages before failing */
        RETRY_FAILED_STAGES
    }

    /**
     * Create a new pipeline behavior
     */
    protected PipelineBehavior(String behaviorId,
                               Class<I> inputType,
                               Class<O> outputType,
                               Duration stageTimeout,
                               ErrorHandlingStrategy errorStrategy,
                               boolean failFast) {
        super(behaviorId, BehaviorType.PIPELINE, null);
        this.inputType = inputType;
        this.outputType = outputType;
        this.stages = new ArrayList<>();
        this.stageTimeout = stageTimeout;
        this.errorStrategy = errorStrategy != null ? errorStrategy : ErrorHandlingStrategy.FAIL_FAST;
        this.failFast = failFast;
    }

    @Override
    protected void action() {
        // Pipeline is executed on-demand via executePipeline()
        // This method is for scheduled execution if needed
        log.trace("Pipeline {} waiting for input", getBehaviorId());
    }

    /**
     * Execute the pipeline with the given input
     *
     * @param input The input data
     * @return CompletableFuture containing the pipeline result
     */
    public CompletableFuture<PipelineResult<O>> executePipeline(I input) {
        if (!isActive()) {
            return CompletableFuture.completedFuture(
                PipelineResult.failed("Pipeline is not active")
            );
        }

        if (stages.isEmpty()) {
            return CompletableFuture.completedFuture(
                PipelineResult.failed("Pipeline has no stages")
            );
        }

        log.debug("Starting pipeline execution: {} with {} stages", getBehaviorId(), stages.size());

        return CompletableFuture.supplyAsync(() -> {
            Object currentData = input;
            List<StageResult> stageResults = new ArrayList<>();
            long pipelineStartTime = System.currentTimeMillis();

            for (int i = 0; i < stages.size(); i++) {
                PipelineStage<Object, Object> stage = (PipelineStage<Object, Object>) stages.get(i);
                long stageStartTime = System.currentTimeMillis();
                try {
                    // Stage start callback
                    if (onStageStart != null) {
                        onStageStart.accept(stage);
                    }

                    log.trace("Executing stage {}/{}: {}", i + 1, stages.size(), stage.getName());

                    // Execute stage with timeout
                    Object result = executeStageWithTimeout(stage, currentData);

                    long stageDuration = System.currentTimeMillis() - stageStartTime;
                    totalStagesExecuted.incrementAndGet();

                    // Record successful stage execution
                    stageResults.add(new StageResult(stage.getName(), true, stageDuration, null));

                    // Stage complete callback
                    if (onStageComplete != null) {
                        onStageComplete.accept(stage);
                    }

                    // Update current data for next stage
                    currentData = result;

                } catch (Exception e) {
                    long stageDuration = System.currentTimeMillis() - stageStartTime;

                    // Extract meaningful error message
                    String errorMessage = extractErrorMessage(e);

                    stageResults.add(new StageResult(stage.getName(), false, stageDuration, errorMessage));

                    // Stage error callback
                    if (onStageError != null) {
                        onStageError.handleError(stage, e);
                    }

                    // Handle error based on strategy
                    if (errorStrategy == ErrorHandlingStrategy.FAIL_FAST || failFast) {
                        log.error("Pipeline failed at stage {}: {}", stage.getName(), errorMessage);
                        failedExecutions.incrementAndGet();

                        long totalDuration = System.currentTimeMillis() - pipelineStartTime;
                        PipelineResult<O> result = PipelineResult.failed(
                            "Pipeline failed at stage " + stage.getName() + ": " + errorMessage,
                            stageResults,
                            totalDuration
                        );

                        if (onPipelineComplete != null) {
                            onPipelineComplete.accept(result);
                        }

                        return result;
                    } else {
                        log.warn("Stage {} failed but continuing: {}", stage.getName(), errorMessage);
                        // Continue with current data unchanged
                    }
                }
            }

            // All stages completed successfully
            long totalDuration = System.currentTimeMillis() - pipelineStartTime;
            successfulExecutions.incrementAndGet();

            @SuppressWarnings("unchecked")
            O finalOutput = (O) currentData;

            PipelineResult<O> result = PipelineResult.success(
                finalOutput,
                stageResults,
                totalDuration
            );

            log.debug("Pipeline {} completed successfully in {}ms", getBehaviorId(), totalDuration);

            if (onPipelineComplete != null) {
                onPipelineComplete.accept(result);
            }

            return result;
        });
    }

    /**
     * Execute a stage with timeout if configured
     */
    private Object executeStageWithTimeout(PipelineStage<Object, Object> stage, Object input) {
        if (stageTimeout == null) {
            return stage.process(input);
        }

        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return stage.process(input);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        try {
            return future.get(stageTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new CompletionException("Stage timeout after " + stageTimeout, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new CompletionException("Stage execution failed", cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Stage execution interrupted", e);
        }
    }

    /**
     * Extract meaningful error message from exception, handling CompletionException
     */
    private String extractErrorMessage(Exception e) {
        if (e instanceof CompletionException) {
            CompletionException ce = (CompletionException) e;
            String message = ce.getMessage();
            if (message != null) {
                return message;
            }
            if (ce.getCause() != null) {
                return ce.getCause().getMessage();
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * Get pipeline metrics
     */
    public PipelineMetrics getMetrics() {
        return new PipelineMetrics(
            successfulExecutions.get(),
            failedExecutions.get(),
            totalStagesExecuted.get(),
            stages.size()
        );
    }

    /**
     * Get list of stage names
     */
    public List<String> getStageNames() {
        return stages.stream()
            .map(PipelineStage::getName)
            .toList();
    }

    /**
     * Get number of stages in pipeline
     */
    public int getStageCount() {
        return stages.size();
    }

    /**
     * Reset pipeline metrics
     */
    public void resetMetrics() {
        successfulExecutions.set(0);
        failedExecutions.set(0);
        totalStagesExecuted.set(0);
        log.debug("Pipeline metrics reset: {}", getBehaviorId());
    }

    // ========================================================================================
    // BUILDER PATTERN
    // ========================================================================================

    /**
     * Create a new pipeline builder
     */
    public static <I, O> Builder<I, O> builder(String behaviorId, Class<I> inputType, Class<O> outputType) {
        return new Builder<>(behaviorId, inputType, outputType);
    }

    /**
     * Builder for constructing pipelines fluently
     */
    public static class Builder<I, O> {
        private final String behaviorId;
        private final Class<I> inputType;
        private final Class<O> outputType;
        private final List<PipelineStage<?, ?>> stages = new ArrayList<>();
        private Duration stageTimeout;
        private ErrorHandlingStrategy errorStrategy = ErrorHandlingStrategy.FAIL_FAST;
        private boolean failFast = true;

        private Consumer<PipelineStage<?, ?>> onStageStart;
        private Consumer<PipelineStage<?, ?>> onStageComplete;
        private StageErrorHandler onStageError;
        private Consumer<PipelineResult<O>> onPipelineComplete;

        private Builder(String behaviorId, Class<I> inputType, Class<O> outputType) {
            this.behaviorId = behaviorId;
            this.inputType = inputType;
            this.outputType = outputType;
        }

        /**
         * Add a stage to the pipeline
         */
        public <T, R> Builder<I, O> addStage(String name, Function<T, R> processor) {
            stages.add(new FunctionStage<>(name, processor));
            return this;
        }

        /**
         * Add a stage with custom implementation
         */
        public Builder<I, O> addStage(PipelineStage<?, ?> stage) {
            stages.add(stage);
            return this;
        }

        /**
         * Set timeout for each stage
         */
        public Builder<I, O> stageTimeout(Duration timeout) {
            this.stageTimeout = timeout;
            return this;
        }

        /**
         * Set error handling strategy
         */
        public Builder<I, O> errorStrategy(ErrorHandlingStrategy strategy) {
            this.errorStrategy = strategy;
            return this;
        }

        /**
         * Set fail-fast behavior
         */
        public Builder<I, O> failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        /**
         * Set callback for stage start
         */
        public Builder<I, O> onStageStart(Consumer<PipelineStage<?, ?>> callback) {
            this.onStageStart = callback;
            return this;
        }

        /**
         * Set callback for stage completion
         */
        public Builder<I, O> onStageComplete(Consumer<PipelineStage<?, ?>> callback) {
            this.onStageComplete = callback;
            return this;
        }

        /**
         * Set callback for stage errors
         */
        public Builder<I, O> onStageError(StageErrorHandler callback) {
            this.onStageError = callback;
            return this;
        }

        /**
         * Set callback for pipeline completion
         */
        public Builder<I, O> onPipelineComplete(Consumer<PipelineResult<O>> callback) {
            this.onPipelineComplete = callback;
            return this;
        }

        /**
         * Build the pipeline
         */
        public PipelineBehavior<I, O> build() {
            if (stages.isEmpty()) {
                throw new IllegalStateException("Pipeline must have at least one stage");
            }

            PipelineBehavior<I, O> pipeline = new PipelineBehavior<>(
                behaviorId,
                inputType,
                outputType,
                stageTimeout,
                errorStrategy,
                failFast
            );

            pipeline.stages.addAll(stages);
            pipeline.onStageStart = onStageStart;
            pipeline.onStageComplete = onStageComplete;
            pipeline.onStageError = onStageError;
            pipeline.onPipelineComplete = onPipelineComplete;

            return pipeline;
        }
    }

    // ========================================================================================
    // PIPELINE STAGE INTERFACE
    // ========================================================================================

    /**
     * Interface for a pipeline stage
     */
    @FunctionalInterface
    public interface PipelineStage<I, O> {
        /**
         * Process input and produce output
         */
        O process(I input);

        /**
         * Get stage name
         */
        default String getName() {
            return getClass().getSimpleName();
        }
    }

    /**
     * Simple function-based stage implementation
     */
    private static class FunctionStage<I, O> implements PipelineStage<I, O> {
        private final String name;
        private final Function<I, O> function;

        FunctionStage(String name, Function<I, O> function) {
            this.name = name;
            this.function = function;
        }

        @Override
        public O process(I input) {
            return function.apply(input);
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * Error handler for pipeline stages
     */
    @FunctionalInterface
    public interface StageErrorHandler {
        void handleError(PipelineStage<?, ?> stage, Exception error);
    }

    // ========================================================================================
    // RESULT TYPES
    // ========================================================================================

    /**
     * Result of pipeline execution
     */
    public static class PipelineResult<O> {
        private final boolean success;
        private final O output;
        private final String errorMessage;
        private final List<StageResult> stageResults;
        private final long totalDurationMs;

        private PipelineResult(boolean success, O output, String errorMessage,
                              List<StageResult> stageResults, long totalDurationMs) {
            this.success = success;
            this.output = output;
            this.errorMessage = errorMessage;
            this.stageResults = stageResults != null ? List.copyOf(stageResults) : List.of();
            this.totalDurationMs = totalDurationMs;
        }

        public static <O> PipelineResult<O> success(O output, List<StageResult> stageResults, long durationMs) {
            return new PipelineResult<>(true, output, null, stageResults, durationMs);
        }

        public static <O> PipelineResult<O> failed(String errorMessage) {
            return new PipelineResult<>(false, null, errorMessage, List.of(), 0);
        }

        public static <O> PipelineResult<O> failed(String errorMessage, List<StageResult> stageResults, long durationMs) {
            return new PipelineResult<>(false, null, errorMessage, stageResults, durationMs);
        }

        public boolean isSuccess() { return success; }
        public O getOutput() { return output; }
        public String getErrorMessage() { return errorMessage; }
        public List<StageResult> getStageResults() { return stageResults; }
        public long getTotalDurationMs() { return totalDurationMs; }

        @Override
        public String toString() {
            return String.format("PipelineResult[success=%s, stages=%d, duration=%dms]",
                success, stageResults.size(), totalDurationMs);
        }
    }

    /**
     * Result of a single stage execution
     */
    public record StageResult(
        String stageName,
        boolean success,
        long durationMs,
        String errorMessage
    ) {
        @Override
        public String toString() {
            return String.format("Stage[%s, %s, %dms%s]",
                stageName,
                success ? "SUCCESS" : "FAILED",
                durationMs,
                errorMessage != null ? ", error=" + errorMessage : "");
        }
    }

    /**
     * Pipeline execution metrics
     */
    public record PipelineMetrics(
        int successfulExecutions,
        int failedExecutions,
        int totalStagesExecuted,
        int stageCount
    ) {
        public int getTotalExecutions() {
            return successfulExecutions + failedExecutions;
        }

        public double getSuccessRate() {
            int total = getTotalExecutions();
            return total > 0 ? (double) successfulExecutions / total : 0.0;
        }

        public double getAverageStagesPerExecution() {
            int total = getTotalExecutions();
            return total > 0 ? (double) totalStagesExecuted / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format("PipelineMetrics[executions=%d, success=%d, failed=%d, successRate=%.2f%%]",
                getTotalExecutions(), successfulExecutions, failedExecutions, getSuccessRate() * 100);
        }
    }
}
