package dev.agenor.examples.agent;

import dev.agenor.core.Message;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.memory.InMemoryStore;

import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * Complete end-to-end example demonstrating LLM Memory Management.
 *
 * <p>This example shows:
 * <ul>
 *   <li>Automatic LLM memory injection by runtime</li>
 *   <li>Creating and configuring a ChatAgent</li>
 *   <li>Interactive conversation with memory</li>
 *   <li>Fact extraction and retrieval</li>
 *   <li>Auto-summarization</li>
 * </ul>
 *
 * <p><b>Key Feature:</b> LLMMemoryManager is automatically injected by
 * JenticRuntime when MemoryStore is configured - no manual setup needed!
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * java dev.agenor.examples.ChatAgentExample
 * }</pre>
 *
 * @since 0.6.0
 */
public class ChatAgentExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Jentic LLM Memory Management - Complete Example");
        System.out.println("Integration Demo (with Auto-Injection)");
        System.out.println("=".repeat(60));
        System.out.println();

        // ========== SETUP WITH AUTO-INJECTION ==========

        System.out.println("1. Creating runtime with memory support...");

        // Create runtime with memory store
        // This automatically configures LLM memory for all agents!
        JenticRuntime runtime = JenticRuntime.builder()
            .memoryStore(new InMemoryStore())  // ← Enables LLM memory auto-injection
            .build();
        System.out.println("   ✓ Created JenticRuntime with InMemoryStore");
        System.out.println("   ✓ LLM memory will be auto-injected for all agents");

        // Create and register agent
        // LLMMemoryManager is automatically injected by runtime!
        ChatAgent agent = new ChatAgent();
        runtime.registerAgent(agent);
        System.out.println("   ✓ Created ChatAgent (LLM memory auto-configured)");

        // Get message dispatcher from runtime (since 0.20.0)
        MessageDispatcher messageService = runtime.getMessageDispatcher();

        System.out.println();

        // ========== SUBSCRIBE TO RESPONSES ==========

        System.out.println("2. Subscribing to agent responses...");

        CountDownLatch responseLatch = new CountDownLatch(1);

        messageService.subscribeTopic("agent.response", msg -> {
            String response = msg.getContent(String.class);

            // Parse tokens from header (default to 0)
            int tokens = 0;
            String tokensHeader = msg.headers().get("conversationTokens");
            if (tokensHeader != null) {
                try {
                    tokens = Integer.parseInt(tokensHeader);
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }

            System.out.println();
            System.out.println("🤖 Agent: " + response);
            System.out.println("   (Conversation: " + tokens + " tokens)");
            System.out.println();

            responseLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        messageService.subscribeTopic("agent.notification", msg -> {
            String notification = msg.getContent(String.class);
            System.out.println("📢 Notification: " + notification);
            System.out.println();
            return CompletableFuture.completedFuture(null);
        });

        System.out.println("   ✓ Subscribed to agent.response");
        System.out.println("   ✓ Subscribed to agent.notification");
        System.out.println();

        // ========== START AGENT ==========

        System.out.println("3. Starting runtime...");
        runtime.start().join();


        // ========== DEMO CONVERSATION ==========

        System.out.println("4. Running demonstration conversation...");
        System.out.println();
        System.out.println("-".repeat(60));
        System.out.println();

        // Conversation 1: Introduction
        sendUserMessage(messageService, "Hi, my name is Alice and I'm from Paris.");
        Thread.sleep(1000);

        sendUserMessage(messageService, "I love blue color and prefer tea over coffee.");
        Thread.sleep(1000);

        // Conversation 2: Memory query
        sendUserMessage(messageService, "What's my name?");
        Thread.sleep(1000);

        sendUserMessage(messageService, "Where am I from?");
        Thread.sleep(1000);

        // Conversation 3: Additional context
        sendUserMessage(messageService, "I work as a software engineer.");
        Thread.sleep(1000);

        sendUserMessage(messageService, "My favorite programming language is Java.");
        Thread.sleep(1000);

        // Show conversation stats
        System.out.println("-".repeat(60));
        System.out.println();
        System.out.println("5. Conversation statistics:");
        System.out.println("   Messages: " + agent.getConversationMessageCount());
        System.out.println("   Tokens: " + agent.getConversationTokens());
        System.out.println();

        // ========== INTERACTIVE MODE ==========

        System.out.println("6. Entering interactive mode...");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  - Type a message to chat");
        System.out.println("  - Type 'clear' to clear conversation");
        System.out.println("  - Type 'summarize' to summarize old messages");
        System.out.println("  - Type 'status' to see agent status");
        System.out.println("  - Type 'exit' to quit");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.print("You: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            switch (input.toLowerCase()) {
                case "exit":
                case "quit":
                    running = false;
                    break;

                case "clear":
                    messageService.publish(Message.builder()
                        .topic("clear.conversation")
                        .content("clear")
                        .build());
                    Thread.sleep(500);
                    break;

                case "summarize":
                    messageService.publish(Message.builder()
                        .topic("summarize.conversation")
                        .header("count", "10")
                        .content("summarize")
                        .build());
                    Thread.sleep(500);
                    break;

                case "status":
                    messageService.publish(Message.builder()
                        .topic("query.status")
                        .content("status")
                        .build());
                    Thread.sleep(500);
                    break;

                default:
                    sendUserMessage(messageService, input);
                    Thread.sleep(1000);
                    break;
            }
        }

        // ========== SHUTDOWN ==========

        System.out.println();
        System.out.println("7. Shutting down...");

        runtime.stop().join();


        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Example completed successfully!");
        System.out.println("=".repeat(60));
    }

    /**
     * Publish a user message to the "user.message" topic.
     */
    private static void sendUserMessage(MessageDispatcher messageService, String text) {
        System.out.println("👤 You: " + text);

        messageService.publish(Message.builder()
            .topic("user.message")
            .content(text)
            .build());
    }
}
