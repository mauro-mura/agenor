package dev.agenor.examples.console;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.annotations.Behavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.console.WebConsole;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.tools.console.JettyWebConsole;

import java.util.Random;

public class WebConsoleExample {

    private static final Logger logger = LoggerFactory.getLogger(WebConsoleExample.class);

    public static void main(String[] args) throws Exception {
        logger.info("=== Web Console Example ===");

        AgenorRuntime runtime = AgenorRuntime.builder().build();
        OrderProcessor orderProcessor = new OrderProcessor();
        InventoryManager inventoryManager = new InventoryManager();
        runtime.registerAgent(orderProcessor);
        runtime.registerAgent(inventoryManager);

        // Start web console
        WebConsole console = JettyWebConsole.builder()
                .port(8080)
                .runtime(runtime)
                .build();

        console.start().join();
        runtime.start().join();

        logger.info("Console: {}", console.getBaseUrl());
        logger.info("API: {}", console.getApiUrl());
        logger.info("WebSocket: {}", console.getWebSocketUrl());
        logger.info("Press CTRL+C to stop");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                console.stop().join();
                runtime.stop().join();
            } catch (Exception e) {
                logger.error("Shutdown error", e);
            }
        }));


        // Keep running
        Thread.currentThread().join();
    }

    public static class OrderProcessor extends BaseAgent {

        private final Random random = new Random();
        private int ordersProcessed = 0;

        public OrderProcessor() {
            super("order-processor", "Order Processor");
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "3s")
        public void processOrders() {
            // Simulate processing time
            try {
                Thread.sleep(50 + random.nextInt(100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Simulate occasional failure (10% chance)
            if (random.nextInt(10) == 0) {
                throw new RuntimeException("Database connection timeout");
            }

            ordersProcessed++;
            log.debug("Processed order #{}", ordersProcessed);
        }

        @Override
        protected void onStart() {
            logger.info("Order Processor started");
        }

        @Override
        protected void onStop() {
            logger.info("Order Processor stopped");
        }
    }

    public static class InventoryManager extends BaseAgent {

        private final Random random = new Random();

        public InventoryManager() {
            super("inventory-manager", "Inventory Manager");
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "5s")
        public void checkStock() {
            try {
                Thread.sleep(20 + random.nextInt(50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.debug("Stock levels checked");
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "10s")
        public void warehouseSync() {
            try {
                Thread.sleep(100 + random.nextInt(200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Simulate occasional failure (20% chance)
            if (random.nextInt(5) == 0) {
                throw new RuntimeException("Warehouse API unavailable");
            }

            log.debug("Warehouse sync completed");
        }

        @Override
        protected void onStart() {
            logger.info("Inventory Manager started");
        }

        @Override
        protected void onStop() {
            logger.info("Inventory Manager stopped");
        }
    }
}

