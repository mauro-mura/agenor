package dev.agenor.examples.a2a;

import dev.agenor.adapters.a2a.A2AAdapterConfig;
import dev.agenor.adapters.a2a.JenticA2AAdapter;
import dev.agenor.adapters.a2a.JenticAgentExecutor;
import dev.agenor.core.Behavior;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCard;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example: A2A Protocol Integration with Jentic Dialogue Protocol.
 *
 * <p>This example demonstrates:
 * <ol>
 *   <li>Creating an A2AAdapterConfig for agent metadata</li>
 *   <li>Exposing a Jentic agent as an A2A server via JenticAgentExecutor</li>
 *   <li>Using JenticA2AAdapter for auto-routing (internal/external)</li>
 *   <li>Communicating with external A2A agents</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl agenor-examples \
 *     -Dexec.mainClass="dev.agenor.examples.a2a.A2AIntegrationExample"
 * </pre>
 */
public class A2AIntegrationExample {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║          A2A PROTOCOL INTEGRATION EXAMPLE                ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // === Part 1: Setup Infrastructure ===
        System.out.println("┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Part 1: Infrastructure Setup                            │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Create internal agent
        OrderProcessorAgent processor = new OrderProcessorAgent();

        JenticRuntime runtime = JenticRuntime.builder().build();
        runtime.registerAgent(processor);
        runtime.start().join();

        System.out.println("[Setup] Internal agent registered: " + processor.getAgentId());

        // === Part 2: A2A Configuration ===
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Part 2: A2A Agent Configuration                         │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Create A2A configuration for the agent
        A2AAdapterConfig config = A2AAdapterConfig.create()
            .agentName("Order Processor")
            .agentDescription("Processes customer orders with dialogue protocol")
            .baseUrl("http://localhost:8080")
            .version("1.0.0")
            .streamingEnabled(true)
            .addSkill(new A2AAdapterConfig.SkillConfig(
                "process-order",
                "Process Order",
                "Processes a customer order",
                List.of("orders", "processing"),
                List.of("Process order for 5 widgets")
            ))
            .addSkill(new A2AAdapterConfig.SkillConfig(
                "check-status",
                "Check Order Status",
                "Checks the status of an existing order",
                List.of("orders", "status"),
                List.of("Check status of order #12345")
            ));

        // Convert to A2A AgentCard
        AgentCard agentCard = config.toAgentCard();
        System.out.println("[A2A] Agent Card created:");
        System.out.println("      Name: " + agentCard.name());
        System.out.println("      Description: " + agentCard.description());
        System.out.println("      Skills: " + agentCard.skills().size());
        System.out.println("      Streaming: " + agentCard.capabilities().streaming());

        // === Part 3: Server-side (AgentExecutor) ===
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Part 3: A2A Server (AgentExecutor)                      │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Create executor for handling incoming A2A requests
        AgentExecutor executor = new JenticAgentExecutor(
            processor.getAgentId(),
            runtime.getMessageDispatcher(),
            Duration.ofMinutes(5)
        );

        System.out.println("[A2A Server] JenticAgentExecutor created");
        System.out.println("             Routes A2A requests to: " + processor.getAgentId());

        // In production, this would be registered with an A2A server:
        // server.registerExecutor(executor);
        // server.start();

        // === Part 4: Client-side (A2AAdapter) ===
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Part 4: A2A Client (JenticA2AAdapter)                   │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Create A2A adapter for sending messages
        JenticA2AAdapter adapter = new JenticA2AAdapter(
            runtime.getMessageDispatcher(),
            runtime.getAgentDirectory(),
            "client-agent",
            Duration.ofMinutes(5)
        );

        // Test internal routing
        System.out.println("\n[Test] Sending to internal agent...");
        DialogueMessage internalMsg = DialogueMessage.builder()
            .senderId("client-agent")
            .receiverId(processor.getAgentId())  // internal
            .performative(Performative.REQUEST)
            .content("Order: 10 widgets")
            .build();

        DialogueMessage response = adapter.send(internalMsg).get(10, TimeUnit.SECONDS);
        System.out.println("[Response] " + response.performative() + ": " + response.content());

        // === Part 5: External A2A Communication (simulated) ===
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Part 5: External A2A Communication                      │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        // Example of sending to external A2A agent (would require real server)
        System.out.println("[Info] To send to external A2A agents:");
        System.out.println("       DialogueMessage msg = DialogueMessage.builder()");
        System.out.println("           .receiverId(\"https://external-agent.com\")");
        System.out.println("           .performative(Performative.QUERY)");
        System.out.println("           .content(\"What is the weather?\")");
        System.out.println("           .build();");
        System.out.println("       adapter.send(msg);  // Auto-routes via HTTP");

        // Check routing detection
        System.out.println("\n[Routing] Detection:");
        System.out.println("  - 'order-processor' → internal? " +
            adapter.isInternalAgent("order-processor"));
        System.out.println("  - 'https://api.example.com' → external? " +
            adapter.isExternalA2AUrl("https://api.example.com"));

        // === Part 6: Streaming Support ===
        System.out.println("\n┌─────────────────────────────────────────────────────────┐");
        System.out.println("│ Part 6: Streaming Support                               │");
        System.out.println("└─────────────────────────────────────────────────────────┘");

        System.out.println("[Info] For long-running tasks with status updates:");
        System.out.println("       adapter.sendWithStreaming(msg, (state, message) -> {");
        System.out.println("           System.out.println(\"Status: \" + state);");
        System.out.println("       });");

        // Cleanup
        runtime.stop().join();

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                    EXAMPLE COMPLETE                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // INTERNAL ORDER PROCESSOR AGENT
    // =========================================================================

    static class OrderProcessorAgent extends BaseAgent {


        private final DialogueCapability dialogue = new DialogueCapability(this);
        private boolean running;


        @Override public String getAgentId() { return "order-processor"; }
        @Override public String getAgentName() { return "Order Processor"; }
        @Override public boolean isRunning() { return running; }
        @Override public void addBehavior(Behavior b) {}
        @Override public void removeBehavior(String id) {}

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                dialogue.initialize(getMessageDispatcher());
                running = true;
                System.out.println("[OrderProcessor] Started");
            });
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> {
                dialogue.shutdown();
                running = false;
            });
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            System.out.println("[OrderProcessor] Processing: " + msg.content());
            dialogue.agree(msg, "Order accepted");

            // Simulate processing
            try { Thread.sleep(100); } catch (Exception e) {}

            String result = "Order completed: " + msg.content();
            dialogue.inform(msg, result);
        }

        @DialogueHandler(performatives = Performative.QUERY)
        public void handleQuery(DialogueMessage msg) {
            System.out.println("[OrderProcessor] Query: " + msg.content());
            dialogue.inform(msg, "Order status: IN_PROGRESS");
        }

    }
}
