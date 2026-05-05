package dev.jentic.examples.support.agents;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.examples.support.agents.CollaborativeRouterAgent.AgentConsultation;
import dev.jentic.examples.support.agents.CollaborativeRouterAgent.AgentContribution;
import dev.jentic.core.knowledge.KnowledgeDocument;
import dev.jentic.core.knowledge.KnowledgeStore;
import dev.jentic.runtime.knowledge.QueryExpander;
import dev.jentic.examples.support.llm.LLMResponseGenerator;
import dev.jentic.examples.support.model.SupportIntent;
import dev.jentic.examples.support.model.SupportQuery;
import dev.jentic.examples.support.model.SupportResponse;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.dialogue.DialogueCapability;
import dev.jentic.core.dialogue.DialogueHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles FAQ queries using the knowledge base.
 * Supports query expansion, hybrid search, LLM-enhanced responses,
 * and collaborative reasoning via DialogueCapability.
 */
@JenticAgent(
    value = "faq-agent",
    type = "handler",
    capabilities = {"knowledge-retrieval", "faq", "rag", "hybrid-search", "consultable"}
)
public class FAQAgent extends BaseAgent implements ConsultableAgent {
    
    private static final Logger log = LoggerFactory.getLogger(FAQAgent.class);
    private static final int TOP_K = 3;
    private static final double MIN_CONFIDENCE = 0.15;
    
    private final KnowledgeStore<SupportIntent> knowledgeStore;
    private final LLMResponseGenerator llmGenerator;
    private final QueryExpander queryExpander;
    private final DialogueCapability dialogue;
    
    /**
     * Constructor without LLM or query expansion.
     */
    public FAQAgent(KnowledgeStore<SupportIntent> knowledgeStore) {
        this(knowledgeStore, null, null);
    }
    
    /**
     * Constructor with LLM support.
     */
    public FAQAgent(KnowledgeStore<SupportIntent> knowledgeStore, LLMResponseGenerator llmGenerator) {
        this(knowledgeStore, llmGenerator, null);
    }
    
    /**
     * Full constructor with all features.
     */
    public FAQAgent(KnowledgeStore<SupportIntent> knowledgeStore, LLMResponseGenerator llmGenerator,
            QueryExpander queryExpander) {
        super("faq-agent", "FAQ Handler");
        this.knowledgeStore = knowledgeStore;
        this.llmGenerator = llmGenerator;
        this.queryExpander = queryExpander;
        this.dialogue = new DialogueCapability(this);
    }
    
    @Override
    protected void onStart() {
        // Initialize dialogue capability for collaborative reasoning
        dialogue.initialize(getMessageDispatcher());

        // Subscribe to FAQ topic
        getMessageDispatcher().subscribeTopic("support.faq", MessageHandler.sync(this::handleFAQQuery));

        // Also handle unclassified queries
        getMessageDispatcher().subscribeTopic("support.unknown", MessageHandler.sync(this::handleFAQQuery));
        
        String features = buildFeatureString();
        log.info("FAQ Agent started with {} documents ({})", knowledgeStore.size(), features);
    }
    
    private String buildFeatureString() {
        StringBuilder sb = new StringBuilder();
        if (llmGenerator != null && llmGenerator.isLLMEnabled()) {
            sb.append("LLM");
        } else {
            sb.append("template");
        }
        if (queryExpander != null) {
            sb.append(" + query expansion");
        }
        sb.append(" + dialogue");
        return sb.toString();
    }
    
    @Override
    protected void onStop() {
        dialogue.shutdown();
        log.info("FAQ Agent stopped");
    }
    
    // ========== CONSULTABLE AGENT IMPLEMENTATION ==========
    
    @Override
    public List<SupportIntent> getExpertise() {
        return List.of(SupportIntent.FAQ, SupportIntent.GENERAL);
    }
    
    /**
     * Handles consultation requests from CollaborativeRouterAgent.
     */
    @DialogueHandler(performatives = {Performative.QUERY})
    public void handleConsultation(DialogueMessage msg) {
        if (msg.content() instanceof AgentConsultation consultation) {
            log.debug("Received consultation request: {}", consultation.queryText());
            AgentContribution contribution = consult(consultation);
            dialogue.reply(msg, Performative.INFORM, contribution);
        } else {
            // Handle as regular query
            dialogue.reply(msg, Performative.REFUSE, "Expected AgentConsultation");
        }
    }
    
