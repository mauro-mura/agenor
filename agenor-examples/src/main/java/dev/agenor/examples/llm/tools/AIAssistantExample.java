package dev.agenor.examples.llm.tools;

import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.runtime.AgenorRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.adapters.llm.LLMProviderFactory;
import dev.agenor.core.Message;
import dev.agenor.core.llm.LLMProvider;

/**
 * Example demonstrating how to use the AIAssistantAgent in a Agenor application.
 *
 * This example shows:
 * - Proper Agenor runtime setup
 * - Agent registration and lifecycle
 * - Message-based interaction with AI agent
 * - Tool usage through function calling
 * - Error handling and cleanup
 */
public class AIAssistantExample {

    private static final Logger log = LoggerFactory.getLogger(AIAssistantExample.class);

    public static void main(String[] args) throws Exception {
        // Check for required API key
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: OPENAI_API_KEY environment variable must be set");
            System.err.println("Get your API key from: https://platform.openai.com/api-keys");
            System.exit(1);
        }

        AIAssistantExample example = new AIAssistantExample();
        example.runInteractiveDemo();
    }

    public void runInteractiveDemo() throws Exception {
        log.info("Starting AI Assistant Example");

        // Create AI Assistant with OpenAI provider
        LLMProvider llmProvider = LLMProviderFactory.openai()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName(OpenAIProvider.Models.GPT_4_1)
                .temperature(0.7)
                .maxTokens(1500)
                .build();

        // Create and configure Agenor runtime
        AgenorRuntime runtime = AgenorRuntime.builder()
            .scanPackages("dev.agenor.examples.llm.tools")
            .service(LLMProvider.class, llmProvider)
            .build();

        // Start runtime
        log.info("Starting Agenor runtime...");
        runtime.start().join();

        try {
            // Wait for agents to start
            Thread.sleep(1000);

            // Run interactive session
            runChatSession(runtime);

        } finally {
            // Clean shutdown
            log.info("Shutting down Agenor runtime...");
            runtime.stop().join();
        }

        log.info("AI Assistant Example completed");
    }

    private void runChatSession(AgenorRuntime runtime) {
        System.out.println();
        System.out.println("=== AI Assistant Chat Demo ===");
        System.out.println("The AI Assistant has the following tools available:");
        System.out.println("- Weather information (mock data)");
        System.out.println("- Calculator (basic math expressions)");
        System.out.println("- Time/date information");
        System.out.println("- Database queries (simulated)");
        System.out.println();
        System.out.println("Example prompts:");
        System.out.println("- \"What's the weather in London?\"");
        System.out.println("- \"Calculate 15 * 23 + 100\"");
        System.out.println("- \"What time is it?\"");
        System.out.println("- \"Query users table for active users\"");
        System.out.println();
        System.out.println("Type 'quit' to exit");
        System.out.println("----------------------------------------");

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("You: ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye!");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            // Send chat request and wait for response
            try {
                String response = sendChatRequest(runtime, input);
                System.out.println("AI: " + response);
                System.out.println();

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.out.println();
            }
        }

        scanner.close();
    }

    private String sendChatRequest(AgenorRuntime runtime, String userInput) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder response = new StringBuilder();

        // Generate correlation ID for request tracking
        String correlationId = UUID.randomUUID().toString();

        // Listen for response
        runtime.getMessageDispatcher().subscribeTopic("ai.chat.response", message -> {
            if (correlationId.equals(message.correlationId())) {
                response.append(message.getContent(String.class));
                latch.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Listen for errors
        runtime.getMessageDispatcher().subscribeTopic("ai.chat.error", message -> {
            if (correlationId.equals(message.correlationId())) {
                response.append("Error: ").append(message.getContent(String.class));
                latch.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Send chat request
        Message chatRequest = Message.builder()
            .id(correlationId)
            .topic("ai.chat.request")
            .senderId("user")
            .content(userInput)
            .build();

        runtime.getMessageDispatcher().publish(chatRequest);

        // Wait for response (timeout after 30 seconds)
        boolean received = latch.await(30, TimeUnit.SECONDS);
        if (!received) {
            throw new RuntimeException("Timeout waiting for AI response");
        }

        return response.toString();
    }

    /**
     * Demonstrate programmatic tool execution without chat interface.
     */
    public void demonstrateToolExecution() throws Exception {
        log.info("Starting Tool Execution Demo");

        // Setup runtime
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        LLMProvider llmProvider = LLMProviderFactory.openai()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName(OpenAIProvider.Models.GPT_4O)
            .build();

        AIAssistantAgent aiAgent = new AIAssistantAgent(llmProvider);
        runtime.registerAgent(aiAgent);
        runtime.start().join();

        try {
            // Direct tool execution examples
            System.out.println("=== Direct Tool Execution Demo ===\n");

            // Example 1: Weather tool
            System.out.println("1. Testing weather tool:");
            String weatherResult = executeToolDirectly(runtime, "get_weather", Map.of(
                "location", "Paris, France",
                "units", "celsius"
            ));
            System.out.println(weatherResult);
            System.out.println();

            // Example 2: Calculator tool
            System.out.println("2. Testing calculator tool:");
            String calcResult = executeToolDirectly(runtime, "calculate", Map.of(
                "expression", "(10 + 5) * 3 - 8"
            ));
            System.out.println(calcResult);
            System.out.println();

            // Example 3: Time tool
            System.out.println("3. Testing time tool:");
            String timeResult = executeToolDirectly(runtime, "get_time", Map.of(
                "format", "human"
            ));
            System.out.println(timeResult);
            System.out.println();

        } finally {
            runtime.stop().join();
        }
    }

    private String executeToolDirectly(AgenorRuntime runtime, String toolName, Map<String, Object> arguments)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();

        String correlationId = UUID.randomUUID().toString();

        // Listen for tool result
        runtime.getMessageDispatcher().subscribeTopic("ai.tool.result", message -> {
            if (correlationId.equals(message.correlationId())) {
                AIAssistantAgent.ToolExecutionResponse response =
                    message.getContent(AIAssistantAgent.ToolExecutionResponse.class);

                if (response.success()) {
                    result.append(response.result().toString());
                } else {
                    result.append("Error: ").append(response.error());
                }
                latch.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Send tool execution request
        AIAssistantAgent.ToolExecutionRequest request =
            new AIAssistantAgent.ToolExecutionRequest(toolName, arguments);

        Message toolMessage = Message.builder()
            .id(correlationId)
            .topic("ai.tool.execute")
            .senderId("demo")
            .content(request)
            .build();

        runtime.getMessageDispatcher().publish(toolMessage);

        // Wait for result
        boolean received = latch.await(10, TimeUnit.SECONDS);
        if (!received) {
            throw new RuntimeException("Timeout waiting for tool execution result");
        }

        return result.toString();
    }
}
