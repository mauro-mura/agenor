package dev.agenor.core.hitl;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable description of a human approval request.
 *
 * <p>Create via the factory method:
 * <pre>{@code
 * ApprovalRequest req = ApprovalRequest.of("payment-agent", "process-payment",
 *                                          paymentPayload, Duration.ofMinutes(30));
 * }</pre>
 *
 * @param requestId  unique identifier (UUID), generated automatically by {@link #of}
 * @param agentId    identifier of the agent requesting approval
 * @param action     human-readable name of the action to approve
 * @param payload    action data; serialised to JSON in webhook notifications. A request read
 *                   back from a persistent gate carries the JSON it stored, not the original
 *                   object: read it with {@link #payloadAs(Class)}
 * @param expiresAt  absolute instant after which the request times out
 * @param metadata   optional key-value pairs for contextual information
 * @since 0.13.0
 */
public record ApprovalRequest(
        String requestId,
        String agentId,
        String action,
        Object payload,
        Instant expiresAt,
        Map<String, Object> metadata
) {

    public ApprovalRequest {
        if (requestId == null || requestId.isBlank())
            throw new IllegalArgumentException("requestId must not be null or blank");
        if (agentId == null || agentId.isBlank())
            throw new IllegalArgumentException("agentId must not be null or blank");
        if (action == null || action.isBlank())
            throw new IllegalArgumentException("action must not be null or blank");
        if (expiresAt == null)
            throw new IllegalArgumentException("expiresAt must not be null");
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    /**
     * Reads {@link #payload()} as the requested type, converting it if necessary.
     *
     * <p>This is the accessor a reader of {@link ApprovalGate#getPendingRequests()} should use.
     * Casting {@code payload()} works only against a gate that keeps the request in memory: a
     * persistent gate, such as the JDBC one, returns the payload as the JSON string it stored,
     * and the cast throws — a failure that no in-memory test can reproduce.
     *
     * <pre>{@code
     * // breaks on the JDBC gate
     * Draft draft = (Draft) pending.payload();
     *
     * // works on every gate
     * Draft draft = pending.payloadAs(Draft.class);
     * }</pre>
     *
     * <p>A payload already of the requested type is returned as the same reference; a JSON
     * string is parsed and converted, as {@link dev.agenor.core.dialogue.DialogueMessage#contentAs(Class)}
     * converts dialogue content.
     *
     * @param <T> the expected type of the payload
     * @param type the class of the expected type; must not be null
     * @return the payload as type T, or null if the payload is null
     * @throws IllegalArgumentException if the payload is neither an instance of {@code type} nor
     *         convertible to it
     * @since 0.38.0
     */
    public <T> T payloadAs(Class<T> type) {
        return PayloadConversion.as(payload, type);
    }

    /**
     * Creates a new {@code ApprovalRequest} with a generated UUID and expiry
     * computed from the given {@code timeout}.
     *
     * @param agentId agent requesting approval
     * @param action  name of the action requiring approval
     * @param payload data associated with the action
     * @param timeout duration before the request expires
     * @return a new {@code ApprovalRequest}
     */
    public static ApprovalRequest of(String agentId, String action, Object payload, Duration timeout) {
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                agentId,
                action,
                payload,
                Instant.now().plus(timeout),
                Collections.emptyMap()
        );
    }

    /**
     * Creates a new {@code ApprovalRequest} with metadata.
     *
     * @param agentId  agent requesting approval
     * @param action   name of the action requiring approval
     * @param payload  data associated with the action
     * @param timeout  duration before the request expires
     * @param metadata contextual key-value pairs
     * @return a new {@code ApprovalRequest}
     */
    public static ApprovalRequest of(String agentId, String action, Object payload,
                                     Duration timeout, Map<String, Object> metadata) {
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                agentId,
                action,
                payload,
                Instant.now().plus(timeout),
                metadata
        );
    }
}
