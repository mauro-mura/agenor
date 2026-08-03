package dev.agenor.core.hitl;

import java.util.List;

/**
 * Receives human decisions from external systems (HTTP handlers, webhooks, tests)
 * for pending HITL approval requests.
 *
 * <p>Exposed as {@code AgenorRuntime.getApprovalService()}. The default
 * implementation is {@code ApprovalService} in {@code agenor-runtime-ext}; when
 * that module is absent, {@link NoopApprovalHandle} is used instead.
 *
 * @since 0.25.0
 */
public interface ApprovalHandle {

    /**
     * Approves the pending request identified by {@code requestId}.
     *
     * @param requestId UUID of the pending request
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    void approve(String requestId);

    /**
     * Rejects the pending request identified by {@code requestId}.
     *
     * @param requestId UUID of the pending request
     * @param reason    human-readable explanation; must not be {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    void reject(String requestId, String reason);

    /**
     * Approves the pending request with a modified payload.
     *
     * @param requestId  UUID of the pending request
     * @param newPayload revised action payload; must not be {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    void modify(String requestId, Object newPayload);

    /**
     * Submits an arbitrary {@link ApprovalDecision} for the pending request.
     *
     * @param requestId UUID of the pending request
     * @param decision  the decision to submit; must not be {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    void submit(String requestId, ApprovalDecision decision);

    /**
     * Returns a snapshot of approval requests that are still pending.
     *
     * @return immutable list of pending {@link ApprovalRequest}s
     */
    List<ApprovalRequest> getPendingRequests();
}
