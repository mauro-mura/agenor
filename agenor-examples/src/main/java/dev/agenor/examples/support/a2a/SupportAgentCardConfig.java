package dev.agenor.examples.support.a2a;

import dev.agenor.adapters.a2a.A2AAdapterConfig;
import dev.agenor.adapters.a2a.A2AAdapterConfig.SkillConfig;

import java.time.Duration;
import java.util.List;

/**
 * A2A Agent Card configuration for FinanceCloud Support Chatbot.
 * Defines skills, capabilities, and metadata for external A2A communication.
 */
public class SupportAgentCardConfig {

    private static final String AGENT_NAME = "financecloud-support";
    private static final String AGENT_DESCRIPTION =
        "Personal finance support chatbot with multi-agent routing, " +
        "knowledge base retrieval, and LLM-enhanced responses.";
    private static final String VERSION = "1.0.0";

    /**
     * Creates the A2A adapter configuration with all support skills.
     */
    public static A2AAdapterConfig create(String baseUrl) {
        return A2AAdapterConfig.create()
            .agentName(AGENT_NAME)
            .agentDescription(AGENT_DESCRIPTION)
            .baseUrl(baseUrl)
            .version(VERSION)
            .streamingEnabled(true)
            .timeout(Duration.ofMinutes(2))
            .inputModes(List.of("text"))
            .outputModes(List.of("text"))
            // Add all support skills
            .addSkill(new SkillConfig(
                "support-query",
                "Support Query",
                "General support query with automatic intent classification and routing",
                List.of("support", "faq", "help"),
                List.of("How do I reset my password?", "What's my balance?", "Show recent transactions")
            ))
            .addSkill(new SkillConfig(
                "account-info",
                "Account Information",
                "Retrieve account details including balance, profile, and linked accounts",
                List.of("account", "balance", "profile"),
                List.of("What's my balance?", "Show my profile", "List linked accounts")
            ))
            .addSkill(new SkillConfig(
                "transaction-history",
                "Transaction History",
                "View transaction history, export statements, and dispute charges",
                List.of("transactions", "history", "dispute"),
                List.of("Show recent transactions", "Export my history", "Dispute a charge")
            ))
            .addSkill(new SkillConfig(
                "security-help",
                "Security Help",
                "Password reset, 2FA setup, device management, and security alerts",
                List.of("security", "password", "2fa", "devices"),
                List.of("Reset my password", "Enable 2FA", "Show trusted devices")
            ))
            .addSkill(new SkillConfig(
                "budget-management",
                "Budget Management",
                "Create, view, and manage spending budgets and alerts",
                List.of("budget", "spending", "alerts"),
                List.of("Show my budgets", "Create a budget", "Set spending alert")
            ))
            .addSkill(new SkillConfig(
                "escalate",
                "Human Escalation",
                "Request to speak with a human support agent via chat, callback, or email",
                List.of("human", "agent", "escalation"),
                List.of("Speak to a human", "Request callback", "Transfer to support")
            ));
    }

    /**
     * Creates configuration with default localhost URL.
     */
    public static A2AAdapterConfig createDefault() {
        return create("http://localhost:8080");
    }

    /**
     * Creates configuration for production deployment.
     */
    public static A2AAdapterConfig createProduction(String baseUrl, boolean streaming) {
        return A2AAdapterConfig.create()
            .agentName(AGENT_NAME)
            .agentDescription(AGENT_DESCRIPTION)
            .baseUrl(baseUrl)
            .version(VERSION)
            .streamingEnabled(streaming)
            .timeout(Duration.ofMinutes(5))
            .inputModes(List.of("text"))
            .outputModes(List.of("text"))
            .addSkill(new SkillConfig("support-query", "Support Query",
                "General support query handler"))
            .addSkill(new SkillConfig("account-info", "Account Information",
                "Account details and balance"))
            .addSkill(new SkillConfig("transaction-history", "Transaction History",
                "Transaction listing and disputes"))
            .addSkill(new SkillConfig("security-help", "Security Help",
                "Password, 2FA, devices"))
            .addSkill(new SkillConfig("budget-management", "Budget Management",
                "Budget tracking and alerts"))
            .addSkill(new SkillConfig("escalate", "Human Escalation",
                "Connect to human agent"));
    }
}
