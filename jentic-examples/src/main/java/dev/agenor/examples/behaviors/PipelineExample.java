package dev.agenor.examples.behaviors;

import dev.agenor.runtime.behavior.advanced.PipelineBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Comprehensive example demonstrating PipelineBehavior usage.
 *
 * This example shows an e-commerce order processing pipeline with:
 * - Sequential data transformation
 * - Error handling and validation
 * - Stage metrics and monitoring
 * - Type-safe transformations
 */
public class PipelineExample {

    private static final Logger log = LoggerFactory.getLogger(PipelineExample.class);

    public static void main(String[] args) {
        log.info("=".repeat(80));
        log.info("PipelineBehavior Example - E-Commerce Order Processing");
        log.info("=".repeat(80));

        // Example 1: Simple String Processing Pipeline
        example1_SimpleStringPipeline();

        // Example 2: Order Processing Pipeline
        example2_OrderProcessingPipeline();

        // Example 3: Error Handling
        example3_ErrorHandling();

        // Example 4: Pipeline Metrics
        example4_PipelineMetrics();

        log.info("\n" + "=".repeat(80));
        log.info("All examples completed successfully!");
        log.info("=".repeat(80));
    }

    /**
     * Example 1: Simple string processing pipeline
     */
    private static void example1_SimpleStringPipeline() {
        log.info("\n" + "-".repeat(80));
        log.info("Example 1: Simple String Processing Pipeline");
        log.info("-".repeat(80));

        PipelineBehavior<String, String> pipeline =
            PipelineBehavior.<String, String>builder("string-pipeline", String.class, String.class)
                .addStage("trim", (String s) -> {
                    log.info("  Stage 1: Trimming whitespace");
                    return s.trim();
                })
                .addStage("uppercase", (String s) -> {
                    log.info("  Stage 2: Converting to uppercase");
                    return s.toUpperCase();
                })
                .addStage("prefix", (String s) -> {
                    log.info("  Stage 3: Adding prefix");
                    return "PROCESSED: " + s;
                })
                .onStageStart(stage -> log.debug("Starting stage: {}", stage.getName()))
                .onStageComplete(stage -> log.debug("Completed stage: {}", stage.getName()))
                .build();

        String input = "  hello world  ";
        log.info("Input: '{}'", input);

        PipelineBehavior.PipelineResult<String> result = pipeline.executePipeline(input).join();

        if (result.isSuccess()) {
            log.info("✅ Pipeline completed successfully");
            log.info("Output: '{}'", result.getOutput());
            log.info("Duration: {}ms", result.getTotalDurationMs());
        }
    }

