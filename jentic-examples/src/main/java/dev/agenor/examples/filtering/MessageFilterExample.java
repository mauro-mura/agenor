package dev.agenor.examples.filtering;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;
import dev.agenor.core.filter.MessageFilterBuilder;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.filter.*;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Demonstrates advanced message filtering capabilities using the new
 * {@link InMemoryMessageDispatcher} API (since 0.20.0).
 *
 * <p>Key changes from the 0.19.x API:
 * <ul>
 *   <li>{@code InMemoryMessageDispatcher} replaces {@code InMemoryMessageService}</li>
 *   <li>{@code dispatcher.subscribeFiltered(filter, handler)} returns a {@link Subscription}</li>
 *   <li>Call {@code subscription.unsubscribe()} instead of {@code messageService.unsubscribe(id)}</li>
 *   <li>{@code dispatcher.publish(topic, msg)} replaces {@code messageService.send(msg)}</li>
 * </ul>
 */
public class MessageFilterExample {

    private static final Logger log = LoggerFactory.getLogger(MessageFilterExample.class);

    public static void main(String[] args) throws Exception {
        log.info("=".repeat(80));
        log.info("JENTIC - ADVANCED MESSAGE FILTERING EXAMPLE");
        log.info("=".repeat(80));

        // Directory is needed by the dispatcher for sendTo resolution;
        // for publish/subscribeFiltered it is not used but still required.
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        InMemoryMessageDispatcher dispatcher = new InMemoryMessageDispatcher(directory);

        // Example 1: Simple topic filtering
        example1_SimpleTopicFiltering(dispatcher);

        // Example 2: Header-based filtering
        example2_HeaderFiltering(dispatcher);

        // Example 3: Complex filter chains
        example3_ComplexFilterChains(dispatcher);

        // Example 4: Content-based filtering
        example4_ContentFiltering(dispatcher);

        // Example 5: Real-world scenario
        example5_RealWorldScenario(dispatcher);

        Thread.sleep(2000);

        log.info("\n" + "=".repeat(80));
        log.info("All filtering examples completed!");
        log.info("=".repeat(80));
    }

