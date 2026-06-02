package dev.agenor.examples.support.context;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Simple keyword-based sentiment analyzer.
 * Detects positive, negative, and frustration signals in user messages.
 */
public class SentimentAnalyzer {

    // Negative indicators (frustration, anger, disappointment)
    private static final Set<String> NEGATIVE_WORDS = Set.of(
        "frustrated", "frustrating", "annoyed", "annoying", "angry", "mad",
        "terrible", "awful", "horrible", "worst", "hate", "useless",
        "stupid", "ridiculous", "unacceptable", "disappointed", "disappointing",
        "broken", "doesn't work", "not working", "failed", "failing",
        "waste", "scam", "garbage", "trash", "pathetic", "incompetent",
        // Added for better coverage
        "nothing works", "never works", "still broken", "not helpful",
        "unhelpful", "pointless", "impossible", "can't", "cannot",
        "won't", "stuck", "confused", "confusing", "wrong", "incorrect",
        "error", "problem", "issue", "bug", "glitch", "crash", "crashing"
    );

    // Strong negative (escalation signals)
    private static final Set<String> ESCALATION_SIGNALS = Set.of(
        "speak to human", "real person", "talk to someone", "agent please",
        "manager", "supervisor", "complaint", "sue", "lawyer", "legal",
        "cancel everything", "close my account", "give up", "done with this",
        "this is unacceptable", "i demand", "immediately", "right now"
    );

    // Positive indicators
    private static final Set<String> POSITIVE_WORDS = Set.of(
        "thanks", "thank you", "helpful", "great", "good", "excellent",
        "perfect", "awesome", "amazing", "appreciate", "worked", "solved",
        "fixed", "love", "wonderful", "fantastic", "happy", "pleased"
    );

    // Frustration patterns (repeated punctuation, caps)
    private static final Pattern FRUSTRATION_PATTERN = Pattern.compile(
        "(!{2,}|\\?{2,}|[A-Z]{5,})"
    );

    // Urgency indicators
    private static final Set<String> URGENCY_WORDS = Set.of(
        "urgent", "asap", "emergency", "immediately", "now", "quickly",
        "hurry", "critical", "important", "desperate"
    );

    /**
     * Analyzes sentiment of a text message.
     *
     * @param text the user message
     * @return SentimentResult with score and flags
     */
    public SentimentResult analyze(String text) {
        if (text == null || text.isBlank()) {
            return new SentimentResult(0.0, false, false, 0);
        }

        String lower = text.toLowerCase();

        // Count indicators
        int negativeCount = countMatches(lower, NEGATIVE_WORDS);
        int positiveCount = countMatches(lower, POSITIVE_WORDS);
        int escalationCount = countMatches(lower, ESCALATION_SIGNALS);
        int urgencyCount = countMatches(lower, URGENCY_WORDS);

        // Check for frustration patterns
        boolean hasFrustrationPattern = FRUSTRATION_PATTERN.matcher(text).find();

        // Calculate base score (-1 to 1)
        double score = 0.0;
        int total = negativeCount + positiveCount;

        if (total > 0) {
            score = (positiveCount - negativeCount) / (double) Math.max(total, 3);
        }

        // Adjust for patterns
        if (hasFrustrationPattern) {
            score -= 0.2;
        }

        // Adjust for escalation signals
        if (escalationCount > 0) {
            score -= 0.3 * escalationCount;
        }

        // Clamp score
        score = Math.max(-1.0, Math.min(1.0, score));

        // Determine flags
        boolean suggestEscalation = escalationCount > 0 || score < -0.5;
        boolean isUrgent = urgencyCount > 0 || escalationCount > 0;

        // Calculate frustration level (0-10)
        int frustrationLevel = calculateFrustrationLevel(
            negativeCount, escalationCount, hasFrustrationPattern, score);

        return new SentimentResult(score, suggestEscalation, isUrgent, frustrationLevel);
    }

    private int countMatches(String text, Set<String> words) {
        int count = 0;
        for (String word : words) {
            if (text.contains(word)) {
                count++;
            }
        }
        return count;
    }

    private int calculateFrustrationLevel(int negativeCount, int escalationCount,
            boolean hasFrustrationPattern, double score) {

        int level = 0;

        // Base from negative words
        level += Math.min(3, negativeCount);

        // Escalation signals are strong indicators
        level += escalationCount * 2;

        // Frustration patterns (caps, multiple punctuation)
        if (hasFrustrationPattern) {
            level += 2;
        }

        // Adjust for overall sentiment
        if (score < -0.5) {
            level += 2;
        } else if (score < -0.2) {
            level += 1;
        }

        return Math.min(10, level);
    }

    /**
     * Quick check if message suggests user wants escalation.
     */
    public boolean wantsEscalation(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return ESCALATION_SIGNALS.stream().anyMatch(lower::contains);
    }

    /**
     * Result of sentiment analysis.
     */
    public record SentimentResult(
        double score,           // -1.0 (very negative) to 1.0 (very positive)
        boolean suggestEscalation,
        boolean isUrgent,
        int frustrationLevel    // 0-10
    ) {
        public boolean isNegative() {
            return score < -0.2;
        }

        public boolean isPositive() {
            return score > 0.2;
        }

        public boolean isNeutral() {
            return score >= -0.2 && score <= 0.2;
        }

        public String getSentimentLabel() {
            if (score < -0.5) return "very_negative";
            if (score < -0.2) return "negative";
            if (score < 0.2) return "neutral";
            if (score < 0.5) return "positive";
            return "very_positive";
        }
    }
}
