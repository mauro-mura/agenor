package dev.jentic.examples.support.agents;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.examples.support.context.ConversationContext;
import dev.jentic.examples.support.context.ConversationContextManager;
import dev.jentic.examples.support.model.SupportIntent;
import dev.jentic.examples.support.model.SupportQuery;
import dev.jentic.examples.support.model.SupportResponse;
import dev.jentic.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles escalation requests and handoff to human agents.
 * Manages queue, provides wait times, and offers alternatives.
 */
@JenticAgent(
    value = "escalation-agent",
    type = "handler",
    capabilities = {"escalation", "human-handoff"}
)
public class EscalationAgent extends BaseAgent {
    
    private static final Logger log = LoggerFactory.getLogger(EscalationAgent.class);
    
    // Simulated queue
    private final AtomicInteger queuePosition = new AtomicInteger(3);
    private final AtomicInteger activeAgents = new AtomicInteger(5);
    
    private final ConversationContextManager contextManager;
    
    public EscalationAgent(ConversationContextManager contextManager) {
        super("escalation-agent", "Escalation Handler");
        this.contextManager = contextManager;
    }
    
    @Override
    protected void onStart() {
        getMessageDispatcher().subscribeTopic("support.escalate", MessageHandler.sync(this::handleEscalationRequest));
        log.info("Escalation Agent started");
    }
    
    @Override
    protected void onStop() {
        log.info("Escalation Agent stopped");
    }
    
    private void handleEscalationRequest(Message message) {
        SupportQuery query = extractQuery(message);
        String queryText = query.text().toLowerCase();
        String sessionId = query.sessionId();
        
        log.info("Escalation request from session {}: '{}'", sessionId, queryText);
        
        // Get conversation context
        ConversationContext context = contextManager.get(sessionId).orElse(null);
        
        SupportResponse response;
        
        if (containsAny(queryText, "cancel", "nevermind", "never mind", "go back")) {
            response = handleCancelEscalation(query);
        } else if (containsAny(queryText, "callback", "call me", "phone")) {
            response = handleCallbackRequest(query, context);
        } else if (containsAny(queryText, "email", "write", "message")) {
            response = handleEmailRequest(query, context);
        } else if (containsAny(queryText, "wait time", "how long", "queue")) {
            response = handleQueueStatusQuery(query);
        } else {
            response = handleEscalationConfirmation(query, context);
        }
        
        sendResponse(message, response);
    }
    
    private SupportResponse handleEscalationConfirmation(SupportQuery query, ConversationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Connect with Support Team**\n\n");
        
        // Check business hours (simulated: 9 AM - 6 PM)
        LocalTime now = LocalTime.now();
        boolean inBusinessHours = now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(18, 0));
        
        if (inBusinessHours) {
            int position = queuePosition.incrementAndGet();
            int estimatedWait = position * 3; // 3 minutes per person
            
            sb.append("🟢 **Live agents available**\n\n");
            sb.append(String.format("• Queue position: **#%d**\n", position));
            sb.append(String.format("• Estimated wait: **~%d minutes**\n", estimatedWait));
            sb.append(String.format("• Agents online: %d\n\n", activeAgents.get()));
            
            // Add context summary if available
            if (context != null) {
                sb.append("I'll share the following with the agent:\n");
                sb.append(String.format("• Topic: %s\n", 
                    context.getCurrentIntent() != null ? context.getCurrentIntent().description() : "General inquiry"));
                sb.append(String.format("• Conversation turns: %d\n", context.getTurnCount()));
                if (context.getFrustrationLevel() >= 5) {
                    sb.append("• 🔴 Priority: High (flagged for immediate attention)\n");
                }
                sb.append("\n");
                
                // Mark escalation in context
                context.requestEscalation("User requested human agent");
            }
            
            sb.append("""
                **While you wait:**
                • Stay in this chat - an agent will join
                • You can continue describing your issue
                • Type 'cancel' to leave the queue
                """);
        } else {
            sb.append("🔴 **Outside business hours**\n\n");
            sb.append(String.format("Our live support is available **9 AM - 6 PM** (your local time).\n"));
            sb.append(String.format("Current time: %s\n\n", now.format(DateTimeFormatter.ofPattern("h:mm a"))));
            
            sb.append("""
                **Alternative options:**
                
                📞 **Request a callback**
                We'll call you when agents are available
                
                📧 **Send an email**
                support@financecloud.com
                Response within 24 hours
                
                📚 **Browse help articles**
                Many issues can be resolved in our Help Center
                """);
        }
        
