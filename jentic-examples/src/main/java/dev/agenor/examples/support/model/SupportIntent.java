package dev.agenor.examples.support.model;

/**
 * Supported intents for the support chatbot.
 */
public enum SupportIntent {
    // General
    FAQ("faq", "General questions and help articles"),
    GENERAL("general", "General or unclassified queries"),
    UNKNOWN("unknown", "Unrecognized intent"),

    // Account
    ACCOUNT("account", "Account management, profile, settings"),
    ACCOUNT_INFO("account-info", "Account information queries"),
    BILLING("billing", "Billing and payment related"),

    // Transactions
    TRANSACTION("transaction", "Transaction history, payments, transfers"),
    TRANSACTION_HISTORY("transaction-history", "Transaction history queries"),

    // Security
    SECURITY("security", "Password, 2FA, device management"),
    PASSWORD_RESET("password-reset", "Password reset requests"),

    // Budget
    BUDGET("budget", "Budget creation, tracking, alerts"),

    // Escalation
    ESCALATE("escalate", "Transfer to human agent"),
    ESCALATION("escalation", "Escalation to human agent");

    private final String code;
    private final String description;

    SupportIntent(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }
}
