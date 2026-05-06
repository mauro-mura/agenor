package dev.jentic.examples.support.agents;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.examples.support.context.ConversationContext;
import dev.jentic.examples.support.context.ConversationContextManager;
import dev.jentic.examples.support.model.SupportIntent;
import dev.jentic.examples.support.model.SupportResponse;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Collaborative Router Agent that consults multiple agents in parallel
 * and synthesizes their responses into a comprehensive answer.
 * 
 * <p>Pattern: Collaborative Reasoning
 * <pre>
 * User Query
 *     │
 *     ▼
 * ┌────────────────────────────────────────────┐
 * │         CollaborativeRouterAgent           │
 * │                                            │
 * │  ┌─────────┐  ┌─────────┐  ┌─────────┐     │
 * │  │   FAQ   │  │ Account │  │ Context │     │
 * │  │  Agent  │  │  Agent  │  │  Agent  │     │
 * │  └────┬────┘  └────┬────┘  └────┬────┘     │
 * │       │            │            │          │
 * │       ▼            ▼            ▼          │
 * │  ┌─────────────────────────────────────┐   │
 * │  │         Response Synthesizer        │   │
 * │  └─────────────────────────────────────┘   │
 * └────────────────────────────────────────────┘
 *     │
 *     ▼
 * Synthesized Response
 * </pre>
 */

public class CollaborativeRouterAgent extends BaseAgent {
    
    private static final Logger log = LoggerFactory.getLogger(CollaborativeRouterAgent.class);
    
    private static final Duration CONSULTATION_TIMEOUT = Duration.ofSeconds(5);
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.3;
    
    private final DialogueCapability dialogue;
    private final ConversationContextManager contextManager;
    private final Map<String, AgentContribution> pendingContributions;
    
    // Agent roles and their expertise
    private static final Map<String, Set<SupportIntent>> AGENT_EXPERTISE = Map.of(
        "faq-agent", Set.of(SupportIntent.FAQ, SupportIntent.GENERAL),
        "account-agent", Set.of(SupportIntent.ACCOUNT_INFO, SupportIntent.BILLING),
        "transaction-agent", Set.of(SupportIntent.TRANSACTION_HISTORY, SupportIntent.BILLING),
        "security-agent", Set.of(SupportIntent.SECURITY, SupportIntent.PASSWORD_RESET),
        "budget-agent", Set.of(SupportIntent.BUDGET, SupportIntent.ACCOUNT_INFO)
    );
    
    public CollaborativeRouterAgent(ConversationContextManager contextManager) {
        this.dialogue = new DialogueCapability(this);
        this.contextManager = contextManager;
        this.pendingContributions = new ConcurrentHashMap<>();
    }
    
    @Override
    public String getAgentId() {
        return "collaborative-router";
    }
    
    @Override
    public String getAgentName() {
        return "Collaborative Router Agent";
    }
    
    @Override
    protected void onStart() {
        dialogue.initialize(getMessageDispatcher());

        // Subscribe to support queries
        getMessageDispatcher().subscribeTopic("support.query", MessageHandler.sync(this::handleQuery));

        // Subscribe to agent contributions
        getMessageDispatcher().subscribeTopic("agent.contribution", MessageHandler.sync(this::handleContribution));

        log.info("Collaborative Router Agent started");
    }

    @Override
    protected void onStop() {
        dialogue.shutdown();
    }
    
    /**
     * Handles incoming support query with collaborative reasoning.
     */
    private void handleQuery(Message msg) {
        String sessionId = msg.headers().getOrDefault("sessionId", UUID.randomUUID().toString());
        String queryText = msg.content().toString();
        
        log.info("[{}] Collaborative query: {}", sessionId, queryText);
        
        // Get or create context
        ConversationContext context = contextManager.getOrCreate(sessionId);
        context.addMessage("user", queryText);
        
        // Classify intent first
        SupportIntent primaryIntent = classifyIntent(queryText);
        
        // Determine which agents to consult based on query
        List<String> agentsToConsult = selectAgentsForQuery(queryText, primaryIntent);
        
        log.info("[{}] Consulting {} agents: {}", sessionId, agentsToConsult.size(), agentsToConsult);
        
        // Create consultation request
        ConsultationRequest request = new ConsultationRequest(
            sessionId,
            queryText,
            primaryIntent,
            context,
            msg.correlationId()
        );
        
        // Consult all agents in parallel
        consultAgentsInParallel(request, agentsToConsult)
            .thenAccept(contributions -> {
                // Synthesize responses
                SupportResponse synthesized = synthesizeResponses(request, contributions);
                
                // Update context
                context.addMessage("assistant", synthesized.text());
                context.setLastIntent(synthesized.intent());
                
                // Send final response
                sendResponse(synthesized, msg.correlationId());
            })
            .exceptionally(ex -> {
                log.error("[{}] Collaborative reasoning failed: {}", sessionId, ex.getMessage());
                sendErrorResponse(sessionId, ex.getMessage(), msg.correlationId());
                return null;
            });
    }
    
