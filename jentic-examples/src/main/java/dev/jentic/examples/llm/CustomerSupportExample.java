package dev.jentic.examples.llm;

import dev.jentic.adapters.llm.LLMProviderFactory;
import dev.jentic.adapters.llm.openai.OpenAIProvider;
import dev.jentic.core.*;
import dev.jentic.core.llm.*;
import dev.jentic.runtime.JenticRuntime;

/**
 * Example demonstrating CustomerSupportAgent with LLMProvider integration.
 */
public class CustomerSupportExample {

    public static void main(String[] args) {
        // Initialize LLM provider
        LLMProvider llmProvider = LLMProviderFactory.openai()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName(OpenAIProvider.Models.GPT_4O_MINI)
            .temperature(0.7)
            .build();

        // Create Jentic runtime
        JenticRuntime runtime = JenticRuntime.builder()
            .build();

        // Create and register agent
        CustomerSupportAgent agent = new CustomerSupportAgent(llmProvider, "gpt-4o-mini");
        runtime.registerAgent(agent);

        // Start runtime
        runtime.start();

        // Run examples
        example1_TicketAnalysis(runtime, agent);
        example2_ResponseGeneration(runtime, agent);
        example3_Classification(runtime, agent);

        // Shutdown
        runtime.stop().join();
    }

    private static void example1_TicketAnalysis(JenticRuntime runtime, CustomerSupportAgent agent) {
        System.out.println("=== Example 1: Ticket Analysis ===");

        String ticket = """
            I've been trying to log into my account for 3 days now!
            Keep getting 'invalid password' even though I'm using the right one.
            This is extremely frustrating and I need access ASAP for work.
            """;

        Message message = Message.builder()
            .senderId("customer-1")
            .receiverId(agent.getAgentId())
            .topic("ticket.analyze")
            .content(ticket)
            .build();

        runtime.getMessageService().sendAndWait(message, 10000)
            .thenAccept(response -> {
                System.out.println("Analysis Result: " + response.content());
            })
            .join();
    }

    private static void example2_ResponseGeneration(JenticRuntime runtime, CustomerSupportAgent agent) {
        System.out.println("\n=== Example 2: Response Generation ===");

        String ticketData = "Can't access premium features|frustrated|high";

        Message message = Message.builder()
            .senderId("customer-2")
            .receiverId(agent.getAgentId())
            .topic("ticket.respond")
            .content(ticketData)
            .build();

        runtime.getMessageService().sendAndWait(message, 10000)
            .thenAccept(response -> {
                System.out.println("Generated Response:\n" + response.content());
            })
            .join();
    }

    private static void example3_Classification(JenticRuntime runtime, CustomerSupportAgent agent) {
        System.out.println("\n=== Example 3: Classification ===");

        String ticket = "My invoice shows incorrect charges for last month";

        Message message = Message.builder()
            .senderId("customer-3")
            .receiverId(agent.getAgentId())
            .topic("ticket.classify")
            .content(ticket)
            .build();

        runtime.getMessageService().sendAndWait(message, 10000)
            .thenAccept(response -> {
                System.out.println("Category: " + response.content());
            })
            .join();
    }
}
