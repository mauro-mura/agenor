package dev.agenor.examples.support.context;

import dev.agenor.examples.support.model.SupportIntent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages conversation contexts across multiple sessions.
 * Handles context creation, retrieval, and expiration.
 */
public class ConversationContextManager {

    private static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(30);

    private final Map<String, ConversationContext> contexts = new ConcurrentHashMap<>();
    private final Duration contextExpiry;
    private final ScheduledExecutorService cleanupExecutor;

    public ConversationContextManager() {
        this(DEFAULT_EXPIRY);
    }

    public ConversationContextManager(Duration contextExpiry) {
        this.contextExpiry = contextExpiry;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "context-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Schedule periodic cleanup
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredContexts,
            5, 5, TimeUnit.MINUTES
        );
    }

    /**
     * Gets or creates a context for the given session.
     */
    public ConversationContext getOrCreate(String sessionId, String userId) {
        return contexts.computeIfAbsent(sessionId,
            id -> new ConversationContext(id, userId));
    }

    /**
     * Gets or creates a context using sessionId as userId.
     */
    public ConversationContext getOrCreate(String sessionId) {
        return getOrCreate(sessionId, sessionId);
    }

    /**
     * Gets an existing context if present.
     */
    public Optional<ConversationContext> get(String sessionId) {
        return Optional.ofNullable(contexts.get(sessionId));
    }

    /**
     * Records a user message and updates context.
     */
    public ConversationContext recordUserMessage(String sessionId, String userId,
            String text, SupportIntent intent, double sentimentScore) {

        ConversationContext ctx = getOrCreate(sessionId, userId);
        ctx.addUserTurn(text, intent);
        ctx.updateSentiment(sentimentScore);

        // Adjust frustration based on sentiment and patterns
        if (sentimentScore < -0.3) {
            ctx.incrementFrustration(2);
        } else if (sentimentScore < 0) {
            ctx.incrementFrustration(1);
        } else if (sentimentScore > 0.3) {
            ctx.decrementFrustration(1);
        }

        // Check for repeat questions
        if (ctx.isRepeatQuestion()) {
            ctx.incrementFrustration(2);
        }

        return ctx;
    }

    /**
     * Records an agent response.
     */
    public void recordAgentResponse(String sessionId, String agentId, String text) {
        get(sessionId).ifPresent(ctx -> ctx.addAgentTurn(agentId, text));
    }

    /**
     * Removes a context (e.g., after escalation or session end).
     */
    public void remove(String sessionId) {
        contexts.remove(sessionId);
    }

    /**
     * Gets total active sessions.
     */
    public int getActiveSessionCount() {
        return contexts.size();
    }

    /**
     * Gets contexts that should be offered escalation.
     */
    public Map<String, ConversationContext> getEscalationCandidates() {
        Map<String, ConversationContext> candidates = new ConcurrentHashMap<>();
        contexts.forEach((id, ctx) -> {
            if (ctx.shouldSuggestEscalation() && !ctx.isEscalationRequested()) {
                candidates.put(id, ctx);
            }
        });
        return candidates;
    }

    /**
     * Cleans up expired contexts.
     */
    private void cleanupExpiredContexts() {
        Instant cutoff = Instant.now().minus(contextExpiry);

        contexts.entrySet().removeIf(entry -> {
            ConversationContext ctx = entry.getValue();
            if (ctx.getHistory().isEmpty()) {
                return ctx.getStartTime().isBefore(cutoff);
            }
            // Use last turn time
            var lastTurn = ctx.getHistory().get(ctx.getHistory().size() - 1);
            return lastTurn.timestamp().isBefore(cutoff);
        });
    }

    /**
     * Shuts down the cleanup executor.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