    @Override
    public AgentContribution consult(AgentConsultation consultation) {
        String queryText = consultation.queryText();
        
        // Handle greetings
        if (isGreeting(queryText)) {
            return new AgentContribution(
                getAgentId(),
                "Welcome to FinanceCloud Support! I can help with account, transactions, security, and budgets.",
                0.9,
                SupportIntent.FAQ,
                List.of("ACTION: Show help menu")
            );
        }
        
        // Expand query if available
        String searchQuery = queryText;
        if (queryExpander != null) {
            String expanded = queryExpander.expand(queryText);
            if (!expanded.equals(queryText)) {
                searchQuery = expanded;
            }
        }
        
        // Search knowledge base
        List<KnowledgeDocument<SupportIntent>> matches = knowledgeStore.search(searchQuery, TOP_K);
        
        if (matches.isEmpty()) {
            return cannotContribute("No relevant FAQ articles found for: " + queryText);
        }
        
        KnowledgeDocument<SupportIntent> bestMatch = matches.get(0);
        double confidence = bestMatch.relevanceScore(queryText);
        
        if (confidence < MIN_CONFIDENCE) {
            return new AgentContribution(
                getAgentId(),
                "Possible topics: " + matches.stream()
                    .limit(3)
                    .map(KnowledgeDocument::title)
                    .toList(),
                0.25,
                SupportIntent.FAQ,
                List.of("ACTION: Clarify question")
            );
        }
        
        // Build response text
        String responseText;
        if (llmGenerator != null && llmGenerator.isLLMEnabled()) {
            try {
                responseText = llmGenerator.generate(queryText, matches, SupportIntent.FAQ);
            } catch (Exception e) {
                responseText = createTemplateResponse(bestMatch, matches);
            }
        } else {
            responseText = createTemplateResponse(bestMatch, matches);
        }
        
        // Extract insights from matched documents
        List<String> insights = new ArrayList<>();
        insights.add("SOURCE: " + bestMatch.title());
        if (matches.size() > 1) {
            insights.add("RELATED: " + matches.get(1).title());
        }
        insights.add("ACTION: Ask follow-up question");
        
        return new AgentContribution(
            getAgentId(),
            responseText,
            confidence,
            SupportIntent.FAQ,
            insights
        );
    }
    
    /**
     * Handles incoming FAQ queries.
     */
    private void handleFAQQuery(Message message) {
        SupportQuery query = extractQuery(message);
        String queryText = query.text();
        
        log.debug("FAQ query: '{}'", queryText);
        
        // Handle greetings and short queries
        if (isGreeting(queryText)) {
            sendResponse(message, createWelcomeResponse(query));
            return;
        }
        
        // Expand query with synonyms if expander available
        String searchQuery = queryText;
        if (queryExpander != null) {
            String expanded = queryExpander.expand(queryText);
            if (!expanded.equals(queryText)) {
                searchQuery = expanded;
                log.debug("Query expanded: '{}' -> '{}'", queryText, searchQuery);
            }
        }
        
        // Search knowledge base with expanded query
        List<KnowledgeDocument<SupportIntent>> matches = knowledgeStore.search(searchQuery, TOP_K);
        
        SupportResponse response;
        if (matches.isEmpty()) {
            response = createNoMatchResponse(query);
        } else {
            KnowledgeDocument<SupportIntent> bestMatch = matches.get(0);
            double confidence = bestMatch.relevanceScore(queryText);
            
            if (confidence < MIN_CONFIDENCE) {
                response = createLowConfidenceResponse(query, matches);
            } else {
                response = createResponse(query, bestMatch, confidence, matches);
            }
        }
        
        sendResponse(message, response);
    }
    
    /**
     * Checks if query is a greeting or very short.
     */
    private boolean isGreeting(String text) {
        if (text == null) return true;
        String lower = text.toLowerCase().trim();
        if (lower.length() < 4) return true;
        return lower.matches("^(hi|hey|hello|help|yo|sup|ciao|hola|ola)\\s*[!?.]*$");
    }
    
    /**
     * Creates a welcome response for greetings.
     */
    private SupportResponse createWelcomeResponse(SupportQuery query) {
        String text = """
            👋 **Welcome to FinanceCloud Support!**
            
            I'm here to help you with:
            • **Account** - Balance, profile, linked accounts
            • **Transactions** - History, exports, disputes
            • **Security** - Password, 2FA, devices
            • **Budgets** - Create and track spending limits
            
            How can I assist you today?
            """;
        
        return new SupportResponse(query.sessionId(), text, SupportIntent.FAQ,
            1.0, List.of("View my balance", "Reset password", "Recent transactions"), false);
    }
    
