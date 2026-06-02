package dev.agenor.examples.support.context;

import dev.agenor.examples.support.model.SupportIntent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks conversation context across multiple turns.
 * Maintains history, sentiment, and escalation state per session.
 */
public class ConversationContext {

    private final String sessionId;
    private final String userId;
    private final Instant startTime;
    private final List<Turn> history;
    private final Map<String, Object> attributes;

    private SupportIntent currentIntent;
    private double sentimentScore; // -1.0 (negative) to 1.0 (positive)
    private int frustrationLevel; // 0-10
    private boolean escalationRequested;
    private String escalationReason;

    public ConversationContext(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.startTime = Instant.now();
        this.history = Collections.synchronizedList(new ArrayList<>());
        this.attributes = new ConcurrentHashMap<>();
        this.sentimentScore = 0.5; // Neutral
        this.frustrationLevel = 0;
        this.escalationRequested = false;
    }

    // ========== TURN MANAGEMENT ==========

    public void addUserTurn(String text, SupportIntent intent) {
        history.add(new Turn(TurnType.USER, text, intent, Instant.now()));
        this.currentIntent = intent;
    }

    public void addAgentTurn(String agentId, String text) {
        history.add(new Turn(TurnType.AGENT, text, currentIntent, Instant.now(), agentId));
    }

    /**
     * Generic method to add a message (user or assistant).
     */
    public void addMessage(String role, String text) {
        if ("user".equalsIgnoreCase(role)) {
            addUserTurn(text, currentIntent != null ? currentIntent : SupportIntent.UNKNOWN);
        } else {
            addAgentTurn(role, text);
        }
    }

    public List<Turn> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public List<Turn> getRecentHistory(int maxTurns) {
        int start = Math.max(0, history.size() - maxTurns);
        return history.subList(start, history.size());
    }

    public int getTurnCount() {
        return history.size();
    }

    // ========== SENTIMENT & FRUSTRATION ==========

    public void updateSentiment(double score) {
        this.sentimentScore = Math.max(-1.0, Math.min(1.0, score));
    }

    public void incrementFrustration(int amount) {
        this.frustrationLevel = Math.min(10, frustrationLevel + amount);
    }

    public void decrementFrustration(int amount) {
        this.frustrationLevel = Math.max(0, frustrationLevel - amount);
    }

    public double getSentimentScore() {
        return sentimentScore;
    }

    public int getFrustrationLevel() {
        return frustrationLevel;
    }

    public boolean isUserFrustrated() {
        return frustrationLevel >= 5 || sentimentScore < -0.3;
    }

    public boolean shouldSuggestEscalation() {
        return frustrationLevel >= 7 || sentimentScore < -0.5 ||
               (getTurnCount() > 6 && isRepeatQuestion());
    }

    // ========== ESCALATION ==========

    public void setLastIntent(SupportIntent intent) {
        this.currentIntent = intent;
    }

    public void requestEscalation(String reason) {
        this.escalationRequested = true;
        this.escalationReason = reason;
    }

    public boolean isEscalationRequested() {
        return escalationRequested;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    // ========== ATTRIBUTES ==========

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    // ========== CONTEXT ANALYSIS ==========

    public boolean isRepeatQuestion() {
        if (history.size() < 3) return false;

        List<Turn> userTurns = history.stream()
            .filter(t -> t.type() == TurnType.USER)
            .toList();

        if (userTurns.size() < 2) return false;

        // Check if same intent repeated
        SupportIntent lastIntent = userTurns.get(userTurns.size() - 1).intent();
        long sameIntentCount = userTurns.stream()
            .filter(t -> t.intent() == lastIntent)
            .count();

        return sameIntentCount >= 3;
    }

    public boolean hasUnresolvedIssue() {
        // Check if user asked follow-up questions about same topic
        if (history.size() < 4) return false;

        List<Turn> recentUserTurns = history.stream()
            .filter(t -> t.type() == TurnType.USER)
            .skip(Math.max(0, history.size() - 4))
            .toList();

        return recentUserTurns.stream()
            .map(Turn::intent)
            .distinct()
            .count() == 1;
    }

    public String getSummary() {
        return String.format(
            "Session: %s, User: %s, Turns: %d, Intent: %s, Sentiment: %.2f, Frustration: %d, Escalation: %s",
            sessionId, userId, history.size(), currentIntent, sentimentScore, frustrationLevel, escalationRequested
        );
    }

    // ========== GETTERS ==========

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public Instant getStartTime() { return startTime; }
    public SupportIntent getCurrentIntent() { return currentIntent; }

    // ========== INNER TYPES ==========

    public enum TurnType { USER, AGENT }

    public record Turn(
        TurnType type,
        String text,
        SupportIntent intent,
        Instant timestamp,
        String agentId
    ) {
        public Turn(TurnType type, String text, SupportIntent intent, Instant timestamp) {
            this(type, text, intent, timestamp, null);
        }
    }
}
