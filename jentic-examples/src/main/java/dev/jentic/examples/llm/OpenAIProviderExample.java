package dev.jentic.examples.llm;

import dev.jentic.adapters.llm.LLMProviderFactory;
import dev.jentic.adapters.llm.openai.OpenAIProvider;
import dev.jentic.core.llm.*;

import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating OpenAI provider usage in Jentic framework.
 *
 * Features:
 * - Basic chat
 * - Streaming responses
 * - Conversation history
 * - Function calling
 * - Error handling
 */
public class OpenAIProviderExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: OPENAI_API_KEY environment variable not set");
            System.exit(1);
        }

        new OpenAIProviderExample().run(apiKey);
    }

    private void run(String apiKey) {
        System.out.println("=== Jentic OpenAI Provider Examples ===\n");

        example1_BasicChat(apiKey);
        example2_StreamingChat(apiKey);
        example3_ConversationHistory(apiKey);
        example4_FunctionCalling(apiKey);
        example5_InteractiveChatbot(apiKey);
    }

    // EXAMPLE 1: Basic single-turn chat
    private void example1_BasicChat(String apiKey) {
        System.out.println("--- Example 1: Basic Chat ---");

        LLMProvider provider = LLMProviderFactory.openai()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4O_MINI)
            .temperature(0.7)
            .maxTokens(150)
            .build();

        LLMRequest request = LLMRequest.builder("basic-chat")
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
    private void example2_StreamingChat(String apiKey) {
        System.out.println("--- Example 2: Streaming Chat ---");

        LLMProvider provider = LLMProviderFactory.openai()
                .apiKey(apiKey)
                .modelName(OpenAIProvider.Models.GPT_4O_MINI)
                .build();

        LLMRequest request = LLMRequest.builder("streaming-chat")
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
    private void example3_ConversationHistory(String apiKey) {
        System.out.println("--- Example 3: Conversation History ---");

        LLMProvider provider = LLMProviderFactory.openai()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4O_MINI)
            .maxTokens(200)
            .build();

        LLMRequest request = LLMRequest.builder("conversation")
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
    private void example4_FunctionCalling(String apiKey) {
        System.out.println("--- Example 4: Function Calling ---");

        LLMProvider provider = LLMProviderFactory.openai()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4O)
            .build();

        // Define weather tool
        FunctionDefinition weatherFunction = FunctionDefinition.builder("get_weather")
            .description("Get current weather for a location")
            .parameter("location", "string", "City name", true)
            .parameter("units", "string", "celsius or fahrenheit", false)
            .build();

        LLMRequest request = LLMRequest.builder("function-call")
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
    private void example5_InteractiveChatbot(String apiKey) {
        System.out.println("--- Example 5: Interactive Chatbot ---");
        System.out.println("Type 'quit' to exit\n");

        LLMProvider provider = LLMProviderFactory.openai()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4O_MINI)
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

                LLMRequest request = LLMRequest.builder("interactive")
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