        List<String> actions = inBusinessHours
            ? List.of("Stay in queue", "Request callback instead", "Cancel")
            : List.of("Request callback", "Send email", "Browse help");
        
        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.ESCALATE,
            1.0, actions, true);
    }
    
    private SupportResponse handleCallbackRequest(SupportQuery query, ConversationContext context) {
        String text = """
            **Request a Callback**
            
            We'll call you back as soon as an agent is available.
            
            📱 **Your registered number**: +1-555-0123
            
            **Preferred callback time:**
            • Next available (recommended)
            • Morning (9 AM - 12 PM)
            • Afternoon (12 PM - 3 PM)
            • Evening (3 PM - 6 PM)
            
            ✓ **Callback requested!**
            
            You'll receive an SMS confirmation shortly.
            Expected callback: within **2 hours** during business hours.
            
            _You can close this chat. We'll reach out to you._
            """;
        
        // Record in context
        if (context != null) {
            context.requestEscalation("Callback requested");
            context.setAttribute("callbackRequested", true);
        }
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.ESCALATE,
            1.0, List.of("Change phone number", "Cancel callback", "Continue chatting"), true);
    }
    
    private SupportResponse handleEmailRequest(SupportQuery query, ConversationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Email Support**\n\n");
        
        sb.append("📧 **Send your inquiry to:**\n");
        sb.append("support@financecloud.com\n\n");
        
        sb.append("**For faster resolution, include:**\n");
        sb.append("• Your registered email address\n");
        sb.append("• Brief description of the issue\n");
        sb.append("• Screenshots if applicable\n");
        sb.append("• Transaction ID (if relevant)\n\n");
        
        // Generate a case summary if context available
        if (context != null && context.getTurnCount() > 0) {
            sb.append("**I can prepare a summary for you:**\n\n");
            sb.append("```\n");
            sb.append("Subject: Support Request - ");
            sb.append(context.getCurrentIntent() != null 
                ? context.getCurrentIntent().description() 
                : "General Inquiry");
            sb.append("\n\n");
            sb.append("Issue discussed in chat:\n");
            
            // Add recent conversation summary
            context.getRecentHistory(4).stream()
                .filter(t -> t.type() == ConversationContext.TurnType.USER)
                .limit(2)
                .forEach(t -> sb.append("- ").append(truncate(t.text(), 100)).append("\n"));
            
            sb.append("```\n\n");
            sb.append("_Copy and paste this into your email._\n\n");
        }
        
        sb.append("⏱️ **Response time**: Within 24 hours (usually faster)\n");
        
        return new SupportResponse(query.sessionId(), sb.toString(), SupportIntent.ESCALATE,
            0.95, List.of("Copy summary", "Request callback instead", "Back to chat"), true);
    }
    
    private SupportResponse handleQueueStatusQuery(SupportQuery query) {
        int position = queuePosition.get();
        int estimatedWait = position * 3;
        
        String text = String.format("""
            **Queue Status**
            
            • Your position: **#%d**
            • Estimated wait: **~%d minutes**
            • Agents available: %d
            
            **Tips while waiting:**
            • Keep this window open
            • You'll hear a sound when connected
            • Feel free to add more details to your issue
            
            Need to leave? Choose an alternative:
            """, position, estimatedWait, activeAgents.get());
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.ESCALATE,
            1.0, List.of("Stay in queue", "Request callback", "Cancel"), false);
    }
    
    private SupportResponse handleCancelEscalation(SupportQuery query) {
        queuePosition.decrementAndGet();
        
        String text = """
            **Escalation Cancelled**
            
            You've left the queue. No worries!
            
            🤖 I'm still here to help. What would you like to do?
            
            • Continue with automated support
            • Try a different question
            • Browse help articles
            
            _You can always request a human agent again._
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.FAQ,
            1.0, List.of("Ask another question", "View help articles", "Exit"), false);
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
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