    /**
     * Example 2: Order processing pipeline with type transformations
     */
    private static void example2_OrderProcessingPipeline() {
        log.info("\n" + "-".repeat(80));
        log.info("Example 2: Order Processing Pipeline");
        log.info("-".repeat(80));

        PipelineBehavior<Order, ProcessedOrder> pipeline =
            PipelineBehavior.<Order, ProcessedOrder>builder("order-pipeline", Order.class, ProcessedOrder.class)
                // Stage 1: Validate order
                .addStage("validate", (Order order) -> {
                    log.info("  Stage 1: Validating order {}", order.orderId());
                    if (order.items().isEmpty()) {
                        throw new IllegalStateException("Order has no items");
                    }
                    if (order.totalAmount().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalStateException("Invalid order amount");
                    }
                    log.info("    ✓ Order validated");
                    return order;
                })

                // Stage 2: Apply discounts
                .addStage("apply-discount", (Order order) -> {
                    log.info("  Stage 2: Applying discounts");
                    BigDecimal discount = calculateDiscount(order);
                    BigDecimal newTotal = order.totalAmount().subtract(discount);
                    Order discountedOrder = new Order(
                        order.orderId(),
                        order.customerId(),
                        order.items(),
                        newTotal,
                        order.createdAt()
                    );
                    log.info("    ✓ Discount applied: ${}", discount);
                    return discountedOrder;
                })

                // Stage 3: Calculate tax
                .addStage("calculate-tax", (Order order) -> {
                    log.info("  Stage 3: Calculating tax");
                    BigDecimal tax = order.totalAmount().multiply(new BigDecimal("0.10"));
                    log.info("    ✓ Tax calculated: ${}", tax);
                    return new OrderWithTax(order, tax);
                })

                // Stage 4: Finalize processing
                .addStage("finalize", (OrderWithTax orderWithTax) -> {
                    log.info("  Stage 4: Finalizing order");
                    ProcessedOrder processed = new ProcessedOrder(
                        orderWithTax.order().orderId(),
                        orderWithTax.order().totalAmount(),
                        orderWithTax.tax(),
                        orderWithTax.order().totalAmount().add(orderWithTax.tax()),
                        "CONFIRMED",
                        LocalDateTime.now()
                    );
                    log.info("    ✓ Order finalized: total with tax = ${}", processed.totalWithTax());
                    return processed;
                })

                .stageTimeout(Duration.ofSeconds(5))
                .onPipelineComplete(result -> {
                    if (result.isSuccess()) {
                        log.info("🎉 Order pipeline completed in {}ms", result.getTotalDurationMs());
                    }
                })
                .build();

        // Create sample order
        Order order = new Order(
            "ORD-12345",
            "CUST-001",
            List.of(
                new OrderItem("PROD-1", "Laptop", 1, new BigDecimal("999.99")),
                new OrderItem("PROD-2", "Mouse", 2, new BigDecimal("29.99"))
            ),
            new BigDecimal("1059.97"),
            LocalDateTime.now()
        );

        log.info("Processing order: {}", order.orderId());
        log.info("  Items: {}", order.items().size());
        log.info("  Total: ${}", order.totalAmount());

        PipelineBehavior.PipelineResult<ProcessedOrder> result = pipeline.executePipeline(order).join();

        if (result.isSuccess()) {
            ProcessedOrder processed = result.getOutput();
            log.info("\n✅ Order processed successfully:");
            log.info("  Order ID: {}", processed.orderId());
            log.info("  Subtotal: ${}", processed.subtotal());
            log.info("  Tax: ${}", processed.tax());
            log.info("  Total: ${}", processed.totalWithTax());
            log.info("  Status: {}", processed.status());

            log.info("\nStage breakdown:");
            result.getStageResults().forEach(stageResult ->
                log.info("  - {}: {} ({}ms)",
                    stageResult.stageName(),
                    stageResult.success() ? "✓" : "✗",
                    stageResult.durationMs())
            );
        }
    }

