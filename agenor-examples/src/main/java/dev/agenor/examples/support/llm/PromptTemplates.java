package dev.agenor.examples.support.llm;

import java.util.List;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.examples.support.context.ConversationContext;
import dev.agenor.examples.support.model.SupportIntent;

/**
 * Prompt templates for LLM-based response generation.
 * Implements RAG (Retrieval-Augmented Generation) pattern.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /**
     * System prompt for support chatbot.
     */
    public static final String SYSTEM_PROMPT = """
        You are a helpful customer support assistant for FinanceCloud, a personal finance management app.

        Your responsibilities:
        - Answer questions accurately based on the provided knowledge base
        - Be concise but thorough
        - Use a friendly, professional tone
        - If you don't know something, say so honestly
        - Suggest related topics when appropriate
        - Never make up information not in the knowledge base

        Guidelines:
        - Keep responses under 200 words unless more detail is needed
        - Use bullet points for step-by-step instructions
        - Acknowledge user frustration empathetically if detected
        - Offer to escalate to a human agent if appropriate

        App features you support:
        - Account management (profile, linked banks, plans)
        - Transactions (history, exports, disputes)
        - Security (password, 2FA, devices)
        - Budgets (creation, tracking, alerts)
        """;

    /**
     * Builds the user prompt with context and retrieved documents.
     */
    public static String buildUserPrompt(String userQuery, List<KnowledgeDocument<SupportIntent>> documents) {
        StringBuilder sb = new StringBuilder();

        // Add knowledge context
        if (documents != null && !documents.isEmpty()) {
            sb.append("KNOWLEDGE BASE CONTEXT:\n");
            sb.append("---\n");

            for (int i = 0; i < documents.size(); i++) {
                KnowledgeDocument doc = documents.get(i);
                sb.append(String.format("[Document %d: %s]\n", i + 1, doc.title()));
                sb.append(doc.content());
                sb.append("\n---\n");
            }
            sb.append("\n");
        }

        // Add user query
        sb.append("USER QUESTION: ");
        sb.append(userQuery);
        sb.append("\n\n");

        // Add instructions
        sb.append("Please answer based on the knowledge base context above. ");
        sb.append("If the answer isn't in the context, say so.");

        return sb.toString();
    }

    /**
     * Builds prompt with conversation history for multi-turn context.
     */
    public static String buildContextualPrompt(
            String userQuery,
            List<KnowledgeDocument<SupportIntent>> documents,
            ConversationContext context) {

        StringBuilder sb = new StringBuilder();

        // Add knowledge context
        if (documents != null && !documents.isEmpty()) {
            sb.append("KNOWLEDGE BASE CONTEXT:\n");
            sb.append("---\n");
            for (KnowledgeDocument doc : documents) {
                sb.append(String.format("[%s]\n%s\n---\n", doc.title(), doc.content()));
            }
            sb.append("\n");
        }

        // Add conversation history if available
        if (context != null && context.getTurnCount() > 1) {
            sb.append("CONVERSATION HISTORY:\n");
            context.getRecentHistory(4).forEach(turn -> {
                String role = turn.type() == ConversationContext.TurnType.USER ? "User" : "Assistant";
                sb.append(String.format("%s: %s\n", role, truncate(turn.text(), 150)));
            });
            sb.append("\n");
        }

        // User sentiment context
        if (context != null && context.isUserFrustrated()) {
            sb.append("NOTE: User appears frustrated. Be extra empathetic and consider offering escalation.\n\n");
        }

        // Current query
        sb.append("CURRENT QUESTION: ");
        sb.append(userQuery);

        return sb.toString();
    }

    /**
     * Domain-specific system prompt additions.
     */
    public static String getDomainContext(SupportIntent intent) {
        return switch (intent) {
            case ACCOUNT -> """

                DOMAIN FOCUS: Account Management
                Key topics: Profile settings, linked bank accounts, subscription plans, account closure.
                Common issues: Balance sync, profile updates, plan upgrades.
                """;

            case SECURITY -> """

                DOMAIN FOCUS: Security
                Key topics: Password reset, 2FA setup, trusted devices, account access.
                Important: Always prioritize user security. Never share sensitive info.
                For fraud concerns, recommend immediate action.
                """;

            case TRANSACTION -> """

                DOMAIN FOCUS: Transactions
                Key topics: Transaction history, exports, disputes, pending charges.
                For disputes: Guide through the in-app process, mention 3-5 day timeline.
                For suspicious activity: Recommend immediate security review.
                """;

            case BUDGET -> """

                DOMAIN FOCUS: Budgets
                Key topics: Budget creation, spending tracking, alerts, categories.
                Be encouraging about financial goals.
                Suggest realistic budget amounts based on common patterns.
                """;

            case ESCALATE -> """

                DOMAIN FOCUS: Escalation
                The user wants to speak with a human agent.
                Be empathetic, acknowledge their need, provide queue/callback options.
                """;

            default -> "";
        };
    }

    /**
     * Prompt for generating suggested actions.
     */
    public static String buildSuggestionsPrompt(String response, SupportIntent intent) {
        return String.format("""
            Based on this support response:
            ---
            %s
            ---

            Suggest 2-3 brief follow-up actions the user might want to take.
            Format as a JSON array of strings, e.g.: ["Action 1", "Action 2", "Action 3"]
            Keep each action under 5 words.
            """, truncate(response, 500));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
