package dev.jentic.examples.support.agents;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.core.dialogue.DialogueHandler;
import dev.jentic.examples.support.agents.CollaborativeRouterAgent.AgentConsultation;
import dev.jentic.examples.support.agents.CollaborativeRouterAgent.AgentContribution;
import dev.jentic.examples.support.model.SupportIntent;
import dev.jentic.examples.support.model.SupportQuery;
import dev.jentic.examples.support.model.SupportResponse;
import dev.jentic.examples.support.service.MockUserDataService;
import dev.jentic.examples.support.service.MockUserDataService.Budget;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles budget-related queries: creation, tracking, alerts.
 * Supports collaborative reasoning via ConsultableAgent interface.
 */
@JenticAgent(
    value = "budget-agent",
    type = "handler",
    capabilities = {"budget-management", "spending-tracking", "consultable"}
)
public class BudgetAgent extends BaseAgent implements ConsultableAgent {
    
    private static final Logger log = LoggerFactory.getLogger(BudgetAgent.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d");
    
    private final MockUserDataService dataService;
    private final DialogueCapability dialogue;
    
    public BudgetAgent(MockUserDataService dataService) {
        super("budget-agent", "Budget Handler");
        this.dataService = dataService;
        this.dialogue = new DialogueCapability(this);
    }
    
    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());
        getMessageDispatcher().subscribeTopic("support.budget", MessageHandler.sync(this::handleBudgetQuery));
        log.info("Budget Agent started with dialogue support");
    }

    @Override
    protected void onStop() {
        dialogue.shutdown();
        log.info("Budget Agent stopped");
    }
    
    // ========== CONSULTABLE AGENT IMPLEMENTATION ==========
    
    @Override
    public List<SupportIntent> getExpertise() {
        return List.of(SupportIntent.BUDGET, SupportIntent.ACCOUNT_INFO);
    }
    
    @DialogueHandler(performatives = {Performative.QUERY})
    public void handleConsultation(DialogueMessage msg) {
        if (msg.content() instanceof AgentConsultation consultation) {
            log.debug("Budget consultation: {}", consultation.queryText());
            AgentContribution contribution = consult(consultation);
            dialogue.reply(msg, Performative.INFORM, contribution);
        }
    }
    
    @Override
    public AgentContribution consult(AgentConsultation consultation) {
        String queryText = consultation.queryText().toLowerCase();
        String userId = "demo-user";
        
        double confidence = calculateConfidence(queryText,
            "budget", "spending", "limit", "alert", "category", "overspent", "save");
        
        if (confidence < 0.3) {
            return cannotContribute("Query not related to budgets");
        }
        
        List<String> insights = new ArrayList<>();
        StringBuilder response = new StringBuilder();
        
        List<Budget> budgets = dataService.getBudgets(userId);
        
        // Budget overview
        if (containsAny(queryText, "budget", "budgets", "spending", "overview")) {
            response.append("Active budgets: ").append(budgets.size());
            insights.add("BUDGET_COUNT: " + budgets.size());
            
            // Find overspent budgets
            long overspent = budgets.stream()
                .filter(b -> b.spent().compareTo(b.limit()) > 0)
                .count();
            if (overspent > 0) {
                insights.add("ALERT: " + overspent + " budgets exceeded");
            }
        }
        
        // Specific category
        for (Budget budget : budgets) {
            if (queryText.contains(budget.category().toLowerCase())) {
                if (response.length() > 0) response.append("\n");
                BigDecimal remaining = budget.limit().subtract(budget.spent());
                int percent = budget.spent().multiply(BigDecimal.valueOf(100))
                    .divide(budget.limit(), 0, RoundingMode.HALF_UP).intValue();
                
                response.append(budget.category()).append(": $")
                    .append(budget.spent()).append("/$").append(budget.limit())
                    .append(" (").append(percent).append("%)");
                
                insights.add("CATEGORY_" + budget.category().toUpperCase() + ": " + percent + "% used");
                
                if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                    insights.add("ALERT: " + budget.category() + " over budget by $" + remaining.abs());
                }
            }
        }
        
        // Alerts
        if (containsAny(queryText, "alert", "notification", "warning")) {
            if (response.length() > 0) response.append("\n");
            long alertCount = budgets.stream().filter(Budget::alertEnabled).count();
            response.append("Budget alerts: ").append(alertCount).append(" enabled");
            insights.add("ALERTS_ENABLED: " + alertCount);
            insights.add("ACTION: Configure alerts");
        }
        
        // Summary if no specific match
        if (response.length() == 0 && !budgets.isEmpty()) {
            BigDecimal totalLimit = budgets.stream()
                .map(Budget::limit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalSpent = budgets.stream()
                .map(Budget::spent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            response.append("Total budgeted: $").append(totalLimit)
                .append(", Spent: $").append(totalSpent);
            insights.add("TOTAL_BUDGET: $" + totalLimit);
            insights.add("TOTAL_SPENT: $" + totalSpent);
        }
        
        return new AgentContribution(
            getAgentId(),
            response.toString(),
            confidence,
            SupportIntent.BUDGET,
            insights
        );
    }
    
    // ========== ORIGINAL QUERY HANDLERS ==========
    
    private void handleBudgetQuery(Message message) {
        SupportQuery query = extractQuery(message);
        String queryText = query.text().toLowerCase();
        String userId = query.userId();
        
        log.debug("Budget query from {}: '{}'", userId, queryText);
        
        SupportResponse response;
        
        if (containsAny(queryText, "create", "new", "add", "set up")) {
            response = handleCreateBudgetQuery(query);
        } else if (containsAny(queryText, "alert", "notification", "warn", "notify")) {
            response = handleAlertSettingsQuery(query);
        } else if (containsAny(queryText, "delete", "remove", "cancel")) {
            response = handleDeleteBudgetQuery(query);
        } else if (containsAny(queryText, "edit", "change", "modify", "update", "increase", "decrease")) {
            response = handleEditBudgetQuery(query, userId);
        } else {
            response = handleBudgetOverview(query, userId);
        }
        
        sendResponse(message, response);
    }
    
    private SupportResponse handleBudgetOverview(SupportQuery query, String userId) {
        List<Budget> budgets = dataService.getBudgets(userId);
        
        if (budgets.isEmpty()) {
            return createNoBudgetsResponse(query);
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("**Your Budgets**\n\n");
        
        BigDecimal totalLimit = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        
        for (Budget budget : budgets) {
            int percent = budget.percentUsed();
            String statusEmoji = getStatusEmoji(percent);
            String progressBar = createProgressBar(percent);
            
            sb.append(String.format("**%s** %s\n", budget.category(), statusEmoji));
            sb.append(String.format("  %s $%s / $%s (%d%%)\n",
                progressBar,
                budget.spent().setScale(0, java.math.RoundingMode.HALF_UP),
                budget.limit().setScale(0, java.math.RoundingMode.HALF_UP),
                percent));
            sb.append(String.format("  Remaining: $%s\n\n",
                budget.remaining().setScale(2, java.math.RoundingMode.HALF_UP)));
            
            totalLimit = totalLimit.add(budget.limit());
            totalSpent = totalSpent.add(budget.spent());
        }
        
        // Overall summary
        int overallPercent = totalLimit.compareTo(BigDecimal.ZERO) > 0
            ? totalSpent.multiply(BigDecimal.valueOf(100))
                .divide(totalLimit, 0, java.math.RoundingMode.HALF_UP).intValue()
            : 0;
        
        sb.append("---\n");
        sb.append(String.format("**Overall**: $%s / $%s (%d%% of total budgets)\n",
            totalSpent.setScale(0, java.math.RoundingMode.HALF_UP),
            totalLimit.setScale(0, java.math.RoundingMode.HALF_UP),
            overallPercent));
        
        // Check for warnings
        List<Budget> overBudget = budgets.stream()
            .filter(b -> b.percentUsed() >= 90)
            .toList();
        
        if (!overBudget.isEmpty()) {
            sb.append("\n⚠️ **Attention**: ");
            sb.append(overBudget.size() == 1 
                ? "1 budget is near or over limit!"
                : overBudget.size() + " budgets are near or over limit!");
        }
        
        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.BUDGET,
            0.9, List.of("Create new budget", "Edit budget", "Alert settings"), false);
    }
    
    private SupportResponse handleCreateBudgetQuery(SupportQuery query) {
        String text = """
            **Create a New Budget**
            
            To create a budget:
            
            1. Go to **Budget** tab
            2. Tap **+ Create Budget**
            3. Choose a category:
               • Groceries
               • Entertainment
               • Shopping
               • Transportation
               • Dining Out
               • Utilities
               • Custom...
            4. Set your monthly limit
            5. Configure alerts (50%, 75%, 100%)
            6. Tap **Create**
            
            **Tips:**
            • Start with categories where you overspend
            • Be realistic - too strict = frustration
            • Review and adjust monthly
            
            Would you like help choosing a budget amount?
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.BUDGET,
            0.9, List.of("Suggest budget amounts", "View spending history", "Skip for now"), false);
    }
    
    private SupportResponse handleAlertSettingsQuery(SupportQuery query) {
        String text = """
            **Budget Alert Settings**
            
            Alerts help you stay on track. Configure them at:
            Budget > [Select Budget] > Alert Settings
            
            **Available alerts:**
            
            📊 **50% threshold** (optional)
            - Gentle reminder you're halfway through
            
            ⚠️ **75% threshold** (default: on)
            - Warning to slow down spending
            
            🚨 **100% threshold** (default: on)
            - Limit reached notification
            
            📈 **Daily overspend summary** (optional)
            - If over budget, daily recap of excess
            
            **Delivery methods:**
            • Push notifications (recommended)
            • Email digest
            • Both
            
            Go to Settings > Notifications > Budgets to customize.
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.BUDGET,
            0.85, List.of("Enable all alerts", "Email only", "Turn off alerts"), false);
    }
    
    private SupportResponse handleEditBudgetQuery(SupportQuery query, String userId) {
        List<Budget> budgets = dataService.getBudgets(userId);
        
        StringBuilder sb = new StringBuilder();
        sb.append("**Edit Your Budgets**\n\n");
        
        if (budgets.isEmpty()) {
            sb.append("You don't have any budgets to edit yet.\n");
            sb.append("Would you like to create one?");
        } else {
            sb.append("Your current budgets:\n\n");
            
            for (Budget budget : budgets) {
                sb.append(String.format("• **%s**: $%s/month\n",
                    budget.category(),
                    budget.limit().setScale(0, java.math.RoundingMode.HALF_UP)));
            }
            
            sb.append("""
                
                **To edit:**
                1. Go to Budget tab
                2. Tap the budget to modify
                3. Tap **Edit**
                4. Change limit or category
                5. Save changes
                
                Changes take effect immediately for the current period.
                """);
        }
        
        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.BUDGET,
            0.85, List.of("Increase a budget", "Decrease a budget", "Create new"), false);
    }
    
    private SupportResponse handleDeleteBudgetQuery(SupportQuery query) {
        String text = """
            **Delete a Budget**
            
            To remove a budget:
            
            1. Go to **Budget** tab
            2. Tap the budget to delete
            3. Scroll down and tap **Delete Budget**
            4. Confirm deletion
            
            ⚠️ **Note**: 
            • Historical data is preserved for reports
            • You can recreate the budget anytime
            • Deleting won't affect your transactions
            
            Are you sure you want to delete a budget?
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.BUDGET,
            0.85, List.of("Yes, delete it", "No, keep it", "Edit instead"), false);
    }
    
    private SupportResponse createNoBudgetsResponse(SupportQuery query) {
        String text = """
            **No Budgets Yet**
            
            You haven't created any budgets. Budgets help you:
            • Track spending by category
            • Get alerts before overspending
            • Build better financial habits
            
            **Quick start suggestion:**
            Based on common spending patterns, try:
            • Groceries: $400-600/month
            • Entertainment: $100-200/month
            • Dining: $200-400/month
            
            Ready to create your first budget?
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.BUDGET,
            0.9, List.of("Create my first budget", "Show my spending first", "Maybe later"), false);
    }
    
    private String getStatusEmoji(int percent) {
        if (percent >= 100) return "🔴";
        if (percent >= 75) return "🟠";
        if (percent >= 50) return "🟡";
        return "🟢";
    }
    
    private String createProgressBar(int percent) {
        int filled = Math.min(10, percent / 10);
        int empty = 10 - filled;
        return "▓".repeat(filled) + "░".repeat(empty);
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
        getMessageDispatcher().sendTo(responseMsg.receiverId(), responseMsg);
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