    /**
     * Creates response from matched document.
     * Uses LLM if available, otherwise falls back to template.
     */
    private SupportResponse createResponse(SupportQuery query, KnowledgeDocument<SupportIntent> match,
            double confidence, List<KnowledgeDocument<SupportIntent>> alternatives) {
        
        String text;
        
        // Try LLM-enhanced response
        if (llmGenerator != null && llmGenerator.isLLMEnabled()) {
            try {
                text = llmGenerator.generate(query.text(), alternatives, SupportIntent.FAQ);
                log.debug("Generated LLM response for query: '{}'", query.text());
            } catch (Exception e) {
                log.warn("LLM generation failed, using template: {}", e.getMessage());
                text = createTemplateResponse(match, alternatives);
            }
        } else {
            text = createTemplateResponse(match, alternatives);
        }
        
        List<String> actions = List.of(
            "Was this helpful? (yes/no)",
            "Ask another question"
        );
        
        return new SupportResponse(
            query.sessionId(),
            text,
            SupportIntent.FAQ,
            confidence,
            actions,
            false
        );
    }
    
    /**
     * Template-based response from document.
     */
    private String createTemplateResponse(KnowledgeDocument<SupportIntent> match, List<KnowledgeDocument<SupportIntent>> alternatives) {
        StringBuilder text = new StringBuilder();
        text.append("**").append(match.title()).append("**\n\n");
        text.append(match.content());
        
        // Add related topics if available
        if (alternatives.size() > 1) {
            text.append("\n\n---\n*Related topics:* ");
            alternatives.stream()
                .skip(1)
                .limit(2)
                .forEach(doc -> text.append(doc.title()).append(" | "));
            // Remove trailing separator
            text.setLength(text.length() - 3);
        }
        
        return text.toString();
    }
    
    /**
     * Response when no documents match.
     */
    private SupportResponse createNoMatchResponse(SupportQuery query) {
        String text;
        
        // Try LLM for no-match response
        if (llmGenerator != null && llmGenerator.isLLMEnabled()) {
            try {
                text = llmGenerator.generate(query.text(), List.of(), SupportIntent.FAQ);
            } catch (Exception e) {
                text = createNoMatchTemplate();
            }
        } else {
            text = createNoMatchTemplate();
        }
        
        return new SupportResponse(
            query.sessionId(),
            text,
            SupportIntent.FAQ,
            0.0,
            List.of("Try a different question", "Speak to an agent"),
            false
        );
    }
    
    private String createNoMatchTemplate() {
        return """
            I couldn't find specific information about your question.
            
            Here's what I can help with:
            - Account setup and management
            - Security settings (password, 2FA)
            - Transaction history and exports
            - Budget creation and alerts
            - Subscription and pricing
            
            Could you rephrase your question, or type 'agent' to speak with a human?
            """;
    }
    
    /**
     * Response when matches have low confidence.
     */
    private SupportResponse createLowConfidenceResponse(SupportQuery query,
            List<KnowledgeDocument<SupportIntent>> matches) {
        
        StringBuilder text = new StringBuilder();
        text.append("I'm not sure I understood your question correctly. ");
        text.append("Did you mean one of these topics?\n\n");
        
        for (int i = 0; i < Math.min(matches.size(), 3); i++) {
            text.append("- ").append(matches.get(i).title()).append("\n");
        }
        
        text.append("\nPlease select a topic or rephrase your question.");
        
        return new SupportResponse(
            query.sessionId(),
            text.toString(),
            SupportIntent.FAQ,
            0.3,
            matches.stream().map(KnowledgeDocument::title).limit(3).toList(),
            false
        );
    }
    
    /**
     * Sends response back to the user.
     */
    private void sendResponse(Message originalMessage, SupportResponse response) {
        Message responseMsg = Message.builder()
            .topic("support.response")
            .senderId(getAgentId())
            .receiverId(originalMessage.senderId())
            .correlationId(response.sessionId())
            .content(response)
            .header("intent", response.handledBy().code())
            .header("confidence", String.valueOf(response.confidence()))
            .build();
        
        getMessageDispatcher().publish(responseMsg.topic(), responseMsg);
        log.debug("Sent response for session {} with confidence {}",
            response.sessionId(), response.confidence());
    }
    
    private SupportQuery extractQuery(Message message) {
        Object content = message.content();
        if (content instanceof SupportQuery q) {
            return q;
        }
        String text = content instanceof String s ? s : content.toString();
        return new SupportQuery(
            message.correlationId() != null ? message.correlationId() : message.id(),
            message.senderId(),
            text
        );
    }
}