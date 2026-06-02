package dev.agenor.examples.eval;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.messaging.FilterableSubscriber;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.filter.TopicFilter;
import dev.agenor.tools.eval.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agenor.core.BehaviorType.*;

/**
 * Demonstrates the Agent Evaluation Framework with annotated agents.
 *
 * <p>This example shows how to:
 * <ul>
 *   <li>Create agents with @Agent and @Behavior annotations</li>
 *   <li>Define custom evaluation scenarios</li>
 *   <li>Use standard scenarios for common tests</li>
 *   <li>Run evaluations and generate reports</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class EvaluationFrameworkExample {

    private static final Logger log = LoggerFactory.getLogger(EvaluationFrameworkExample.class);

    public static void main(String[] args) throws Exception {
        log.info("=== Agenor Agent Evaluation Framework Example ===\n");

        // 1. Create and start runtime
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        runtime.start();

        // 2. Create scenario runner
        ScenarioRunner runner = new ScenarioRunner(runtime);

        try {
            // 3. Run various evaluation scenarios
            List<EvaluationResult> results = new ArrayList<>();

            // Test 1: Order Processor Agent Lifecycle
            log.info("Running Test 1: Order Processor Lifecycle...");
            results.add(runner.run(orderProcessorLifecycleScenario()));

            // Test 2: Message Processing
            log.info("Running Test 2: Message Processing...");
            results.add(runner.run(messageProcessingScenario(runtime)));

            // Test 3: Multi-Agent Communication
            log.info("Running Test 3: Multi-Agent Communication...");
            results.add(runner.run(multiAgentCommunicationScenario()));

            // Test 4: Health Check
            log.info("Running Test 4: System Health Check...");
            results.add(runner.run(StandardScenarios.healthCheck()));

            // Test 5: Throughput Test
            log.info("Running Test 5: Throughput Test...");
            results.add(runner.run(StandardScenarios.throughput(50, Duration.ofSeconds(2))));

            // Test 6: Custom Business Logic
            log.info("Running Test 6: Custom Business Logic...");
            results.add(runner.run(customBusinessLogicScenario()));

            // 4. Generate and print report
            log.info("\n");
            EvaluationReport report = runner.createReport(results);
            report.printSummary();

            // 5. Export reports
            report.toMarkdown(Path.of("evaluation-report.md"));
            report.toJson(Path.of("evaluation-report.json"));
            log.info("\nReports exported to evaluation-report.md and evaluation-report.json");

        } finally {
            runner.shutdown();
            runtime.stop();
        }
    }

    // =========================================================================
    // SCENARIO DEFINITIONS
    // =========================================================================

    /**
     * Tests OrderProcessorAgent lifecycle and basic behavior.
     */
    static Scenario orderProcessorLifecycleScenario() {
        return Scenario.builder("order-processor-lifecycle")
            .description("Tests OrderProcessorAgent start, processing, and stop")
            .timeout(Duration.ofSeconds(15))
            .setup(runtime -> {
                OrderProcessorAgent agent = new OrderProcessorAgent();
                runtime.registerAgent(agent);
            })
            .execute(runtime -> {
                // Start agent
                runtime.getAgents().stream()
                    .filter(a -> a.getAgentId().equals("order-processor"))
                    .findFirst()
                    .ifPresent(agent -> {
                        agent.start();
                        // Wait for agent to start
                        sleep(500);
                    });
            })
            .verify(ctx -> List.of(
                ctx.assertAgentRunning("order-processor"),
                ctx.assertAgentCount(1),
                ctx.assertComponentHealthy("runtime")
            ))
            .teardown(runtime -> {
                runtime.getAgents().forEach(agent -> agent.stop());
            })
            .build();
    }

    /**
     * Tests message processing flow.
     */
    static Scenario messageProcessingScenario(AgenorRuntime runtime) {
        return Scenario.builder("message-processing")
            .description("Tests order message processing flow")
            .timeout(Duration.ofSeconds(10))
            .setup(rt -> {
                OrderProcessorAgent agent = new OrderProcessorAgent();
                rt.registerAgent(agent);
                agent.start();
                sleep(200);
            })
            .execute(rt -> {
                // Send test order
                Message orderMessage = Message.builder()
                    .topic("orders.new")
                    .senderId("test-client")
                    .content(new Order("ORD-001", "Widget", 5, 19.99))
                    .header("priority", "normal")
                    .build();

                rt.getMessageDispatcher().publish(orderMessage);

                // Wait for processing
                sleep(1000);
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                results.add(ctx.assertAgentRunning("order-processor"));
                results.add(ctx.assertMessageReceived("orders.new"));
                results.add(ctx.assertMessageCountAtLeast(1));
                results.add(ctx.assertNoDroppedMessages());
                return results;
            })
            .teardown(rt -> {
                rt.getAgents().forEach(agent -> agent.stop());
            })
            .build();
    }

    /**
     * Tests communication between multiple agents.
     */
    static Scenario multiAgentCommunicationScenario() {
        return Scenario.builder("multi-agent-communication")
            .description("Tests communication between OrderProcessor and InventoryChecker")
            .timeout(Duration.ofSeconds(20))
            .setup(runtime -> {
                // Register multiple agents
                OrderProcessorAgent orderAgent = new OrderProcessorAgent();
                InventoryCheckerAgent inventoryAgent = new InventoryCheckerAgent();
                NotificationAgent notificationAgent = new NotificationAgent();

                runtime.registerAgent(orderAgent);
                runtime.registerAgent(inventoryAgent);
                runtime.registerAgent(notificationAgent);

                // Start all agents
                orderAgent.start();
                inventoryAgent.start();
                notificationAgent.start();
                sleep(300);
            })
            .execute(runtime -> {
                // Send order that triggers inter-agent communication
                Message orderMessage = Message.builder()
                    .topic("orders.new")
                    .senderId("test-client")
                    .content(new Order("ORD-002", "Premium Widget", 10, 49.99))
                    .build();

                runtime.getMessageDispatcher().publish(orderMessage);

                // Wait for multi-agent processing
                sleep(2000);
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();

                // All agents should be running
                results.add(ctx.assertAgentRunning("order-processor"));
                results.add(ctx.assertAgentRunning("inventory-checker"));
                results.add(ctx.assertAgentRunning("notification-agent"));

                // Should have agent count of 3
                results.add(ctx.assertAgentCount(3));

                // Runtime should be healthy (allow DEGRADED for agents component)
                results.add(ctx.assertComponentHealthy("runtime"));

                // Messages should flow through the system
                results.add(ctx.assertMessageCountAtLeast(1));

                return results;
            })
            .teardown(runtime -> {
                runtime.getAgents().forEach(agent -> agent.stop());
            })
            .build();
    }

    /**
     * Tests custom business logic validation.
     */
    static Scenario customBusinessLogicScenario() {
        return Scenario.builder("custom-business-logic")
            .description("Tests custom order validation and processing rules")
            .timeout(Duration.ofSeconds(15))
            .setup(runtime -> {
                OrderProcessorAgent agent = new OrderProcessorAgent();
                runtime.registerAgent(agent);
                agent.start();
                sleep(200);
            })
            .execute(runtime -> {
                // Send multiple orders with different characteristics
                for (int i = 1; i <= 5; i++) {
                    Message order = Message.builder()
                        .topic("orders.new")
                        .senderId("batch-client")
                        .content(new Order("ORD-10" + i, "Item-" + i, i * 2, 10.0 * i))
                        .header("batch", "true")
                        .build();

                    runtime.getMessageDispatcher().publish(order);
                    sleep(100);
                }

                // Wait for all processing
                sleep(1000);
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();

                // Basic assertions
                results.add(ctx.assertAgentRunning("order-processor"));
                results.add(ctx.assertComponentHealthy("runtime"));

                // Should have processed all orders
                results.add(ctx.assertMessageCountAtLeast(5));

                // Custom business validation
                OrderProcessorAgent agent = (OrderProcessorAgent) ctx.runtime().getAgents().stream()
                    .filter(a -> a.getAgentId().equals("order-processor"))
                    .findFirst()
                    .orElse(null);

                if (agent != null) {
                    int processed = agent.getProcessedCount();
                    results.add(ctx.assertCondition(
                        "orders-processed",
                        processed >= 5,
                        "Expected at least 5 orders processed, got: " + processed
                    ));

                    double totalValue = agent.getTotalValue();
                    results.add(ctx.assertCondition(
                        "total-value-positive",
                        totalValue > 0,
                        "Expected positive total value, got: " + totalValue
                    ));
                }

                // Timing assertion
                results.add(ctx.assertCompletedWithin(Duration.ofSeconds(10)));

                return results;
            })
            .teardown(runtime -> {
                runtime.getAgents().forEach(agent -> agent.stop());
            })
            .build();
    }

    // =========================================================================
    // AGENT DEFINITIONS
    // =========================================================================

    /**
     * Order processor agent with annotated behaviors.
     */
    @Agent(
        value = "order-processor",
        type = "Processor",
        capabilities = {"order-validation", "order-processing"}
    )
    static class OrderProcessorAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(OrderProcessorAgent.class);

        private final AtomicInteger processedCount = new AtomicInteger(0);
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private double totalValue = 0.0;
        private final List<Order> processedOrders = new CopyOnWriteArrayList<>();

        public OrderProcessorAgent() {
            super("order-processor", "Order Processor Agent");
        }

        @Override
        protected void onStart() {
            log.info("OrderProcessorAgent started");

            // Subscribe to order messages
            getMessageDispatcher().subscribeTopic("orders.new", this::handleNewOrder);
            getMessageDispatcher().subscribeTopic("orders.cancel", this::handleCancelOrder);
        }

        @Override
        protected void onStop() {
            log.info("OrderProcessorAgent stopped. Processed {} orders, total value: ${}",
                processedCount.get(), String.format("%.2f", totalValue));
        }

        /**
         * Periodic status reporting behavior.
         */
        @Behavior(type = CYCLIC, interval = "5s")
        public void reportStatus() {
            log.debug("Status: processed={}, errors={}, totalValue={}",
                processedCount.get(), errorCount.get(), totalValue);
        }

        /**
         * Handles new order messages.
         */
        private java.util.concurrent.CompletableFuture<Void> handleNewOrder(Message message) {
            try {
                if (message.content() instanceof Order order) {
                    processOrder(order);

                    // Send confirmation
                    var confirmMsg = Message.builder()
                        .topic("orders.processed")
                        .senderId(getAgentId())
                        .correlationId(message.id())
                        .content(new OrderConfirmation(order.orderId(), "PROCESSED", Instant.now()))
                        .build();
                    getMessageDispatcher().publish(confirmMsg);

                    // Request inventory check
                    var inventoryMsg = Message.builder()
                        .topic("inventory.check")
                        .senderId(getAgentId())
                        .content(order)
                        .build();
                    getMessageDispatcher().publish(inventoryMsg);
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("Error processing order: {}", e.getMessage());
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        /**
         * Handles order cancellation messages.
         */
        private java.util.concurrent.CompletableFuture<Void> handleCancelOrder(Message message) {
            log.info("Cancellation request received: {}", message.content());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        private void processOrder(Order order) {
            // Validate order
            if (order.quantity() <= 0 || order.price() <= 0) {
                throw new IllegalArgumentException("Invalid order: " + order);
            }

            // Process
            processedOrders.add(order);
            processedCount.incrementAndGet();
            totalValue += order.quantity() * order.price();

            log.info("Processed order: {} - {} x {} @ ${}",
                order.orderId(), order.productName(), order.quantity(), order.price());
        }

        // Getters for evaluation
        public int getProcessedCount() { return processedCount.get(); }
        public int getErrorCount() { return errorCount.get(); }
        public double getTotalValue() { return totalValue; }
        public List<Order> getProcessedOrders() { return List.copyOf(processedOrders); }
    }

    /**
     * Inventory checker agent.
     */
    @Agent(
        value = "inventory-checker",
        type = "Processor",
        capabilities = {"inventory-check", "stock-management"}
    )
    static class InventoryCheckerAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(InventoryCheckerAgent.class);
        private final AtomicInteger checksPerformed = new AtomicInteger(0);

        public InventoryCheckerAgent() {
            super("inventory-checker", "Inventory Checker Agent");
        }

        @Override
        protected void onStart() {
            log.info("InventoryCheckerAgent started");

            getMessageDispatcher().subscribeTopic("inventory.check", this::handleInventoryCheck);
        }

        @Override
        protected void onStop() {
            log.info("InventoryCheckerAgent stopped. Checks performed: {}", checksPerformed.get());
        }

        private java.util.concurrent.CompletableFuture<Void> handleInventoryCheck(Message message) {
            if (message.content() instanceof Order order) {
                checksPerformed.incrementAndGet();

                // Simulate inventory check
                boolean inStock = checkStock(order.productName(), order.quantity());

                // Send result
                var resultMsg = Message.builder()
                    .topic("inventory.result")
                    .senderId(getAgentId())
                    .correlationId(message.id())
                    .content(new InventoryResult(order.orderId(), inStock, order.quantity()))
                    .build();
                getMessageDispatcher().publish(resultMsg);

                // Notify if low stock
                if (!inStock) {
                    var stockMsg = Message.builder()
                        .topic("notifications.lowstock")
                        .senderId(getAgentId())
                        .content("Low stock alert for: " + order.productName())
                        .build();
                    getMessageDispatcher().publish(stockMsg);
                }

                log.debug("Inventory check for {}: inStock={}", order.productName(), inStock);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        private boolean checkStock(String product, int quantity) {
            // Simulate: 80% chance of being in stock
            return Math.random() > 0.2;
        }

        public int getChecksPerformed() { return checksPerformed.get(); }
    }

    /**
     * Notification agent for alerts and notifications.
     */
    @Agent(
        value = "notification-agent",
        type = "Notifier",
        capabilities = {"email", "sms", "push-notification"}
    )
    static class NotificationAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(NotificationAgent.class);
        private final List<String> sentNotifications = new CopyOnWriteArrayList<>();

        public NotificationAgent() {
            super("notification-agent", "Notification Agent");
        }

        @Override
        protected void onStart() {
            log.info("NotificationAgent started");

            ((FilterableSubscriber) getMessageDispatcher()).subscribeFiltered(
                TopicFilter.startsWith("notifications."), this::handleNotification);
        }

        @Override
        protected void onStop() {
            log.info("NotificationAgent stopped. Notifications sent: {}", sentNotifications.size());
        }

        @Behavior(type = CYCLIC, interval = "10s")
        public void flushPendingNotifications() {
            // Periodic flush of any pending notifications
            log.trace("Checking for pending notifications...");
        }

        private java.util.concurrent.CompletableFuture<Void> handleNotification(Message message) {
            String notification = message.topic() + ": " + message.content();
            sentNotifications.add(notification);
            log.info("📢 Notification: {}", notification);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        public List<String> getSentNotifications() { return List.copyOf(sentNotifications); }
    }

    // =========================================================================
    // DATA RECORDS
    // =========================================================================

    record Order(String orderId, String productName, int quantity, double price) {}

    record OrderConfirmation(String orderId, String status, Instant timestamp) {}

    record InventoryResult(String orderId, boolean inStock, int requestedQuantity) {}

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
