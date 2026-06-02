package dev.agenor.examples.support.agents;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.examples.support.context.ConversationContext;
import dev.agenor.examples.support.context.ConversationContextManager;
import dev.agenor.examples.support.context.SentimentAnalyzer;
import dev.agenor.examples.support.context.SentimentAnalyzer.SentimentResult;
import dev.agenor.examples.support.model.SupportIntent;
import dev.agenor.examples.support.model.SupportQuery;
import dev.agenor.examples.support.model.SupportResponse;
import dev.agenor.examples.support.production.*;
import dev.agenor.examples.support.production.LanguageDetector.Language;
import dev.agenor.examples.support.production.RateLimiter.RateLimitResult;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes incoming support queries to the appropriate specialized agent.
 * Uses keyword-based intent classification with sentiment analysis
 * and conversation context tracking.
 *
 * Production features:
 * - Rate limiting per session
 * - Language detection
 * - Query/response persistence
 * - Analytics tracking
 */
@JenticAgent(
    value = "router-agent",
    type = "router",
    capabilities = {"intent-classification", "routing", "sentiment-analysis", "rate-limiting"}
)
public class RouterAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    private final ConversationContextManager contextManager;
    private final SentimentAnalyzer sentimentAnalyzer;

    // Production services (optional)
    private final ConversationRepository conversationRepo;
    private final AnalyticsService analytics;
    private final LanguageDetector languageDetector;
    private final LocalizationService localization;
    private final RateLimiter rateLimiter;

    // Track query start times for response time calculation
    private final Map<String, QueryTracker> pendingQueries = new ConcurrentHashMap<>();

    // Store subscriptions
    private Subscription querySubscription;
    private Subscription responseSubscription;

    // Keyword mappings for intent detection
    private static final Map<SupportIntent, Set<String>> INTENT_KEYWORDS = Map.of(
        SupportIntent.ACCOUNT, Set.of(
            "account", "profile", "settings", "register", "signup", "close account",
            "delete account", "update", "personal info", "balance", "linked"
        ),
        SupportIntent.SECURITY, Set.of(
            "password", "reset", "2fa", "two factor", "login", "locked", "unauthorized",
            "security", "pin", "biometric", "device", "logout", "trusted"
        ),
        SupportIntent.TRANSACTION, Set.of(
            "transaction", "payment", "transfer", "history", "activity", "export",
            "statement", "dispute", "charge", "refund", "purchase", "pending"
        ),
        SupportIntent.BUDGET, Set.of(
            "budget", "spending", "limit", "alert", "category", "goal", "track"
        ),
        SupportIntent.ESCALATE, Set.of(
            "human", "agent", "speak to", "talk to", "representative", "escalate",
            "complaint", "manager", "real person", "supervisor"
        )
    );

    /**
     * Constructor with only required dependencies.
     */
    public RouterAgent(ConversationContextManager contextManager) {
        this(contextManager, null, null, null, null, null);
    }

    /**
     * Full constructor with production services.
     */
    public RouterAgent(ConversationContextManager contextManager,
                       ConversationRepository conversationRepo,
                       AnalyticsService analytics,
                       LanguageDetector languageDetector,
                       LocalizationService localization,
                       RateLimiter rateLimiter) {
        super("router-agent", "Support Router");
        this.contextManager = contextManager;
        this.sentimentAnalyzer = new SentimentAnalyzer();
        this.conversationRepo = conversationRepo;
        this.analytics = analytics;
        this.languageDetector = languageDetector;
        this.localization = localization;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void onStart() {
        // Subscribe to incoming support requests
        querySubscription = getMessageDispatcher().subscribeTopic("support.query", MessageHandler.sync(this::handleQuery));

        // Subscribe to responses for metrics tracking
        responseSubscription = getMessageDispatcher().subscribeTopic("support.response", MessageHandler.sync(this::trackResponse));

        log.info("Router Agent started - listening on support.query and support.response");
    }

    @Override
    protected void onStop() {
        if (querySubscription != null) {
            querySubscription.unsubscribe();
        }
        if (responseSubscription != null) {
            responseSubscription.unsubscribe();
        }
        log.info("Router Agent stopped");
    }

    /**
     * Handles incoming support queries from the main topic.
     */
    private void handleQuery(Message message) {
        String queryText = extractQueryText(message);
        String sessionId = message.correlationId() != null ? message.correlationId() : message.id();
        String userId = message.senderId();

        log.debug("Received query: '{}' from session {}", queryText, sessionId);

        // Check rate limit
        if (rateLimiter != null) {
            RateLimitResult limitResult = rateLimiter.checkLimit(sessionId);
            if (!limitResult.isAllowed()) {
                log.warn("Rate limit exceeded for session {}", sessionId);
                sendRateLimitResponse(sessionId, limitResult);
                return;
            }
        }

        // Track query start time for response time calculation
        Instant queryStart = Instant.now();

        // Detect language
        Language detectedLanguage = Language.ENGLISH;
        if (languageDetector != null) {
            var langResult = languageDetector.detect(queryText);
            detectedLanguage = langResult.language();
            log.debug("Detected language: {} (confidence: {})",
                detectedLanguage.getCode(), langResult.confidence());
        }

        // Analyze sentiment
        SentimentResult sentiment = sentimentAnalyzer.analyze(queryText);
        log.debug("Sentiment: {} (score: {}, frustration: {})",
            sentiment.getSentimentLabel(), sentiment.score(), sentiment.frustrationLevel());

        // Classify intent
        IntentResult result = classifyIntent(queryText);

        // Override to escalation if sentiment strongly suggests it
        if (sentiment.suggestEscalation() && result.intent != SupportIntent.ESCALATE) {
            log.info("Sentiment suggests escalation, overriding intent from {} to ESCALATE", result.intent);
            result = new IntentResult(SupportIntent.ESCALATE, 0.9);
        }

        log.info("Classified intent: {} (confidence: {})", result.intent, result.confidence);

        // Track pending query for response time calculation
        pendingQueries.put(sessionId, new QueryTracker(
            queryStart, queryText, result.intent, result.confidence, detectedLanguage));

        // Update conversation context
        ConversationContext context = contextManager.recordUserMessage(
            sessionId, userId, queryText, result.intent, sentiment.score());

        // Check if we should proactively suggest escalation
        if (context.shouldSuggestEscalation() && result.intent != SupportIntent.ESCALATE) {
            // Send a suggestion but still route to the appropriate agent
            sendEscalationSuggestion(sessionId, context);
        }

        // Create enriched query
        SupportQuery query = new SupportQuery(sessionId, userId, queryText)
            .withIntent(result.intent)
            .withContext(Map.of(
                "sentiment", sentiment.getSentimentLabel(),
                "sentimentScore", sentiment.score(),
                "frustrationLevel", context.getFrustrationLevel(),
                "turnCount", context.getTurnCount(),
                "language", detectedLanguage.getCode()
            ));

        // Route to appropriate agent
        String targetTopic = getTargetTopic(result.intent);

        Message routedMessage = Message.builder()
            .topic(targetTopic)
            .senderId(getAgentId())
            .correlationId(sessionId)
            .content(query)
            .header("originalQuery", queryText)
            .header("intent", result.intent.code())
            .header("confidence", String.valueOf(result.confidence))
            .header("sentiment", sentiment.getSentimentLabel())
            .header("frustration", String.valueOf(context.getFrustrationLevel()))
            .header("language", detectedLanguage.getCode())
            .build();

        getMessageDispatcher().publish(routedMessage);
        log.debug("Routed to topic: {}", targetTopic);
    }

    /**
     * Tracks response for metrics and persistence.
     */
    private void trackResponse(Message message) {
        Object content = message.content();
        if (!(content instanceof SupportResponse response)) {
            return;
        }

        String sessionId = message.correlationId();
        if (sessionId == null) {
            return;
        }

        // Get tracked query info
        QueryTracker tracker = pendingQueries.remove(sessionId);
        if (tracker == null) {
            return;
        }

        // Calculate response time
        long responseTimeMs = java.time.Duration.between(tracker.startTime, Instant.now()).toMillis();

        // Record analytics
        if (analytics != null) {
            analytics.recordQuery(sessionId, tracker.intent, responseTimeMs, tracker.confidence);

            // Track escalation
            if (tracker.intent == SupportIntent.ESCALATE) {
                analytics.recordEscalation(sessionId, "User request or sentiment");
            }
        }

        // Persist conversation turn
        if (conversationRepo != null) {
            SupportQuery query = new SupportQuery(sessionId, "user", tracker.queryText)
                .withIntent(tracker.intent);
            conversationRepo.saveTurn(sessionId, query, response, responseTimeMs);
        }

        log.debug("Tracked response for session {}: {}ms", sessionId, responseTimeMs);
    }

    /**
     * Sends rate limit exceeded response.
     */
    private void sendRateLimitResponse(String sessionId, RateLimitResult result) {
        String text = String.format(
            "⏱️ **Rate Limit Exceeded**\n\n" +
            "You've sent too many requests. Please wait %d seconds before trying again.",
            result.getRetryAfterSeconds());

        SupportResponse response = new SupportResponse(
            sessionId, text, SupportIntent.FAQ, 1.0,
            List.of("Wait and retry"), false
        );

        Message responseMsg = Message.builder()
            .topic("support.response")
            .senderId(getAgentId())
            .correlationId(sessionId)
            .content(response)
            .build();

        getMessageDispatcher().publish(responseMsg);
    }

    /**
     * Tracks pending query info for response time calculation.
     */
    private record QueryTracker(
        Instant startTime,
        String queryText,
        SupportIntent intent,
        double confidence,
        Language language
    ) {}

    /**
     * Sends a proactive escalation suggestion to the user.
     */
    private void sendEscalationSuggestion(String sessionId, ConversationContext context) {
        String text = """
            💡 **Would you like to speak with a support agent?**

            I notice you might be having difficulty getting the help you need.
            I can connect you with a human agent who may be able to assist better.

            Just type "speak to agent" if you'd like that, or continue with your question.
            """;

        SupportResponse suggestion = new SupportResponse(
            sessionId, text, SupportIntent.FAQ, 0.8,
            List.of("Speak to agent", "Continue with bot"), false
        );

        Message suggestionMsg = Message.builder()
            .topic("support.response")
            .senderId(getAgentId())
            .correlationId(sessionId)
            .content(suggestion)
            .header("type", "escalation-suggestion")
            .build();

        getMessageDispatcher().publish(suggestionMsg);
    }

    /**
     * Classifies the intent of a query using keyword matching.
     */
    private IntentResult classifyIntent(String query) {
        if (query == null || query.isBlank()) {
            return new IntentResult(SupportIntent.UNKNOWN, 0.0);
        }

        String lowerQuery = query.toLowerCase();

        SupportIntent bestIntent = SupportIntent.FAQ;
        int bestScore = 0;

        for (var entry : INTENT_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lowerQuery.contains(keyword)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIntent = entry.getKey();
            }
        }

        // Calculate confidence
        double confidence = bestScore > 0 ? Math.min(1.0, bestScore * 0.3 + 0.4) : 0.3;

        return new IntentResult(bestIntent, confidence);
    }

    /**
     * Returns the target topic for an intent.
     */
    private String getTargetTopic(SupportIntent intent) {
        return switch (intent) {
            case ACCOUNT -> "support.account";
            case SECURITY -> "support.security";
            case TRANSACTION -> "support.transaction";
            case BUDGET -> "support.budget";
            case ESCALATE -> "support.escalate";
            default -> "support.faq";
        };
    }

    private String extractQueryText(Message message) {
        Object content = message.content();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof SupportQuery q) {
            return q.text();
        }
        return content != null ? content.toString() : "";
    }

    private record IntentResult(SupportIntent intent, double confidence) {}
}