    /**
     * Example 1: Simple topic-based filtering
     */
    private static void example1_SimpleTopicFiltering(InMemoryMessageDispatcher dispatcher) {
        log.info("\n" + "-".repeat(80));
        log.info("EXAMPLE 1: Simple Topic Filtering");
        log.info("-".repeat(80));

        // Subscribe to all "order.*" topics
        MessageFilter orderFilter = TopicFilter.startsWith("order.");

        Subscription subscription = dispatcher.subscribeFiltered(
            orderFilter,
            msg -> {
                log.info("✅ Order handler received: {} - {}",
                        msg.topic(), msg.content());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Publish various messages
        publishMessage(dispatcher, "order.created", "Order #1001");
        publishMessage(dispatcher, "order.updated", "Order #1001 updated");
        publishMessage(dispatcher, "product.created", "Product #501"); // Not matched
        publishMessage(dispatcher, "order.shipped", "Order #1001 shipped");

        subscription.unsubscribe();
    }

    /**
     * Example 2: Header-based filtering
     */
    private static void example2_HeaderFiltering(InMemoryMessageDispatcher dispatcher) {
        log.info("\n" + "-".repeat(80));
        log.info("EXAMPLE 2: Header-Based Filtering");
        log.info("-".repeat(80));

        // Subscribe to HIGH priority messages only
        MessageFilter highPriorityFilter = HeaderFilter.equals("priority", "HIGH");

        dispatcher.subscribeFiltered(
            highPriorityFilter,
            msg -> {
                log.info("🚨 HIGH PRIORITY: {} - {}",
                        msg.topic(), msg.content());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Publish messages with different priorities
        publishMessageWithHeader(dispatcher, "alert", "Low priority alert", "priority", "LOW");
        publishMessageWithHeader(dispatcher, "alert", "High priority alert", "priority", "HIGH");
        publishMessageWithHeader(dispatcher, "alert", "Critical alert", "priority", "CRITICAL");
        publishMessageWithHeader(dispatcher, "alert", "Another high priority", "priority", "HIGH");
    }

    /**
     * Example 3: Complex filter chains (AND, OR, NOT)
     */
    private static void example3_ComplexFilterChains(InMemoryMessageDispatcher dispatcher) {
        log.info("\n" + "-".repeat(80));
        log.info("EXAMPLE 3: Complex Filter Chains");
        log.info("-".repeat(80));

        // Complex filter: (order.* OR payment.*) AND priority=HIGH
        MessageFilter complexFilter = MessageFilter.builder()
            .operator(MessageFilterBuilder.FilterOperator.OR)
            .topicStartsWith("order.")
            .topicStartsWith("payment.")
            .build()
            .and(HeaderFilter.equals("priority", "HIGH"));

        dispatcher.subscribeFiltered(
            complexFilter,
            msg -> {
                log.info("💎 Complex filter matched: {} - {}",
                        msg.topic(), msg.content());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Test messages
        publishMessageWithHeader(dispatcher, "order.created", "Order", "priority", "HIGH"); // ✅
        publishMessageWithHeader(dispatcher, "payment.processed", "Payment", "priority", "HIGH"); // ✅
        publishMessageWithHeader(dispatcher, "order.created", "Order", "priority", "LOW"); // ❌
        publishMessageWithHeader(dispatcher, "product.created", "Product", "priority", "HIGH"); // ❌
    }

    /**
     * Example 4: Content-based filtering
     */
    private static void example4_ContentFiltering(InMemoryMessageDispatcher dispatcher) {
        log.info("\n" + "-".repeat(80));
        log.info("EXAMPLE 4: Content-Based Filtering");
        log.info("-".repeat(80));

        // Filter for OrderData content type
        MessageFilter orderDataFilter = ContentFilter.ofType(OrderData.class);

        dispatcher.subscribeFiltered(
            orderDataFilter,
            msg -> {
                OrderData order = msg.getContent(OrderData.class);
                log.info("📦 Order data received: {} - Total: ${}",
                        order.orderId(), order.totalAmount());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Publish different content types
        publishMessage(dispatcher, "order.created",
                new OrderData("ORD-1001", new BigDecimal("999.99")));
        publishMessage(dispatcher, "order.created",
                "Simple string order"); // Not matched
        publishMessage(dispatcher, "order.created",
                new OrderData("ORD-1002", new BigDecimal("1500.00")));
    }

    /**
     * Example 5: Real-world scenario - E-commerce order filtering
     */
    private static void example5_RealWorldScenario(InMemoryMessageDispatcher dispatcher) {
        log.info("\n" + "-".repeat(80));
        log.info("EXAMPLE 5: Real-World Scenario - E-commerce");
        log.info("-".repeat(80));

        // Handler 1: High-value orders (>$1000)
        MessageFilter highValueFilter = MessageFilter.builder()
            .topicStartsWith("order.")
            .contentType(OrderData.class)
            .contentPredicate(content -> {
                OrderData order = (OrderData) content;
                return order.totalAmount().compareTo(new BigDecimal("1000")) > 0;
            })
            .build();

        dispatcher.subscribeFiltered(
            highValueFilter,
            msg -> {
                OrderData order = msg.getContent(OrderData.class);
                log.info("💰 HIGH VALUE ORDER: {} - ${}",
                        order.orderId(), order.totalAmount());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Handler 2: VIP customer orders
        MessageFilter vipFilter = MessageFilter.builder()
            .topicStartsWith("order.")
            .headerEquals("customer-tier", "VIP")
            .build();

        dispatcher.subscribeFiltered(
            vipFilter,
            msg -> {
                log.info("👑 VIP CUSTOMER ORDER: {} from customer {}",
                        msg.topic(), msg.headers().get("customer-id"));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Handler 3: US region orders requiring special handling
        MessageFilter usRegionFilter = MessageFilter.builder()
            .topicStartsWith("order.")
            .headerMatches("region", "us-.*")
            .build();

        dispatcher.subscribeFiltered(
            usRegionFilter,
            msg -> {
                log.info("🇺🇸 US REGION ORDER: {} from {}",
                        msg.topic(), msg.headers().get("region"));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        );

        // Publish test orders
        log.info("\nPublishing test orders...\n");

        // Order 1: High value, VIP, US region (matches all 3 handlers)
        Message order1 = Message.builder()
            .topic("order.created")
            .content(new OrderData("ORD-2001", new BigDecimal("2500.00")))
            .header("customer-id", "CUST-001")
            .header("customer-tier", "VIP")
            .header("region", "us-east-1")
            .build();
        dispatcher.publish(order1);

        // Order 2: Low value, regular customer, EU region (matches no handlers)
        Message order2 = Message.builder()
            .topic("order.created")
            .content(new OrderData("ORD-2002", new BigDecimal("50.00")))
            .header("customer-id", "CUST-002")
            .header("customer-tier", "REGULAR")
            .header("region", "eu-west-1")
            .build();
        dispatcher.publish(order2);

        // Order 3: High value, US region (matches 2 handlers)
        Message order3 = Message.builder()
            .topic("order.created")
            .content(new OrderData("ORD-2003", new BigDecimal("1200.00")))
            .header("customer-id", "CUST-003")
            .header("customer-tier", "REGULAR")
            .header("region", "us-west-2")
            .build();
        dispatcher.publish(order3);
    }

    // Helper methods

    private static void publishMessage(InMemoryMessageDispatcher dispatcher, String topic, Object content) {
        Message msg = Message.builder()
            .topic(topic)
            .content(content)
            .build();
        dispatcher.publish(msg);
    }

    private static void publishMessageWithHeader(InMemoryMessageDispatcher dispatcher,
                                                 String topic, Object content,
                                                 String headerKey, String headerValue) {
        Message msg = Message.builder()
            .topic(topic)
            .content(content)
            .header(headerKey, headerValue)
            .build();
        dispatcher.publish(msg);
    }

    // Sample domain object
    record OrderData(String orderId, BigDecimal totalAmount) {}
}
