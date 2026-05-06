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
import dev.jentic.examples.support.service.MockUserDataService.LinkedAccount;
import dev.jentic.examples.support.service.MockUserDataService.UserProfile;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles account-related queries: profile info, linked accounts, settings.
 * Supports collaborative reasoning via ConsultableAgent interface.
 */
@JenticAgent(
    value = "account-agent",
    type = "handler",
    capabilities = {"account-management", "profile", "consultable"}
)
public class AccountAgent extends BaseAgent implements ConsultableAgent {
    
    private static final Logger log = LoggerFactory.getLogger(AccountAgent.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    
    private final MockUserDataService dataService;
    private final DialogueCapability dialogue;
    
    public AccountAgent(MockUserDataService dataService) {
        super("account-agent", "Account Handler");
        this.dataService = dataService;
        this.dialogue = new DialogueCapability(this);
    }
    
    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());
        getMessageDispatcher().subscribeTopic("support.account", MessageHandler.sync(this::handleAccountQuery));
        log.info("Account Agent started with dialogue support");
    }

    @Override
    protected void onStop() {
        dialogue.shutdown();
        log.info("Account Agent stopped");
    }
    
    // ========== CONSULTABLE AGENT IMPLEMENTATION ==========
    
    @Override
    public List<SupportIntent> getExpertise() {
        return List.of(SupportIntent.ACCOUNT_INFO, SupportIntent.BILLING);
    }
    
    @DialogueHandler(performatives = {Performative.QUERY})
    public void handleConsultation(DialogueMessage msg) {
        if (msg.content() instanceof AgentConsultation consultation) {
            log.debug("Account consultation: {}", consultation.queryText());
            AgentContribution contribution = consult(consultation);
            dialogue.reply(msg, Performative.INFORM, contribution);
        }
    }
    
    @Override
    public AgentContribution consult(AgentConsultation consultation) {
        String queryText = consultation.queryText().toLowerCase();
        String userId = "demo-user"; // In production, extract from session
        
        // Calculate relevance confidence
        double confidence = calculateConfidence(queryText, 
            "balance", "account", "profile", "plan", "subscription", "linked", "bank");
        
        if (confidence < 0.3) {
            return cannotContribute("Query not related to account information");
        }
        
        List<String> insights = new ArrayList<>();
        StringBuilder response = new StringBuilder();
        
        // Check what account info is relevant
        if (containsAny(queryText, "balance", "money", "total", "how much")) {
            dataService.getUser(userId).ifPresent(user -> {
                response.append("Current balance: $")
                    .append(user.balance())
                    .append(" (").append(user.plan()).append(" plan)");
                insights.add("BALANCE: $" + user.balance());
            });
        }
        
        if (containsAny(queryText, "profile", "info", "email", "phone")) {
            dataService.getUser(userId).ifPresent(user -> {
                if (response.length() > 0) response.append("\n");
                response.append("Profile: ").append(user.name())
                    .append(" (").append(user.email()).append(")");
                insights.add("USER: " + user.name());
            });
        }
        
        if (containsAny(queryText, "linked", "bank", "connected")) {
            List<LinkedAccount> accounts = dataService.getLinkedAccounts(userId);
            if (!accounts.isEmpty()) {
                if (response.length() > 0) response.append("\n");
                response.append("Linked accounts: ").append(accounts.size());
                insights.add("LINKED_ACCOUNTS: " + accounts.size());
            }
        }
        
        if (containsAny(queryText, "plan", "subscription", "premium")) {
            dataService.getUser(userId).ifPresent(user -> {
                if (response.length() > 0) response.append("\n");
                response.append("Current plan: ").append(user.plan());
                insights.add("PLAN: " + user.plan());
                insights.add("ACTION: Upgrade plan");
            });
        }
        
        if (response.length() == 0) {
            // Generic account info
            dataService.getUser(userId).ifPresent(user -> {
                response.append("Account holder: ").append(user.name())
                    .append(", Plan: ").append(user.plan())
                    .append(", Balance: $").append(user.balance());
            });
        }
        
        return new AgentContribution(
            getAgentId(),
            response.toString(),
            confidence,
            SupportIntent.ACCOUNT_INFO,
            insights
        );
    }
    
    // ========== ORIGINAL QUERY HANDLERS ==========
    
    private void handleAccountQuery(Message message) {
        SupportQuery query = extractQuery(message);
        String queryText = query.text().toLowerCase();
        String userId = query.userId();
        
        log.debug("Account query from {}: '{}'", userId, queryText);
        
        SupportResponse response;
        
        if (containsAny(queryText, "balance", "how much", "money", "total")) {
            response = handleBalanceQuery(query, userId);
        } else if (containsAny(queryText, "linked", "connected", "bank", "accounts")) {
            response = handleLinkedAccountsQuery(query, userId);
        } else if (containsAny(queryText, "profile", "info", "details", "email", "phone")) {
            response = handleProfileQuery(query, userId);
        } else if (containsAny(queryText, "plan", "subscription", "premium", "upgrade")) {
            response = handlePlanQuery(query, userId);
        } else if (containsAny(queryText, "close", "delete", "cancel")) {
            response = handleCloseAccountQuery(query);
        } else {
            response = handleGenericAccountQuery(query, userId);
        }
        
        sendResponse(message, response);
    }
    
    private SupportResponse handleBalanceQuery(SupportQuery query, String userId) {
        return dataService.getUser(userId)
            .map(user -> {
                StringBuilder sb = new StringBuilder();
                sb.append("**Your Account Balances**\n\n");
                
                BigDecimal total = BigDecimal.ZERO;
                for (LinkedAccount acc : user.linkedAccounts()) {
                    String balanceStr = formatCurrency(acc.balance());
                    sb.append(String.format("• **%s** (%s ...%s): %s\n",
                        acc.bankName(), acc.accountType(), acc.lastFour(), balanceStr));
                    total = total.add(acc.balance());
                }
                
                sb.append(String.format("\n**Net Total**: %s", formatCurrency(total)));
                sb.append("\n\n_Last synced: within the past 2 hours_");
                
                return SupportResponse.withConfidence(query.sessionId(), sb.toString(), 
                    SupportIntent.ACCOUNT, 0.95);
            })
            .orElse(createUserNotFoundResponse(query));
    }
    
    private SupportResponse handleLinkedAccountsQuery(SupportQuery query, String userId) {
        return dataService.getUser(userId)
            .map(user -> {
                StringBuilder sb = new StringBuilder();
                sb.append("**Your Linked Accounts**\n\n");
                
                if (user.linkedAccounts().isEmpty()) {
                    sb.append("You don't have any linked accounts yet.\n\n");
                    sb.append("To link an account:\n");
                    sb.append("1. Go to Settings > Link Account\n");
                    sb.append("2. Search for your bank\n");
                    sb.append("3. Sign in with your bank credentials\n");
                } else {
                    for (LinkedAccount acc : user.linkedAccounts()) {
                        sb.append(String.format("**%s - %s**\n", acc.bankName(), acc.accountType()));
                        sb.append(String.format("  Account ending in: ...%s\n", acc.lastFour()));
                        sb.append(String.format("  Last synced: %s\n\n", 
                            acc.lastSync().format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))));
                    }
                }
                
                return SupportResponse.withConfidence(query.sessionId(), sb.toString(),
                    SupportIntent.ACCOUNT, 0.9);
            })
            .orElse(createUserNotFoundResponse(query));
    }
    
    private SupportResponse handleProfileQuery(SupportQuery query, String userId) {
        return dataService.getUser(userId)
            .map(user -> {
                String text = String.format("""
                    **Your Profile**
                    
                    • **Name**: %s
                    • **Email**: %s
                    • **Phone**: %s
                    • **Member since**: %s
                    • **Plan**: %s
                    
                    To update your profile, go to Settings > Profile.
                    """,
                    user.name(),
                    user.email(),
                    user.phone(),
                    user.memberSince().format(DATE_FMT),
                    user.plan()
                );
                
                return SupportResponse.withConfidence(query.sessionId(), text,
                    SupportIntent.ACCOUNT, 0.9);
            })
            .orElse(createUserNotFoundResponse(query));
    }
    
    private SupportResponse handlePlanQuery(SupportQuery query, String userId) {
        return dataService.getUser(userId)
            .map(user -> {
                String text = String.format("""
                    **Your Subscription**
                    
                    You're currently on the **%s** plan.
                    
                    %s
                    
                    To manage your subscription, go to Settings > Subscription.
                    """,
                    user.plan(),
                    user.plan().equalsIgnoreCase("Premium") 
                        ? "You have access to all features including unlimited accounts and 24-month history."
                        : "Upgrade to Premium for unlimited accounts, advanced budgets, and priority support."
                );
                
                return SupportResponse.withConfidence(query.sessionId(), text,
                    SupportIntent.ACCOUNT, 0.85);
            })
            .orElse(createUserNotFoundResponse(query));
    }
    
    private SupportResponse handleCloseAccountQuery(SupportQuery query) {
        String text = """
            **Account Closure**
            
            We're sorry to see you go! Before closing your account:
            
            1. **Export your data** - Go to Settings > Export Data
            2. **Unlink all accounts** - Go to Settings > Linked Accounts
            3. **Cancel subscription** - If on Premium, cancel first
            
            To proceed with closure:
            Settings > Account > Close Account
            
            ⚠️ This action is permanent. Data will be deleted after 30 days.
            
            Would you like to speak with someone about any issues first?
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.ACCOUNT, 
            0.9, List.of("Export my data", "Speak to an agent", "Keep my account"), false);
    }
    
    private SupportResponse handleGenericAccountQuery(SupportQuery query, String userId) {
        return dataService.getUser(userId)
            .map(user -> {
                String text = String.format("""
                    **Account Overview**
                    
                    Hello %s! Here's a quick summary:
                    
                    • **Plan**: %s
                    • **Linked accounts**: %d
                    • **Member since**: %s
                    
                    What would you like to know about your account?
                    """,
                    user.name().split(" ")[0],
                    user.plan(),
                    user.linkedAccounts().size(),
                    user.memberSince().format(DATE_FMT)
                );
                
                return new SupportResponse(query.sessionId(), text, SupportIntent.ACCOUNT,
                    0.7, List.of("View balances", "View profile", "Linked accounts"), false);
            })
            .orElse(createUserNotFoundResponse(query));
    }
    
    private SupportResponse createUserNotFoundResponse(SupportQuery query) {
        return SupportResponse.simple(query.sessionId(),
            "I couldn't find your account information. Please ensure you're logged in.",
            SupportIntent.ACCOUNT);
    }
    
    private String formatCurrency(BigDecimal amount) {
        String prefix = amount.compareTo(BigDecimal.ZERO) < 0 ? "-$" : "$";
        return prefix + amount.abs().setScale(2, java.math.RoundingMode.HALF_UP);
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
