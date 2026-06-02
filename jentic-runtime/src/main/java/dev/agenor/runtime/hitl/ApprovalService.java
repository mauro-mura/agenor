package dev.agenor.runtime.hitl;

import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Facade that receives human decisions from external systems (HTTP handlers,
 * webhooks, tests) and forwards them to {@link InMemoryApprovalGate}.
 *
 * <p>Exposed as a singleton via {@code AgenorRuntime.getApprovalService()} (T7).
 * External callers use the convenience methods {@link #approve}, {@link #reject},
 * and {@link #modify}, or the generic {@link #submit} for arbitrary decisions.
 *
 * <p>{@link #getPendingRequests()} returns a snapshot of requests still awaiting
 * a decision — useful for dashboards or polling UIs.
 *
 * @see InMemoryApprovalGate
 * @since 0.13.0
 */
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalGate gate;

    /**
     * Creates an {@code ApprovalService} backed by the given gate.
     *
     * @param gate the approval gate; never {@code null}
     */
    public ApprovalService(ApprovalGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
    }

    // -------------------------------------------------------------------------
    // Decision API
    // -------------------------------------------------------------------------

    /**
     * Approves the pending request identified by {@code requestId}.
     *
     * @param requestId UUID of the pending request
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    public void approve(String requestId) {
        submit(requestId, new ApprovalDecision.Approved());
        log.info("Request approved: requestId={}", requestId);
    }

    /**
     * Rejects the pending request identified by {@code requestId}.
     *
     * @param requestId UUID of the pending request
     * @param reason    human-readable explanation; must not be {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    public void reject(String requestId, String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        submit(requestId, new ApprovalDecision.Rejected(reason));
        log.info("Request rejected: requestId={}, reason={}", requestId, reason);
    }

    /**
     * Approves the pending request with a modified payload.
     *
     * @param requestId  UUID of the pending request
     * @param newPayload revised action payload; must not be {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    public void modify(String requestId, Object newPayload) {
        Objects.requireNonNull(newPayload, "newPayload must not be null");
        submit(requestId, new ApprovalDecision.Modified(newPayload));
        log.info("Request modified: requestId={}", requestId);
    }

    /**
     * Submits an arbitrary {@link ApprovalDecision} for the pending request.
     *
     * @param requestId UUID of the pending request
     * @param decision  the decision to submit; must not be {@code null}
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    public void submit(String requestId, ApprovalDecision decision) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        gate.submit(requestId, decision);
    }

    // -------------------------------------------------------------------------
    // Query API
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of approval requests that are still pending.
     *
     * <p>Expired or already-decided requests are not included.
     *
     * @return immutable list of pending {@link ApprovalRequest}s
     */
    public List<ApprovalRequest> getPendingRequests() {
        return gate.getPendingRequests();
    }
}
