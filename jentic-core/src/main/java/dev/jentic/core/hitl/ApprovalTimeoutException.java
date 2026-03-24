package dev.jentic.core.hitl;

import dev.jentic.core.exceptions.JenticException;

import java.time.Instant;

/**
 * Thrown when no human decision is submitted before an {@link ApprovalRequest}
 * expires.
 *
 * <p>This is an unchecked exception (extends {@link JenticException}) consistent
 * with the Jentic exception hierarchy. Callers may catch it explicitly to implement
 * fallback or escalation logic.
 *
 * @see ApprovalGate
 * @see ApprovalRequest
 * @since 0.13.0
 */
public class ApprovalTimeoutException extends JenticException {

    private final String requestId;
    private final Instant expiresAt;

    /**
     * Creates a new {@code ApprovalTimeoutException} from an expired request.
     *
     * @param requestId UUID of the timed-out request
     * @param expiresAt the expiry instant of the request
     */
    public ApprovalTimeoutException(String requestId, Instant expiresAt) {
        super("Approval request timed out: requestId=" + requestId
              + ", expiresAt=" + expiresAt);
        this.requestId = requestId;
        this.expiresAt = expiresAt;
    }

    /**
     * Creates a new {@code ApprovalTimeoutException} directly from the request record.
     *
     * @param request the expired approval request
     */
    public ApprovalTimeoutException(ApprovalRequest request) {
        this(request.requestId(), request.expiresAt());
    }

    /** Returns the UUID of the timed-out approval request. */
    public String getRequestId() {
        return requestId;
    }

    /** Returns the expiry instant of the timed-out approval request. */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}