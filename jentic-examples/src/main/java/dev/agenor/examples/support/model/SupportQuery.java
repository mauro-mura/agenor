package dev.agenor.examples.support.model;

import java.time.Instant;
import java.util.Map;

/**
 * Represents an incoming support query from a user.
 */
public record SupportQuery(
    String sessionId,
    String userId,
    String text,
    SupportIntent intent,
    Map<String, Object> context,
    Instant timestamp
) {
    public SupportQuery(String sessionId, String userId, String text) {
        this(sessionId, userId, text, SupportIntent.UNKNOWN, Map.of(), Instant.now());
    }

    public SupportQuery withIntent(SupportIntent intent) {
        return new SupportQuery(sessionId, userId, text, intent, context, timestamp);
    }

    public SupportQuery withContext(Map<String, Object> context) {
        return new SupportQuery(sessionId, userId, text, intent, context, timestamp);
    }
}
