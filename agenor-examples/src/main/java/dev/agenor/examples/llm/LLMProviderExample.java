package dev.agenor.examples.llm;

import dev.agenor.core.llm.*;

import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating the {@link LLMProvider} API in an Agenor framework.
 *
 * Features:
 * - Basic chat
 * - Streaming responses
 * - Conversation history
 * - Function calling
 * - Error handling
 *
 * Runs against a free local Ollama instance by default. Set LLM_BACKEND=groq
 * (+ GROQ_API_KEY) for a free cloud alternative, or LLM_BACKEND=openai/anthropic
 * (+ the matching *_API_KEY) if you have paid credits — see {@link ExampleLLMProvider}.
 */
public class LLMProviderExample {

    public static void main(String[] args) {
        new LLMProviderExample().run();
    }

    private void run() {
        System.out.println("=== Agenor LLM Provider Examples ===\n");

        example1_BasicChat();
        example2_StreamingChat();
        example3_ConversationHistory();
        example4_FunctionCalling();
        example5_InteractiveChatbot();
    }

    // EXAMPLE 1: Basic single-turn chat
    private void example1_BasicChat() {
        System.out.println("--- Example 1: Basic Chat ---");

        LLMProvider provider = ExampleLLMProvider.builder()
            .temperature(0.7)
            .maxTokens(150)
            .build();

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user("Explain quantum computing in one sentence"))
            .build();

        try {
            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get();

            System.out.println("Response: " + response.content());
            System.out.println("Tokens used: " + response.usage().totalTokens());
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // EXAMPLE 2: Streaming response for real-time UX
    private void example2_StreamingChat() {
        System.out.println("--- Example 2: Streaming Chat ---");

        LLMProvider provider = ExampleLLMProvider.builder().build();

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user("Write a haiku about artificial intelligence"))
            .build();

        System.out.print("Response: ");
        try {
            CompletableFuture<Void> future = provider.chatStream(
                request,
                chunk -> {
                    if (chunk.hasContent()) {
                        System.out.print(chunk.content());
                    }
                }
            );
            future.get();
            System.out.println("\n");
        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
        }
    }

    // EXAMPLE 3: Multi-turn conversation with history
    private void example3_ConversationHistory() {
        System.out.println("--- Example 3: Conversation History ---");

        LLMProvider provider = ExampleLLMProvider.builder()
            .maxTokens(200)
            .build();

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.system("You are a helpful math tutor"))
            .addMessage(LLMMessage.user("What is 15 * 8?"))
            .addMessage(LLMMessage.assistant("15 * 8 = 120"))
            .addMessage(LLMMessage.user("Now divide that by 4"))
            .build();

        try {
            LLMResponse response = provider.chat(request).get();
            System.out.println("Response: " + response.content());
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // EXAMPLE 4: Function calling (tool use)
    private void example4_FunctionCalling() {
        System.out.println("--- Example 4: Function Calling ---");

        LLMProvider provider = ExampleLLMProvider.builder().build();

        if (!provider.supportsFunctionCalling()) {
            System.out.println("(" + provider.getProviderName() + " doesn't support function calling in this "
                + "adapter — set LLM_BACKEND=groq or LLM_BACKEND=openai for a full demo)");
        }

        // Define weather tool
        FunctionDefinition weatherFunction = FunctionDefinition.builder("get_weather")
            .description("Get current weather for a location")
            .parameter("location", "string", "City name", true)
            .parameter("units", "string", "celsius or fahrenheit", false)
            .build();

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user("What's the weather in Tokyo?"))
            .addFunction(weatherFunction)
            .build();

        try {
            LLMResponse response = provider.chat(request).get();

            if (response.hasFunctionCalls()) {
                System.out.println("Function calls requested:");
                for (FunctionCall call : response.functionCalls()) {
                    System.out.println("  - " + call.name() + "(" + call.arguments() + ")");
                }
            } else {
                System.out.println("Response: " + response.content());
            }
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // EXAMPLE 5: Interactive chatbot
    private void example5_InteractiveChatbot() {
        System.out.println("--- Example 5: Interactive Chatbot ---");
        System.out.println("Type 'quit' to exit\n");

        LLMProvider provider = ExampleLLMProvider.builder()
            .temperature(0.8)
            .build();

        ConversationManager conversation = new ConversationManager();
        conversation.addMessage(LLMMessage.system(
            "You are a friendly AI assistant. Keep responses concise."
        ));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("You: ");
                String userInput = scanner.nextLine().trim();

                if (userInput.equalsIgnoreCase("quit")) {
                    System.out.println("Goodbye!");
                    break;
                }

                if (userInput.isEmpty()) {
                    continue;
                }

                conversation.addMessage(LLMMessage.user(userInput));

                LLMRequest request = LLMRequest.builder()
                    .messages(conversation.getMessages())
                    .build();

                System.out.print("AI: ");
                StringBuilder response = new StringBuilder();

                CompletableFuture<Void> future = provider.chatStream(
                    request,
                    chunk -> {
                        if (chunk.hasContent()) {
                            System.out.print(chunk.content());
                            response.append(chunk.content());
                        }
                    }
                );

                future.get();
                System.out.println();

                conversation.addMessage(LLMMessage.assistant(response.toString()));
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // Helper class to manage conversation history
    private static class ConversationManager {
        private final java.util.List<LLMMessage> messages = new java.util.ArrayList<>();

        void addMessage(LLMMessage message) {
            messages.add(message);
        }

        java.util.List<LLMMessage> getMessages() {
            return new java.util.ArrayList<>(messages);
        }
    }
}
