package dev.agenor.examples.ecommerce;

import dev.agenor.core.*;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.OneShotBehavior;
import dev.agenor.runtime.behavior.composite.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Agent(
        value = "order-orchestrator",
        type = "Orchestrator",
        capabilities = {"order-processing", "workflow-management"}
)
public class OrderOrchestratorAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(OrderOrchestratorAgent.class);

    // Timeout configurations
    private static final Duration FSM_STATE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FULFILLMENT_STEP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ORDER_TOTAL_TIMEOUT = Duration.ofMinutes(5);

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private String currentOrderId = null;
    private FSMBehavior orderFSM;

    public OrderOrchestratorAgent(AgentDirectory agentDirectory,
                                  BehaviorScheduler behaviorScheduler) {
        this.agentDirectory = agentDirectory;
        this.behaviorScheduler = behaviorScheduler;
        this.orderFSM = buildOrderProcessingFSM();
    }

    private FSMBehavior buildOrderProcessingFSM() {
        FSMBehavior fsm = FSMBehavior.builder("order-fsm", "IDLE")
                .stateTimeout(FSM_STATE_TIMEOUT)
                .state("IDLE", createIdleStateBehavior())
                .state("VALIDATING", createValidatingStateBehavior())
                .state("PROCESSING_PAYMENT", createPaymentProcessingBehavior())
                .state("FULFILLING", createFulfillmentBehavior())
                .state("COMPLETED", createCompletedBehavior())
                .state("FAILED", createFailedBehavior())

                // Transitions
                .transition("IDLE", "VALIDATING",
                        f -> currentOrderId != null)

                .transition("VALIDATING", "PROCESSING_PAYMENT",
                        f -> isValidationComplete() && isValidationSuccessful())

                .transition("VALIDATING", "FAILED",
                        f -> {
                            // ✅ Timeout check + validation failure
                            if (!isValidationComplete()) {
                                Order order = getCurrentOrder();
                                if (order != null) {
                                    Duration elapsed = Duration.between(
                                            order.createdAt(),
                                            Instant.now()
                                    );
                                    if (elapsed.compareTo(VALIDATION_TIMEOUT.multipliedBy(2)) > 0) {
                                        log.error("Validation timeout for order: {}", order.orderId());
                                        return true;  // Timeout → FAILED
                                    }
                                }
                                return false;  // Ancora in attesa
                            }
                            return !isValidationSuccessful();  // Validation failed → FAILED
                        })

                .transition("PROCESSING_PAYMENT", "FULFILLING",
                        f -> getCurrentOrder() != null && getCurrentOrder().paymentId() != null)

                .transition("FULFILLING", "COMPLETED",
                        f -> getCurrentOrder() != null && getCurrentOrder().shipmentId() != null)

                .transition("FAILED", "IDLE", f -> true)
                .transition("COMPLETED", "IDLE", f -> true)

                .build();

        if (fsm instanceof dev.agenor.core.composite.CompositeBehavior composite) {
            composite.setAgent(this);
        }

        return fsm;
    }

    // =========================================================================
    // FSM STATE BEHAVIORS
    // =========================================================================

    private dev.agenor.core.Behavior createIdleStateBehavior() {
        return new OneShotBehavior("idle") {
            @Override
            protected void action() {
                if (currentOrderId == null) {
                    log.trace("Order orchestrator in IDLE state");
                }
            }
        };
    }

    private dev.agenor.core.Behavior createValidatingStateBehavior() {
        return new OneShotBehavior("trigger-validations") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                log.info("🔄 Starting parallel validations for order: {}", order.orderId());

                // ✅ Validazioni parallele con CompletableFuture e timeout
                CompletableFuture<Boolean> customerValid = validateCustomer(order.customerId());
                CompletableFuture<Boolean> inventoryValid = validateInventory(order.items());
                CompletableFuture<Boolean> paymentValid = validatePayment(order.totalAmount().toString());

                // Aspetta tutte le validazioni con timeout globale
                try {
                    CompletableFuture.allOf(customerValid, inventoryValid, paymentValid)
                            .orTimeout(VALIDATION_TIMEOUT.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS)
                            .get();

                    // Tutte completate - memorizza risultati
                    Map<String, Boolean> results = new ConcurrentHashMap<>();
                    results.put("customer", customerValid.getNow(false));
                    results.put("inventory", inventoryValid.getNow(false));
                    results.put("payment", paymentValid.getNow(false));

                    // ✅ Logging dettagliato
                    results.forEach((validator, valid) ->
                            log.info("   ✓ Validation: {} = {}", validator, valid ? "VALID" : "INVALID")
                    );

                    // Update order status
                    orders.put(order.orderId(), order.withStatus(OrderStatus.VALIDATING));

                } catch (Exception e) {
                    log.error("❌ Validation error for order: {}", order.orderId(), e);
                }
            }
        };
    }

    private CompletableFuture<Boolean> validateCustomer(String customerId) {
        Message request = Message.builder()
                .topic("validate-customer")
                .senderId(getAgentId())
                .content(customerId)
                .build();
        return requestReply("validate-customer", request, VALIDATION_TIMEOUT.toMillis())
                .thenApply(response -> {
                    Map<String, Object> result = response.getContent(Map.class);
                    return (Boolean) result.getOrDefault("valid", false);
                })
                .exceptionally(throwable -> {
                    log.error("Customer validation timeout/error", throwable);
                    return false;
                });
    }

    private CompletableFuture<Boolean> validateInventory(List<OrderItem> items) {
        Message request = Message.builder()
                .topic("validate-inventory")
                .senderId(getAgentId())
                .content(items)
                .build();
        return requestReply("validate-inventory", request, VALIDATION_TIMEOUT.toMillis())
                .thenApply(response -> {
                    Map<String, Object> result = response.getContent(Map.class);
                    return (Boolean) result.getOrDefault("valid", false);
                })
                .exceptionally(throwable -> {
                    log.error("Inventory validation timeout/error", throwable);
                    return false;
                });
    }

    private CompletableFuture<Boolean> validatePayment(String amount) {
        Message request = Message.builder()
                .topic("validate-payment")
                .senderId(getAgentId())
                .content(amount)
                .build();
        return requestReply("validate-payment", request, VALIDATION_TIMEOUT.toMillis())
                .thenApply(response -> {
                    Map<String, Object> result = response.getContent(Map.class);
                    return (Boolean) result.getOrDefault("valid", false);
                })
                .exceptionally(throwable -> {
                    log.error("Payment validation timeout/error", throwable);
                    return false;
                });
    }

    private CompletableFuture<Message> requestReply(String topic, Message request, long timeoutMs) {
        CompletableFuture<Message> future = new CompletableFuture<>();
        Subscription sub = getMessageDispatcher().subscribeRecipient(getAgentId(), msg -> {
            if (request.id().equals(msg.correlationId())) {
                future.complete(msg);
            }
            return CompletableFuture.completedFuture(null);
        });
        getMessageDispatcher().publish(request);
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> sub.unsubscribe());
    }

    private dev.agenor.core.Behavior createPaymentProcessingBehavior() {
        return new OneShotBehavior("process-payment") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                log.info("💳 Processing payment for order: {}", order.orderId());
                simulateWork(300);

                String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
                Order updated = order.withPaymentId(paymentId)
                        .withStatus(OrderStatus.PAYMENT_PROCESSING);
                orders.put(order.orderId(), updated);

                log.info("   ✅ Payment processed: {}", paymentId);
            }
        };
    }

    private dev.agenor.core.Behavior createFulfillmentBehavior() {
        SequentialBehavior sequential = new SequentialBehavior(
                "fulfillment-sequence")
                .withStepTimeout(FULFILLMENT_STEP_TIMEOUT); // ✅ Timeout di 10s per ogni step

        sequential.addChildBehavior(new OneShotBehavior("reserve-inventory") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                log.info("📦 Reserving inventory for order: {}", order.orderId());
                simulateWork(200);
                log.info("   ✅ Inventory reserved");
            }
        });

        sequential.addChildBehavior(new OneShotBehavior("prepare-shipment") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                log.info("📮 Preparing shipment for order: {}", order.orderId());
                simulateWork(250);

                String shipmentId = "SHIP-" + UUID.randomUUID().toString().substring(0, 8);
                Order updated = order.withShipmentId(shipmentId)
                        .withStatus(OrderStatus.PREPARING);
                orders.put(order.orderId(), updated);

                log.info("   ✅ Shipment prepared: {}", shipmentId);
            }
        });

        sequential.addChildBehavior(new OneShotBehavior("ship-order") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                log.info("🚚 Shipping order: {}", order.orderId());
                simulateWork(100);

                Order updated = order.withStatus(OrderStatus.SHIPPED);
                orders.put(order.orderId(), updated);

                log.info("   ✅ Order shipped with tracking: {}", order.shipmentId());
            }
        });

        sequential.setAgent(this);
        return sequential;
    }

    private dev.agenor.core.Behavior createCompletedBehavior() {
        return new OneShotBehavior("complete-order") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                Order completed = order.withStatus(OrderStatus.DELIVERED);
                orders.put(order.orderId(), completed);

                Duration processingTime = Duration.between(order.createdAt(), Instant.now());

                log.info("✅ ORDER COMPLETED: {} (processing time: {}s)",
                        order.orderId(), processingTime.getSeconds());
                log.info("   Customer: {}", order.customerId());
                log.info("   Total: ${}", order.totalAmount());
                log.info("   Payment: {}", order.paymentId());
                log.info("   Shipment: {}", order.shipmentId());

                sendNotification(order, "Your order has been delivered!");

                currentOrderId = null;
            }
        };
    }

    private dev.agenor.core.Behavior createFailedBehavior() {
        return new OneShotBehavior("handle-failure") {
            @Override
            protected void action() {
                Order order = getCurrentOrder();
                if (order == null) return;

                Order failed = order.withStatus(OrderStatus.FAILED);
                orders.put(order.orderId(), failed);

                Duration failureTime = Duration.between(order.createdAt(), Instant.now());

                log.error("❌ ORDER FAILED: {} (failed after: {}s)",
                        order.orderId(), failureTime.getSeconds());

                sendNotification(order, "Order failed - please contact support");

                currentOrderId = null;
            }
        };
    }

    // =========================================================================
    // CYCLIC BEHAVIOR - Runs the FSM continuously
    // =========================================================================

    @Behavior(type = BehaviorType.CYCLIC, interval = "500ms")
    public void runOrderStateMachine() {
        try {
            orderFSM.execute().join();
        } catch (Exception e) {
            log.error("Error executing FSM", e);
        }
    }

    // =========================================================================
    // MESSAGE HANDLERS
    // =========================================================================

    @AgenorMessageHandler("new-order")
    public void handleNewOrder(Message message) {
        Order order = message.getContent(Order.class);

        log.info("\n" + "=".repeat(80));
        log.info("📦 NEW ORDER RECEIVED");
        log.info("=".repeat(80));
        log.info("Order ID: {}", order.orderId());
        log.info("Customer: {}", order.customerId());
        log.info("Items: {}", order.items().size());
        log.info("Total: ${}", order.totalAmount());
        log.info("=".repeat(80) + "\n");

        orders.put(order.orderId(), order);
        currentOrderId = order.orderId();

        Message ack = message.reply("Order received")
                .topic("order-ack")
                .build();
        getMessageDispatcher().sendTo(ack);
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private Order getCurrentOrder() {
        return currentOrderId != null ? orders.get(currentOrderId) : null;
    }

    private boolean isValidationComplete() {
        return true;
    }

    private boolean isValidationSuccessful() {
        Order order = getCurrentOrder();
        return order != null && order.status() == OrderStatus.VALIDATING;
    }

    private void sendNotification(Order order, String text) {
        Message notification = Message.builder()
                .topic("order-notification")
                .senderId(getAgentId())
                .content(Map.of(
                        "orderId", order.orderId(),
                        "customerId", order.customerId(),
                        "message", text
                ))
                .build();
        getMessageDispatcher().publish(notification);
    }

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
