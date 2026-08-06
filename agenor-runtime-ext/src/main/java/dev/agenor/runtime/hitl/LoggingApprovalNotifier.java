package dev.agenor.runtime.hitl;

import dev.agenor.core.hitl.ApprovalNotifier;
import dev.agenor.core.hitl.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ApprovalNotifier} that logs pending approval requests via SLF4J
 * at {@code WARN} level.
 *
 * <p>Zero external dependencies — suitable for development, testing, and any
 * environment where a structured log entry is sufficient to alert operators.
 * This is the notifier substituted by the runtime wiring when
 * {@link dev.agenor.core.hitl.DefaultApprovalNotifier} sentinel is detected on
 * a {@link dev.agenor.core.hitl.RequiresApproval} annotation.
 *
 * <p>Log format example:
 * <pre>
 * WARN  [hitl] Approval required — requestId=abc-123, agentId=payment-agent,
 *              action=process-payment, expiresAt=2026-03-24T10:30:00Z
 * </pre>
 *
 * @see ApprovalNotifier
 * @since 0.13.0
 */
public class LoggingApprovalNotifier implements ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger("hitl");

    /** No-arg constructor required for {@code @RequiresApproval} annotation wiring. */
    public LoggingApprovalNotifier() {}

    @Override
    public void notify(ApprovalRequest request) {
        log.warn("Approval required — requestId={}, agentId={}, action={}, expiresAt={}",
                request.requestId(),
                request.agentId(),
                request.action(),
                request.expiresAt());
    }
}
