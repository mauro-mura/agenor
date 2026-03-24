package dev.jentic.core.hitl;

import java.util.concurrent.CompletableFuture;

/**
 * Suspends the calling virtual thread until a human decision is received or the
 * request times out.
 *
 * <p>Implementations register the pending request and return a
 * {@link CompletableFuture} that is completed by {@code ApprovalService} when the
 * external decision arrives. The calling agent typically blocks via
 * {@code future.join()}, which is cheap on a virtual thread (Project Loom, ADR-001).
 *
 * <p>Example:
 * <pre>{@code
 * ApprovalRequest req = ApprovalRequest.of(agentId, "delete-record", payload, Duration.ofMinutes(10));
 * ApprovalDecision decision = gate.requestApproval(req).join(); // virtual thread parks here
 * }</pre>
 *
 * @see ApprovalDecision
 * @see ApprovalRequest
 * @see ApprovalTimeoutException
 * @since 0.13.0
 */
public interface ApprovalGate {

    /**
     * Registers the approval request and returns a future that resolves to the
     * human decision.
     *
     * <p>The future completes exceptionally with {@link ApprovalTimeoutException}
     * if no decision is submitted before {@link ApprovalRequest#expiresAt()}.
     *
     * @param request the approval request; never {@code null}
     * @return a future that resolves to an {@link ApprovalDecision}; never {@code null}
     */
    CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request);
}