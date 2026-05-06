package dev.jentic.examples.ecommerce;

import dev.jentic.core.Message;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.runtime.JenticRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ECommerceApplication {

    private static final Logger log = LoggerFactory.getLogger(ECommerceApplication.class);

    public static void main(String[] args) throws Exception {
        log.info("=".repeat(80));
        log.info("JENTIC E-COMMERCE ORDER PROCESSING - DEMONSTRATION");
        log.info("Using: FSM + Parallel Validators + Sequential Fulfillment");
        log.info("=".repeat(80) + "\n");

        JenticRuntime runtime = JenticRuntime.builder()
                .scanPackage("dev.jentic.examples.ecommerce")
                .build();

        log.info("🚀 Starting Jentic Runtime...\n");
        runtime.start().get(10, TimeUnit.SECONDS);

        // Wait for agents to be ready
        Thread.sleep(2000);

        MessageDispatcher dispatcher = runtime.getMessageDispatcher();

        // =====================================================================
        // SCENARIO 1: Valid Order
        // =====================================================================

        log.info("\n" + "=".repeat(80));
        log.info("SCENARIO 1: Processing a VALID order");
        log.info("=".repeat(80) + "\n");

        Order order1 = new Order(
                "customer-alice",
                List.of(
                        new OrderItem("PROD-001", "Laptop", 1, new BigDecimal("999.99")),
                        new OrderItem("PROD-002", "Mouse", 2, new BigDecimal("29.99"))
                )
        );

        Message newOrder1 = Message.builder()
                .topic("new-order")
                .senderId("web-app")
                .content(order1)
                .build();

        // Send via the shared dispatcher
        dispatcher.publish(newOrder1);

        // Wait for order processing (FSM runs automatically via cyclic behavior)
        Thread.sleep(8000);

        // =====================================================================
        // SCENARIO 2: Invalid Order
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("SCENARIO 2: Processing an INVALID order (bad customer)");
        log.info("=".repeat(80) + "\n");

        Order order2 = new Order(
                "", // Invalid customer ID
                List.of(
                        new OrderItem("PROD-003", "Keyboard", 1, new BigDecimal("79.99"))
                )
        );

        Message newOrder2 = Message.builder()
                .topic("new-order")
                .senderId("web-app")
                .content(order2)
                .build();

        dispatcher.publish(newOrder2);

        // Wait for order processing
        Thread.sleep(5000);

        // =====================================================================
        // Shutdown
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("Shutting down Jentic Runtime...");
        log.info("=".repeat(80) + "\n");

        runtime.stop().get(10, TimeUnit.SECONDS);

        log.info("✅ Application terminated successfully\n");
    }
}