    /**
     * Consults multiple agents in parallel and collects their contributions.
     */
    private CompletableFuture<List<AgentContribution>> consultAgentsInParallel(
            ConsultationRequest request, List<String> agents) {
        
        List<CompletableFuture<AgentContribution>> futures = agents.stream()
            .map(agentId -> consultAgent(agentId, request))
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .filter(c -> c.confidence() >= MIN_CONFIDENCE_THRESHOLD)
                .collect(Collectors.toList()))
            .orTimeout(CONSULTATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                log.warn("Some consultations timed out, proceeding with available responses");
                return futures.stream()
                    .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            });
    }
    
    /**
     * Consults a single agent using dialogue protocol.
     */
    private CompletableFuture<AgentContribution> consultAgent(String agentId, ConsultationRequest request) {
        log.debug("[{}] Consulting {}", request.sessionId(), agentId);
        
        // Build query message for the agent
        DialogueMessage query = DialogueMessage.builder()
            .senderId(getAgentId())
            .receiverId(agentId)
            .performative(Performative.QUERY)
            .content(new AgentConsultation(
                request.sessionId(),
                request.queryText(),
                request.primaryIntent(),
                request.context().getRecentHistory(3).stream()
                    .map(turn -> turn.type() + ": " + turn.text())
                    .toList()
            ))
            .build();
        
        return dialogue.query(agentId, query.content(), CONSULTATION_TIMEOUT)
            .thenApply(response -> {
                if (response.performative() == Performative.INFORM) {
                    Object content = response.content();
                    if (content instanceof AgentContribution contribution) {
                        return contribution;
                    } else if (content instanceof SupportResponse sr) {
                        return new AgentContribution(
                            agentId,
                            sr.text(),
                            sr.confidence(),
                            sr.intent(),
                            extractInsights(sr)
                        );
                    }
                }
                return null;
            })
            .exceptionally(ex -> {
                log.debug("[{}] Agent {} did not respond: {}", 
                    request.sessionId(), agentId, ex.getMessage());
                return null;
            });
    }
    
    /**
     * Synthesizes multiple agent contributions into a unified response.
     */
    private SupportResponse synthesizeResponses(ConsultationRequest request, 
                                                 List<AgentContribution> contributions) {

        if (contributions.isEmpty()) {
            return createFallbackResponse(request);
        }

        log.info("[{}] Synthesizing {} contributions", request.sessionId(), contributions.size());

        // Sort by confidence
        contributions.sort(Comparator.comparing(AgentContribution::confidence).reversed());

        // Primary response from highest confidence agent
        AgentContribution primary = contributions.get(0);

        // Build synthesized response
        StringBuilder synthesized = new StringBuilder();
        synthesized.append(primary.responseText());

        // Add response texts from other high-confidence agents (not just insights)
        List<AgentContribution> secondary = contributions.stream()
                .skip(1)
                .filter(c -> c.confidence() > 0.5)
                .filter(c -> !c.responseText().isBlank())
                .toList();

        for (AgentContribution contrib : secondary) {
            synthesized.append("\n\n---\n\n");
            synthesized.append(contrib.responseText());
        }

        // Collect additional insights from all agents
        List<String> additionalInsights = contributions.stream()
                .flatMap(c -> c.insights().stream())
                .filter(i -> !i.startsWith("ACTION:"))
                .distinct()
                .limit(5)
                .toList();

        if (!additionalInsights.isEmpty()) {
            synthesized.append("\n\n**Summary:**\n");
            for (String insight : additionalInsights) {
                synthesized.append("• ").append(insight).append("\n");
            }
        }

        // Calculate combined confidence
        double avgConfidence = contributions.stream()
                .mapToDouble(AgentContribution::confidence)
                .average()
                .orElse(primary.confidence());

        // Collect suggested actions from all agents
        List<String> allActions = contributions.stream()
                .flatMap(c -> c.insights().stream())
                .filter(i -> i.startsWith("ACTION:"))
                .map(i -> i.substring(7).trim())
                .distinct()
                .limit(3)
                .toList();

        return new SupportResponse(
                synthesized.toString().trim(),
                primary.intent(),
                avgConfidence,
                allActions.isEmpty() ? List.of("Ask another question", "Speak to human") : allActions,
                false,
                Map.of(
                        "contributingAgents", contributions.stream()
                                .map(AgentContribution::agentId)
                                .toList(),
                        "synthesisMethod", "multi-response"
                )
        );
    }
    
    /**
     * Selects which agents to consult based on the query and intent.
     */
    private List<String> selectAgentsForQuery(String query, SupportIntent intent) {
        List<String> selected = new ArrayList<>();
        
        // Always consult FAQ for knowledge base
        selected.add("faq-agent");
        
        // Add specialists based on intent
        AGENT_EXPERTISE.forEach((agentId, expertise) -> {
            if (expertise.contains(intent) && !selected.contains(agentId)) {
                selected.add(agentId);
            }
        });
        
        // Add context-aware agents based on keywords
        String lowerQuery = query.toLowerCase();
        
        if (containsAny(lowerQuery, "balance", "account", "profile")) {
            addIfAbsent(selected, "account-agent");
        }
        if (containsAny(lowerQuery, "transaction", "payment", "history", "charge")) {
            addIfAbsent(selected, "transaction-agent");
        }
        if (containsAny(lowerQuery, "password", "security", "secure", "2fa", "login", "suspicious")) {
            addIfAbsent(selected, "security-agent");
        }
        if (containsAny(lowerQuery, "budget", "spending", "limit", "alert")) {
            addIfAbsent(selected, "budget-agent");
        }
        
        // Limit to max 4 agents to avoid too much overhead
        return selected.stream().limit(4).toList();
    }
    
    /**
     * Classifies the primary intent of the query.
     */
    private SupportIntent classifyIntent(String query) {
        String lower = query.toLowerCase();

        if (containsAny(lower, "password", "reset", "forgot", "login", "2fa", "security", "secure")) {
            return SupportIntent.SECURITY;
        }
        if (containsAny(lower, "balance", "account", "profile", "plan")) {
            return SupportIntent.ACCOUNT_INFO;
        }
        if (containsAny(lower, "transaction", "payment", "charge", "history", "statement")) {
            return SupportIntent.TRANSACTION_HISTORY;
        }
        if (containsAny(lower, "budget", "spending", "limit", "alert")) {
            return SupportIntent.BUDGET;
        }
        if (containsAny(lower, "human", "agent", "speak", "talk", "representative")) {
            return SupportIntent.ESCALATION;
        }
        
        return SupportIntent.FAQ;
    }
    
    private SupportResponse createFallbackResponse(ConsultationRequest request) {
        return new SupportResponse(
            "I apologize, but I couldn't gather enough information to answer your question. " +
            "Could you please rephrase your question or ask about something specific?",
            SupportIntent.GENERAL,
            0.3,
            List.of("Rephrase question", "Speak to human agent"),
            false,
            Map.of("reason", "no_contributions")
        );
    }
    
    private void handleContribution(Message msg) {
        // Handle async contributions if agents publish instead of reply
        if (msg.content() instanceof AgentContribution contribution) {
            String sessionId = msg.headers().getOrDefault("sessionId", "");
            pendingContributions.put(sessionId + ":" + contribution.agentId(), contribution);
        }
    }
    
    private void sendResponse(SupportResponse response, String correlationId) {
        Message responseMsg = Message.builder()
            .topic("support.response")
            .senderId(getAgentId())
            .correlationId(correlationId)
            .content(response)
            .build();
        getMessageDispatcher().publish(responseMsg);
    }
    
    private void sendErrorResponse(String sessionId, String error, String correlationId) {
        SupportResponse errorResponse = new SupportResponse(
            "I encountered an issue processing your request. Please try again or speak with a human agent.",
            SupportIntent.GENERAL,
            0.5,
            List.of("Try again", "Speak to human"),
            false,
            Map.of("error", error)
        );
        sendResponse(errorResponse, correlationId);
    }
    
    private List<String> extractInsights(SupportResponse response) {
        List<String> insights = new ArrayList<>();
        
        // Extract action items
        if (response.suggestedActions() != null) {
            response.suggestedActions().forEach(action -> 
                insights.add("ACTION: " + action));
        }
        
        // Could extract key facts from response text here
        // For now, just return actions
        return insights;
    }
    
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
    
    private void addIfAbsent(List<String> list, String item) {
        if (!list.contains(item)) {
            list.add(item);
        }
    }
    
    // ========== INNER CLASSES ==========
    
    /**
     * Request sent to agents for consultation.
     */
    public record ConsultationRequest(
        String sessionId,
        String queryText,
        SupportIntent primaryIntent,
        ConversationContext context,
        String correlationId
    ) {}
    
    /**
     * Query sent to individual agent.
     */
    public record AgentConsultation(
        String sessionId,
        String queryText,
        SupportIntent suggestedIntent,
        List<String> conversationHistory
    ) {}
    
    /**
     * Contribution from an agent.
     */
    public record AgentContribution(
        String agentId,
        String responseText,
        double confidence,
        SupportIntent intent,
        List<String> insights
    ) {}
}
