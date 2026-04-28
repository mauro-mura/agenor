package dev.jentic.examples.llm;

import dev.jentic.core.*;
import dev.jentic.core.annotations.*;
import dev.jentic.core.llm.*;
import dev.jentic.runtime.agent.BaseAgent;

/**
 * Customer support agent that analyzes tickets using LLM.
 *
 * Features:
 * - Sentiment analysis
 * - Priority classification
 * - Automated response suggestions
 */
@JenticAgent(
        value = "customer-support-agent",
        type = "customer-support",
        capabilities = {"ticket-analysis", "sentiment-detection", "priority-classification"},
        autoStart = true
)
public class CustomerSupportAgent extends BaseAgent {

    private final LLMProvider llmProvider;
    private final String model;

    public CustomerSupportAgent(LLMProvider llmProvider, String model) {
        super("customer-support-agent");
        this.llmProvider = llmProvider;
        this.model = model;
    }

    /**
     * Analyze customer ticket and extract key information.
     */
    @JenticMessageHandler(value = "ticket.analyze", autoSubscribe = true)
    public void handleAnalyzeTicket(Message message) {
        String ticketContent = message.content().toString();

        llmProvider.chat(
                LLMRequest.builder().model(model)
                        .addMessage(LLMMessage.system(
                                "You are a customer support analyzer. Analyze tickets and provide:\n" +
                                        "1. Sentiment (positive/neutral/negative)\n" +
                                        "2. Priority (low/medium/high/critical)\n" +
                                        "3. Category (technical/billing/account/other)\n" +
                                        "4. Suggested response\n" +
                                        "Respond in JSON format."
                        ))
                        .addMessage(LLMMessage.user("Analyze this ticket:\n\n" + ticketContent))
                        .temperature(0.3)
                        .maxTokens(500)
                        .build()
        ).thenAccept(response -> {
            log.debug("LLM analysis completed: {} tokens", response.usage().totalTokens());
            TicketAnalysis analysis = parseAnalysis(response.content());

            var resultMsg = Message.builder()
                    .senderId(getAgentId())
                    .receiverId(message.senderId())
                    .correlationId(message.id())
                    .topic("ticket.analysis.result")
                    .content(analysis.toString())
                    .build();
            getMessageDispatcher().publish(resultMsg.topic(), resultMsg);
        });
    }

    /**
     * Generate personalized response based on ticket analysis.
     */
    @JenticMessageHandler(value = "ticket.respond", autoSubscribe = true)
    public void handleGenerateResponse(Message message) {
        // Parse ticket data from message content
        String content = message.content().toString();
        String[] parts = content.split("\\|");
        if (parts.length < 3) return;

        String ticketText = parts[0];
        String sentiment = parts[1];
        String priority = parts[2];

        llmProvider.chat(
                LLMRequest.builder().model(model)
                        .addMessage(LLMMessage.system(
                                "You are a professional customer support agent. " +
                                        "Generate helpful, empathetic responses to customer issues."
                        ))
                        .addMessage(LLMMessage.user(
                                String.format(
                                        "Customer Issue: %s\n" +
                                                "Customer Sentiment: %s\n" +
                                                "Priority: %s\n\n" +
                                                "Generate an appropriate response.",
                                        ticketText, sentiment, priority
                                )
                        ))
                        .temperature(0.7)
                        .maxTokens(300)
                        .build()
        ).thenAccept(response -> {
            var responseMsg = Message.builder()
                    .senderId(getAgentId())
                    .receiverId(message.senderId())
                    .correlationId(message.id())
                    .topic("ticket.response.generated")
                    .content(response.content())
                    .build();
            getMessageDispatcher().publish(responseMsg.topic(), responseMsg);
        });
    }

    /**
     * Classify ticket category using function calling.
     */
    @JenticMessageHandler(value = "ticket.classify", autoSubscribe = true)
    public void handleClassifyTicket(Message message) {
        String ticketContent = message.content().toString();

        FunctionDefinition classifyFunction = FunctionDefinition.builder("classify_ticket")
                .description("Classify customer ticket into appropriate category")
                .enumParameter("category", "Ticket category", true,
                        "technical", "billing", "account", "product", "shipping", "other")
                .numberParameter("confidence", "Classification confidence (0-1)", true)
                .parameter("keywords", "array", "Key terms found in ticket", false)
                .build();

        llmProvider.chat(
                LLMRequest.builder().model(model)
                        .addMessage(LLMMessage.user("Classify this ticket: " + ticketContent))
                        .addFunction(classifyFunction)
                        .build()
        ).thenAccept(response -> {
            String category = "other";
            if (response.hasFunctionCalls()) {
                FunctionCall call = response.functionCalls().get(0);
                category = call.getStringArgument("category");
            }

            var classifyMsg = Message.builder()
                    .senderId(getAgentId())
                    .receiverId(message.senderId())
                    .correlationId(message.id())
                    .topic("ticket.classification.result")
                    .content(category)
                    .build();
            getMessageDispatcher().publish(classifyMsg.topic(), classifyMsg);
        });
    }

    @Override
    protected void onStart() {
        log.info("CustomerSupportAgent started with model: {}", model);
    }

    @Override
    protected void onStop() {
        log.info("CustomerSupportAgent stopped");
    }

    // Helper methods

    private TicketAnalysis parseAnalysis(String jsonResponse) {
        // Parse JSON response into structured data
        // Simplified for example
        return new TicketAnalysis("neutral", "medium", "technical", "");
    }

    // Data records

    record TicketAnalysis(String sentiment, String priority, String category, String suggestion) {}
}