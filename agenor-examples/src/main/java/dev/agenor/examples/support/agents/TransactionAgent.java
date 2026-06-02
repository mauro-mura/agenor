package dev.agenor.examples.support.agents;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.examples.support.agents.CollaborativeRouterAgent.AgentConsultation;
import dev.agenor.examples.support.agents.CollaborativeRouterAgent.AgentContribution;
import dev.agenor.examples.support.model.SupportIntent;
import dev.agenor.examples.support.model.SupportQuery;
import dev.agenor.examples.support.model.SupportResponse;
import dev.agenor.examples.support.service.MockUserDataService;
import dev.agenor.examples.support.service.MockUserDataService.Transaction;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles transaction-related queries: history, disputes, exports.
 * Supports collaborative reasoning via ConsultableAgent interface.
 */
@Agent(
    value = "transaction-agent",
    type = "handler",
    capabilities = {"transaction-history", "disputes", "consultable"}
)
public class TransactionAgent extends BaseAgent implements ConsultableAgent {

    private static final Logger log = LoggerFactory.getLogger(TransactionAgent.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a");

    private final MockUserDataService dataService;
    private final DialogueCapability dialogue;

    public TransactionAgent(MockUserDataService dataService) {
        super("transaction-agent", "Transaction Handler");
        this.dataService = dataService;
        this.dialogue = new DialogueCapability(this);
    }

    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());
        getMessageDispatcher().subscribeTopic("support.transaction", MessageHandler.sync(this::handleTransactionQuery));
        log.info("Transaction Agent started with dialogue support");
    }

    @Override
    protected void onStop() {
        dialogue.shutdown();
        log.info("Transaction Agent stopped");
    }

    // ========== CONSULTABLE AGENT IMPLEMENTATION ==========

    @Override
    public List<SupportIntent> getExpertise() {
        return List.of(SupportIntent.TRANSACTION_HISTORY, SupportIntent.BILLING);
    }

    @DialogueHandler(performatives = {Performative.QUERY})
    public void handleConsultation(DialogueMessage msg) {
        if (msg.content() instanceof AgentConsultation consultation) {
            log.debug("Transaction consultation: {}", consultation.queryText());
            AgentContribution contribution = consult(consultation);
            dialogue.reply(msg, Performative.INFORM, contribution);
        }
    }

    @Override
    public AgentContribution consult(AgentConsultation consultation) {
        String queryText = consultation.queryText().toLowerCase();
        String userId = "demo-user";

        double confidence = calculateConfidence(queryText,
            "transaction", "payment", "history", "charge", "spent", "dispute", "refund", "statement");

        if (confidence < 0.3) {
            return cannotContribute("Query not related to transactions");
        }

        List<String> insights = new ArrayList<>();
        StringBuilder response = new StringBuilder();

        List<Transaction> transactions = dataService.getRecentTransactions(userId, 10);

        // Transaction history
        if (containsAny(queryText, "history", "recent", "last", "transactions")) {
            BigDecimal total = transactions.stream()
                .map(Transaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            response.append("Recent transactions: ").append(transactions.size())
                .append(" (Total: $").append(total).append(")");
            insights.add("TRANSACTION_COUNT: " + transactions.size());
            insights.add("TOTAL_SPENT: $" + total);
        }

        // Specific amount queries
        if (containsAny(queryText, "spent", "how much", "total")) {
            BigDecimal total = transactions.stream()
                .map(Transaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (response.length() > 0) response.append("\n");
            response.append("Total spent recently: $").append(total);
            insights.add("SPENDING_SUMMARY: $" + total);
        }

        // Dispute related
        if (containsAny(queryText, "dispute", "fraud", "unauthorized", "wrong")) {
            if (response.length() > 0) response.append("\n");
            response.append("Disputes can be filed within 60 days of transaction");
            insights.add("ACTION: File dispute");
            insights.add("DISPUTE_WINDOW: 60 days");
        }

        // Last transaction
        if (containsAny(queryText, "last", "latest", "most recent") && !transactions.isEmpty()) {
            Transaction last = transactions.get(0);
            if (response.length() > 0) response.append("\n");
            response.append("Latest: ").append(last.merchant())
                .append(" - $").append(last.amount());
            insights.add("LAST_TRANSACTION: " + last.merchant() + " ($" + last.amount() + ")");
        }

        // Export/statement
        if (containsAny(queryText, "export", "statement", "download", "pdf")) {
            if (response.length() > 0) response.append("\n");
            response.append("Statements available in PDF/CSV format");
            insights.add("ACTION: Export statement");
        }

        if (response.length() == 0 && !transactions.isEmpty()) {
            Transaction last = transactions.get(0);
            response.append("Last transaction: ").append(last.merchant())
                .append(" ($").append(last.amount()).append(") on ")
                .append(last.date().format(DATE_FMT));
        }

        return new AgentContribution(
            getAgentId(),
            response.toString(),
            confidence,
            SupportIntent.TRANSACTION_HISTORY,
            insights
        );
    }

    // ========== ORIGINAL QUERY HANDLERS ==========

    private void handleTransactionQuery(Message message) {
        SupportQuery query = extractQuery(message);
        String queryText = query.text().toLowerCase();
        String userId = query.userId();

        log.debug("Transaction query from {}: '{}'", userId, queryText);

        SupportResponse response;

        if (containsAny(queryText, "dispute", "fraud", "unauthorized", "suspicious", "wrong")) {
            response = handleDisputeQuery(query, userId);
        } else if (containsAny(queryText, "export", "download", "csv", "pdf", "statement")) {
            response = handleExportQuery(query);
        } else if (containsAny(queryText, "pending", "processing", "status")) {
            response = handlePendingQuery(query, userId);
        } else if (containsAny(queryText, "category", "groceries", "entertainment", "shopping")) {
            response = handleCategoryQuery(query, userId, extractCategory(queryText));
        } else if (containsAny(queryText, "recent", "last", "history", "transactions", "activity")) {
            response = handleRecentTransactionsQuery(query, userId);
        } else {
            response = handleRecentTransactionsQuery(query, userId);
        }

        sendResponse(message, response);
    }

    private SupportResponse handleRecentTransactionsQuery(SupportQuery query, String userId) {
        List<Transaction> transactions = dataService.getTransactions(userId, 5);

        if (transactions.isEmpty()) {
            return SupportResponse.simple(query.sessionId(),
                "No transactions found. Link a bank account to start tracking your spending.",
                SupportIntent.TRANSACTION);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Recent Transactions**\n\n");

        for (Transaction txn : transactions) {
            String amountStr = formatAmount(txn.amount());
            String statusBadge = txn.status().equals("pending") ? " ⏳" : "";

            sb.append(String.format("**%s** — %s%s\n",
                txn.merchant(), amountStr, statusBadge));
            sb.append(String.format("  %s • %s\n\n",
                txn.date().format(DATETIME_FMT), txn.category()));
        }

        sb.append("_Showing last 5 transactions. View all in Activity tab._");

        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.TRANSACTION,
            0.9, List.of("Export transactions", "View by category", "Report a problem"), false);
    }

    private SupportResponse handleDisputeQuery(SupportQuery query, String userId) {
        // Check for pending/suspicious transactions
        List<Transaction> transactions = dataService.getTransactions(userId, 10);
        Transaction suspicious = transactions.stream()
            .filter(t -> t.status().equals("pending") || t.category().equals("Unknown"))
            .findFirst()
            .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("**Report a Transaction Problem**\n\n");

        if (suspicious != null) {
            sb.append(String.format("⚠️ I noticed a potentially suspicious transaction:\n"));
            sb.append(String.format("**%s** — %s on %s\n\n",
                suspicious.merchant(),
                formatAmount(suspicious.amount()),
                suspicious.date().format(DATE_FMT)));
            sb.append("Is this the transaction you want to dispute?\n\n");
        }

        sb.append("""
            To file a dispute:
            1. Go to **Activity** tab
            2. Tap the transaction in question
            3. Select **Report a Problem**
            4. Choose the issue type:
               • Unauthorized transaction
               • Duplicate charge
               • Wrong amount
               • Item not received
            5. Add details and submit

            **Timeline**: We investigate within 3-5 business days.

            🚨 **For urgent fraud**: Call 1-800-FINANCE immediately.
            """);

        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.TRANSACTION,
            0.95, List.of("Start dispute now", "Call fraud hotline", "View suspicious transactions"), false);
    }

    private SupportResponse handleExportQuery(SupportQuery query) {
        String text = """
            **Export Your Transactions**

            To export your transaction history:

            1. Go to **Activity** tab
            2. Tap the **Export** icon (top right)
            3. Select your date range
            4. Choose format:
               • **CSV** — Best for Excel/Sheets
               • **PDF** — Best for records
               • **OFX** — Best for accounting software
            5. Choose destination (email, cloud, download)

            **Premium users**: Export up to 24 months of data.
            **Free users**: Export up to 3 months.

            Would you like step-by-step guidance?
            """;

        return new SupportResponse(query.sessionId(), text, SupportIntent.TRANSACTION,
            0.9, List.of("Export as CSV", "Export as PDF", "Upgrade for more history"), false);
    }

    private SupportResponse handlePendingQuery(SupportQuery query, String userId) {
        List<Transaction> transactions = dataService.getTransactions(userId, 20);
        List<Transaction> pending = transactions.stream()
            .filter(t -> t.status().equals("pending"))
            .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("**Pending Transactions**\n\n");

        if (pending.isEmpty()) {
            sb.append("✓ You have no pending transactions.\n\n");
            sb.append("All your recent transactions have been processed.");
        } else {
            sb.append(String.format("You have **%d pending** transaction(s):\n\n", pending.size()));

            for (Transaction txn : pending) {
                sb.append(String.format("• **%s** — %s\n  %s\n\n",
                    txn.merchant(),
                    formatAmount(txn.amount()),
                    txn.date().format(DATETIME_FMT)));
            }

            sb.append("_Pending transactions typically clear within 1-3 business days._");
        }

        return SupportResponse.withConfidence(query.sessionId(), sb.toString(),
            SupportIntent.TRANSACTION, 0.9);
    }

    private SupportResponse handleCategoryQuery(SupportQuery query, String userId, String category) {
        List<Transaction> transactions = dataService.getTransactionsByCategory(userId, category);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**%s Transactions**\n\n", capitalize(category)));

        if (transactions.isEmpty()) {
            sb.append(String.format("No transactions found in the '%s' category.", category));
        } else {
            BigDecimal total = BigDecimal.ZERO;
            for (Transaction txn : transactions.stream().limit(5).toList()) {
                sb.append(String.format("• %s — %s (%s)\n",
                    txn.merchant(), formatAmount(txn.amount()), txn.date().format(DATE_FMT)));
                total = total.add(txn.amount());
            }

            sb.append(String.format("\n**Total**: %s", formatAmount(total)));
            if (transactions.size() > 5) {
                sb.append(String.format("\n_...and %d more_", transactions.size() - 5));
            }
        }

        return SupportResponse.withConfidence(query.sessionId(), sb.toString(),
            SupportIntent.TRANSACTION, 0.85);
    }

    private String extractCategory(String text) {
        if (text.contains("groceries") || text.contains("food")) return "Groceries";
        if (text.contains("entertainment")) return "Entertainment";
        if (text.contains("shopping")) return "Shopping";
        if (text.contains("transport")) return "Transportation";
        return "Unknown";
    }

    private String formatAmount(BigDecimal amount) {
        String prefix = amount.compareTo(BigDecimal.ZERO) < 0 ? "-$" : "+$";
        return prefix + amount.abs().setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private void sendResponse(Message originalMessage, SupportResponse response) {
        Message responseMsg = Message.builder()
            .topic("support.response")
            .senderId(getAgentId())
            .receiverId(originalMessage.senderId())
            .correlationId(response.sessionId())
            .content(response)
            .build();
        getMessageDispatcher().publish(responseMsg);
    }

    private SupportQuery extractQuery(Message message) {
        Object content = message.content();
        if (content instanceof SupportQuery q) return q;
        String text = content instanceof String s ? s : content.toString();
        return new SupportQuery(
            message.correlationId() != null ? message.correlationId() : message.id(),
            message.senderId(),
            text
        );
    }
}