    /**
     * Example 3: Error handling strategies
     */
    private static void example3_ErrorHandling() {
        log.info("\n" + "-".repeat(80));
        log.info("Example 3: Error Handling");
        log.info("-".repeat(80));

        // Scenario 1: FAIL_FAST (default)
        log.info("\nScenario 1: FAIL_FAST strategy");
        PipelineBehavior<String, String> failFastPipeline =
            PipelineBehavior.<String, String>builder("fail-fast", String.class, String.class)
                .addStage("stage1", (String s) -> s.toUpperCase())
                .addStage("failing-stage", (String s) -> {
                    throw new RuntimeException("Simulated error");
                })
                .addStage("stage3", (String s) -> s + "!!!")
                .errorStrategy(PipelineBehavior.ErrorHandlingStrategy.FAIL_FAST)
                .onStageError((stage, error) ->
                    log.warn("  ⚠️  Stage {} failed: {}", stage.getName(), error.getMessage()))
                .build();

        PipelineBehavior.PipelineResult<String> result1 = failFastPipeline.executePipeline("test").join();
        log.info("Result: {}", result1.isSuccess() ? "SUCCESS" : "FAILED");
        if (!result1.isSuccess()) {
            log.info("Error: {}", result1.getErrorMessage());
            log.info("Stages executed: {}/3", result1.getStageResults().size());
        }

        // Scenario 2: CONTINUE_ON_ERROR
        log.info("\nScenario 2: CONTINUE_ON_ERROR strategy");
        PipelineBehavior<String, String> continueOnErrorPipeline =
            PipelineBehavior.<String, String>builder("continue-on-error", String.class, String.class)
                .addStage("stage1", (String s) -> s.toUpperCase())
                .addStage("failing-stage", (String s) -> {
                    throw new RuntimeException("Simulated error");
                })
                .addStage("stage3", (String s) -> s + "!!!")
                .errorStrategy(PipelineBehavior.ErrorHandlingStrategy.CONTINUE_ON_ERROR)
                .failFast(false)
                .onStageError((stage, error) ->
                    log.warn("  ⚠️  Stage {} failed but continuing: {}", stage.getName(), error.getMessage()))
                .build();

        PipelineBehavior.PipelineResult<String> result2 = continueOnErrorPipeline.executePipeline("test").join();
        log.info("Result: {}", result2.isSuccess() ? "SUCCESS" : "FAILED");
        if (result2.isSuccess()) {
            log.info("Output: '{}'", result2.getOutput());
            log.info("All stages executed: {}/3", result2.getStageResults().size());
        }
    }

    /**
     * Example 4: Pipeline metrics
     */
    private static void example4_PipelineMetrics() {
        log.info("\n" + "-".repeat(80));
        log.info("Example 4: Pipeline Metrics");
        log.info("-".repeat(80));

        PipelineBehavior<Integer, Integer> pipeline =
            PipelineBehavior.<Integer, Integer>builder("metrics-pipeline", Integer.class, Integer.class)
                .addStage("double", (Integer n) -> n * 2)
                .addStage("add-ten", (Integer n) -> n + 10)
                .build();

        // Execute pipeline multiple times
        log.info("Executing pipeline 5 times...");
        for (int i = 1; i <= 5; i++) {
            PipelineBehavior.PipelineResult<Integer> result = pipeline.executePipeline(i).join();
            log.info("  Execution {}: {} -> {}", i, i, result.getOutput());
        }

        // Display metrics
        PipelineBehavior.PipelineMetrics metrics = pipeline.getMetrics();
        log.info("\n📊 Pipeline Metrics:");
        log.info("  Total executions: {}", metrics.getTotalExecutions());
        log.info("  Successful: {}", metrics.successfulExecutions());
        log.info("  Failed: {}", metrics.failedExecutions());
        log.info("  Success rate: {:.2f}%", metrics.getSuccessRate() * 100);
        log.info("  Total stages executed: {}", metrics.totalStagesExecuted());
        log.info("  Avg stages per execution: {:.2f}", metrics.getAverageStagesPerExecution());

        // Reset metrics
        pipeline.resetMetrics();
        log.info("\n✅ Metrics reset");
        log.info("  Total executions after reset: {}", pipeline.getMetrics().getTotalExecutions());
    }

    // Helper method for discount calculation
    private static BigDecimal calculateDiscount(Order order) {
        // Simple discount: 10% off orders over $1000
        if (order.totalAmount().compareTo(new BigDecimal("1000")) > 0) {
            return order.totalAmount().multiply(new BigDecimal("0.10"));
        }
        return BigDecimal.ZERO;
    }

    // ========================================================================================
    // DOMAIN MODELS
    // ========================================================================================

    record Order(
        String orderId,
        String customerId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        LocalDateTime createdAt
    ) {}

    record OrderItem(
        String productId,
        String productName,
        int quantity,
        BigDecimal price
    ) {}

    record OrderWithTax(
        Order order,
        BigDecimal tax
    ) {}

    record ProcessedOrder(
        String orderId,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal totalWithTax,
        String status,
        LocalDateTime processedAt
    ) {}
}
