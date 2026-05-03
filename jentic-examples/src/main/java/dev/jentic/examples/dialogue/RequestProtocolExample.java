package dev.jentic.examples.dialogue;

import dev.jentic.core.Agent;
import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Behavior;
import dev.jentic.core.MessageService;
import dev.jentic.core.dialogue.DialogueHandler;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.runtime.dialogue.DialogueCapability;
import dev.jentic.runtime.directory.InMemoryAgentDirectory;
import dev.jentic.runtime.messaging.InMemoryMessageDispatcher;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runnable example: Request Protocol (Order Processing).
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl jentic-examples \
 *     -Dexec.mainClass="dev.jentic.examples.dialogue.RequestProtocolExample"
 * </pre>
 */
public class RequestProtocolExample {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║       REQUEST PROTOCOL EXAMPLE - Order Processing        ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // Shared infrastructure — agents must be registered so sendTo can resolve endpoints
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        InMemoryMessageDispatcher dispatcher = new InMemoryMessageDispatcher(directory);

        // Create agents
        ServerAgent server = new ServerAgent(dispatcher, directory);
        ClientAgent client = new ClientAgent(dispatcher, directory);

        // Start agents
        server.start().join();
        client.start().join();
        Thread.sleep(100);

        // === Test 1: Successful order ===
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Test 1: Place order (Laptop x2)                        │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        DialogueMessage r1 = client.placeOrder(new Order("Laptop", 2)).get(10, TimeUnit.SECONDS);
        System.out.println("Response: " + r1.performative() + " - " + r1.content() + "\n");

        // === Test 2: Query ===
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Test 2: Query order status                             │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        DialogueMessage r2 = client.queryStatus("ORD-123").get(10, TimeUnit.SECONDS);
        System.out.println("Response: " + r2.content() + "\n");

        // === Test 3: Invalid order (refused) ===
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Test 3: Invalid order (null)                           │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        DialogueMessage r3 = client.placeOrder(null).get(10, TimeUnit.SECONDS);
        System.out.println("Response: " + r3.performative() + " - " + r3.content() + "\n");

        // Cleanup
        client.stop().join();
        server.stop().join();

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                    EXAMPLE COMPLETE                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // SERVER AGENT
    // =========================================================================

    static class ServerAgent implements Agent {

        private final MessageDispatcher dispatcher;
        private final InMemoryAgentDirectory directory;
        private final DialogueCapability dialogue = new DialogueCapability(this);
        private boolean running;

        ServerAgent(MessageDispatcher ms, InMemoryAgentDirectory dir) {
            this.dispatcher = ms;
            this.directory = dir;
        }

        @Override public String getAgentId() { return "server"; }
        @Override public String getAgentName() { return "Server"; }
        @Override public boolean isRunning() { return running; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}
        @Override public MessageService getMessageService() { return null; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                directory.register(AgentDescriptor.builder("server")
                        .agentName("Server").agentType("ServerAgent")
                        .status(AgentStatus.RUNNING).build()).join();
                dialogue.initialize(dispatcher);
                running = true;
                System.out.println("[Server] Started");
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                directory.unregister("server").join();
                running = false;
            });
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            System.out.println("[Server] Received REQUEST: " + msg.content());

            if (msg.content() == null) {
                System.out.println("[Server] REFUSE - null order");
                dialogue.refuse(msg, "Order cannot be null");
                return;
            }

            System.out.println("[Server] AGREE - processing");
            dialogue.agree(msg, "Accepted");

            // Simulate async processing
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(300); } catch (Exception e) {}
                String result = "Completed: " + msg.content();
                System.out.println("[Server] INFORM - " + result);
                dialogue.inform(msg, result);
            });
        }

        @DialogueHandler(performatives = Performative.QUERY)
        public void handleQuery(DialogueMessage msg) {
            System.out.println("[Server] Received QUERY: " + msg.content());
            dialogue.inform(msg, "Status of " + msg.content() + ": PROCESSING");
        }
    }

    // =========================================================================
    // CLIENT AGENT
    // =========================================================================

    static class ClientAgent implements Agent {

        private final MessageDispatcher dispatcher;
        private final InMemoryAgentDirectory directory;
        private final DialogueCapability dialogue = new DialogueCapability(this);
        private boolean running;

        ClientAgent(MessageDispatcher ms, InMemoryAgentDirectory dir) {
            this.dispatcher = ms;
            this.directory = dir;
        }

        @Override public String getAgentId() { return "client"; }
        @Override public String getAgentName() { return "Client"; }
        @Override public boolean isRunning() { return running; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}
        @Override public MessageService getMessageService() { return null; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                directory.register(AgentDescriptor.builder("client")
                        .agentName("Client").agentType("ClientAgent")
                        .status(AgentStatus.RUNNING).build()).join();
                dialogue.initialize(dispatcher);
                running = true;
                System.out.println("[Client] Started");
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                directory.unregister("client").join();
                running = false;
            });
        }

        CompletableFuture<DialogueMessage> placeOrder(Order order) {
            System.out.println("[Client] Placing order: " + order);
            return dialogue.request("server", order, Duration.ofSeconds(10));
        }

        CompletableFuture<DialogueMessage> queryStatus(String orderId) {
            System.out.println("[Client] Querying: " + orderId);
            return dialogue.query("server", orderId, Duration.ofSeconds(10));
        }
    }

    // =========================================================================
    // DATA
    // =========================================================================

    record Order(String product, int quantity) {}
}
