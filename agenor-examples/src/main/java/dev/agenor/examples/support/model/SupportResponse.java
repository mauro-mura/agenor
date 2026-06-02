package dev.agenor.examples.support.model;

import java.util.List;
import java.util.Map;

/**
 * Response from the support chatbot.
 */
public record SupportResponse(
    String sessionId,
    String text,
    SupportIntent intent,
    double confidence,
    List<String> suggestedActions,
    boolean requiresEscalation,
    Map<String, Object> metadata
) {
    /**
     * Canonical constructor - sessionId can be null for collaborative responses.
     */
    public SupportResponse {
        if (suggestedActions == null) suggestedActions = List.of();
        if (metadata == null) metadata = Map.of();
    }

    /**
     * Constructor without metadata (backward compatibility).
     */
    public SupportResponse(String sessionId, String text, SupportIntent intent,
            double confidence, List<String> suggestedActions, boolean requiresEscalation) {
        this(sessionId, text, intent, confidence, suggestedActions, requiresEscalation, Map.of());
    }

    /**
     * Constructor without sessionId (for collaborative responses).
     */
    public SupportResponse(String text, SupportIntent intent, double confidence,
            List<String> suggestedActions, boolean requiresEscalation, Map<String, Object> metadata) {
        this(null, text, intent, confidence, suggestedActions, requiresEscalation, metadata);
    }

    /**
     * Alias for backward compatibility.
     */
    public SupportIntent handledBy() {
        return intent;
    }

    public static SupportResponse simple(String sessionId, String text, SupportIntent handler) {
        return new SupportResponse(sessionId, text, handler, 1.0, List.of(), false, Map.of());
    }

    public static SupportResponse withConfidence(String sessionId, String text,
            SupportIntent handler, double confidence) {
        return new SupportResponse(sessionId, text, handler, confidence, List.of(), false, Map.of());
    }

    public static SupportResponse escalate(String sessionId, String reason) {
        return new SupportResponse(sessionId, reason, SupportIntent.ESCALATE, 1.0, List.of(), true, Map.of());
    }
}
