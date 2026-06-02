package dev.agenor.core.guardrail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable contextual metadata passed to every guardrail during chain execution.
 *
 * <p>Provides guardrails with information about the invoking agent and the current
 * conversation topic, as well as an open-ended metadata map for custom attributes.
 *
 * <p>Example:
 * <pre>{@code
 * GuardrailContext ctx = new GuardrailContext(
 *     "finance-agent-01",
 *     "investment-advice",
 *     Map.of("userId", "u-42", "locale", "it-IT")
 * );
 * }</pre>
 *
 * @param agentId  identifier of the agent running the guardrail chain; never {@code null}
 * @param topic    logical topic or channel for the current interaction; may be {@code null}
 * @param metadata additional key-value attributes; never {@code null}, defensively copied
 * @since 0.13.0
 */
public record GuardrailContext(
        String agentId,
        String topic,
        Map<String, Object> metadata) {

    public GuardrailContext {
        Objects.requireNonNull(agentId, "agentId must not be null");
        metadata = Collections.unmodifiableMap(new HashMap<>(
                metadata != null ? metadata : Collections.emptyMap()));
    }

    /**
     * Creates a context with no topic and an empty metadata map.
     *
     * @param agentId identifier of the invoking agent; never {@code null}
     */
    public static GuardrailContext of(String agentId) {
        return new GuardrailContext(agentId, null, Collections.emptyMap());
    }

    /**
     * Creates a context with a topic and an empty metadata map.
     *
     * @param agentId identifier of the invoking agent; never {@code null}
     * @param topic   logical topic for the current interaction; may be {@code null}
     */
    public static GuardrailContext of(String agentId, String topic) {
        return new GuardrailContext(agentId, topic, Collections.emptyMap());
    }
}
