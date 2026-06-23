package dev.agenor.examples.context;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.context.AgentContext;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Demonstrates the {@link AgentContext} injection pattern.
 *
 * <p>The scenario: an order system where {@code OrderSubmitterAgent} (classic
 * {@code BaseAgent} subclass) publishes orders, and {@code OrderProcessorAgent}
 * processes them. The processor must extend an existing {@code OrderRepository}
 * domain class, so it cannot extend {@code BaseAgent}. It receives all framework
 * services via a single {@link AgentContext} constructor parameter instead.
 *
 * <p>Key points:
 * <ul>
 *   <li>{@code OrderProcessorAgent} extends {@code OrderRepository} AND implements {@code Agent}</li>
 *   <li>No {@code BaseAgent} inheritance required</li>
 *   <li>All core services are available through {@code ctx.*()}</li>
 *   <li>{@code OrderSubmitterAgent} uses the classic {@code BaseAgent} style — both coexist</li>
 * </ul>
 *
 * <p>When agents are discovered via package scanning, {@code AgentFactory} injects
 * {@code AgentContext} automatically. In this example the agent is manually registered,
 * so we build the context from the runtime getters.
 *
 * @since 0.10.0
 */
public class AgentContextExample {

    private static final Logger log = LoggerFactory.getLogger(AgentContextExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Agenor AgentContext Example ===");
        log.info("Demonstrates plain Agent impl (no BaseAgent) via AgentContext injection");
        log.info("");

        AgenorRuntime runtime = AgenorRuntime.builder().build();

        // Classic BaseAgent-based agent
        runtime.registerAgent(new OrderSubmitterAgent());

        // Plain Agent implementor — AgentContext injected automatically by AgentFactory during
        // package scanning. When registering manually, we use the runtime helper.
        runtime.registerAgent(new OrderProcessorAgent(runtime.getAgentContext()));

        runtime.start().join();
        log.info("Runtime started with {} agents", runtime.getAgents().size());

        // Let the agents exchange messages for a few cycles
        Thread.sleep(15_000);

        log.info("Stopping...");
        runtime.stop().join();
        log.info("=== AgentContext Example completed ===");
    }

    // =========================================================================
    // Domain layer — simulates an existing class hierarchy the agent must fit in
    // =========================================================================

    /**
     * Simulated domain base class: an in-memory order repository.
     * In a real project this could be a JPA entity, a Spring component, etc.
     */
    static class OrderRepository {

        private final List<String> processedOrders = Collections.synchronizedList(new ArrayList<>());

        protected void save(String orderId) {
            processedOrders.add(orderId);
        }

        public List<String> getProcessedOrders() {
            return Collections.unmodifiableList(processedOrders);
        }

        public int getProcessedCount() {
            return processedOrders.size();
        }
    }

    // =========================================================================
    // Agents
    // =========================================================================

    /**
     * Classic agent — extends BaseAgent, publishes orders every 3 seconds.
     */
    @Agent(value = "order-submitter", type = "producer", capabilities = {"order-submission"})
    public static class OrderSubmitterAgent extends BaseAgent {

        private static final String[] PRODUCTS = {"Laptop", "Phone", "Tablet", "Monitor", "Keyboard"};
        private final Random random = new Random();
        private int orderCounter = 0;

        public OrderSubmitterAgent() {
            super("order-submitter", "Order Submitter");
        }

        @Behavior(type = CYCLIC, interval = "3s", autoStart = true)
        public void submitOrder() {
            var orderId = "ORD-" + (++orderCounter);
            var product = PRODUCTS[random.nextInt(PRODUCTS.length)];
            var quantity = 1 + random.nextInt(5);

            var orderMsg = Message.builder()
                    .topic("orders.new")
                    .senderId(getAgentId())
                    .content(orderId)
                    .header("product", product)
                    .header("quantity", String.valueOf(quantity))
                    .build();
            getMessageDispatcher().publish(orderMsg);

            log.info("[Submitter] Submitted {} — {} x{}", orderId, product, quantity);
        }

        @Override
        protected void onStart() {
            log.info("[Submitter] Ready to submit orders");
        }
    }

    /**
     * Agent that extends {@code OrderRepository} (domain superclass) and implements
     * {@code Agent} directly — receives all framework services via {@link AgentContext}.
     *
     * <p>This is the core demonstration of the AgentContext pattern: the agent
     * has its own inheritance hierarchy and does not extend BaseAgent.
     *
     * <p>{@code @AgenorMessageHandler} works exactly as on BaseAgent subclasses:
     * {@code AnnotationProcessor} wires the subscription via reflection on any {@code Agent}.
     */
    @Agent(value = "order-processor", type = "consumer", capabilities = {"order-processing"})
    public static class OrderProcessorAgent extends OrderRepository implements dev.agenor.core.Agent {

        private static final Logger log = LoggerFactory.getLogger(OrderProcessorAgent.class);

        private final AgentContext ctx;
        private final AtomicBoolean running = new AtomicBoolean(false);

        // Constructor receives AgentContext — injected automatically by AgentFactory
        // when the agent is discovered via package scanning.
        public OrderProcessorAgent(AgentContext ctx) {
            this.ctx = ctx;
        }

        // ----- Agent interface -----

        @Override
        public String getAgentId() {
            return "order-processor";
        }

        @Override
        public String getAgentName() {
            return "Order Processor";
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public CompletableFuture<Void> start() {
            if (!running.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.runAsync(() ->
                    log.info("[Processor] Started — @AgenorMessageHandler will route orders.new")
            );
        }

        @Override
        public CompletableFuture<Void> stop() {
            if (!running.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.runAsync(() -> {
                log.info("[Processor] Stopped. Total orders processed: {}", getProcessedCount());
                log.info("[Processor] Processed orders: {}", getProcessedOrders());
            });
        }

        @Override
        public void addBehavior(dev.agenor.core.Behavior behavior) { /* no scheduled behaviors in this example */ }

        @Override
        public void removeBehavior(String behaviorId) { /* no-op */ }

        @Override
        public dev.agenor.core.messaging.MessageDispatcher getMessageDispatcher() {
            return ctx.messageDispatcher();
        }

        // ----- Message handler — AnnotationProcessor subscribes this automatically -----

        @AgenorMessageHandler("orders.new")
        public void handleOrder(Message message) {
            var orderId = message.getContent(String.class);
            var product = message.headers().getOrDefault("product", "unknown");
            var quantity = message.headers().getOrDefault("quantity", "?");

            // Persist via the domain repository — the reason we cannot extend BaseAgent
            save(orderId);

            log.info("[Processor] Processed {} — {} x{} (total: {})",
                    orderId, product, quantity, getProcessedCount());

            // Publish a confirmation using the injected MessageDispatcher
            var msg = Message.builder()
                    .topic("orders.confirmed")
                    .senderId(getAgentId())
                    .content(orderId)
                    .header("status", "CONFIRMED")
                    .build();
            ctx.messageDispatcher().publish(msg);
        }
    }
}
