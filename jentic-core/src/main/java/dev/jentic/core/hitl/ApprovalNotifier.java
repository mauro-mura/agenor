package dev.jentic.core.hitl;

/**
 * SPI for notifying an external observer that a human approval request is pending.
 *
 * <p>Implementations send an asynchronous, fire-and-forget notification (webhook,
 * log entry, message queue, etc.) without blocking the agent. Checked exceptions
 * must be handled internally; failures should be logged rather than propagated.
 *
 * <p>Implementations used with {@link RequiresApproval} must expose a public
 * no-arg constructor. Notifiers requiring configuration (e.g. a webhook URL or
 * auth token) must be wired programmatically via {@code HumanCheckpointBehavior}.
 *
 * @see RequiresApproval
 * @see ApprovalRequest
 * @since 0.13.0
 */
public interface ApprovalNotifier {

    /**
     * Sends a notification that {@code request} is awaiting human approval.
     *
     * <p>This method must not throw checked exceptions. Transient failures (network
     * errors, timeouts) should be caught and logged internally.
     *
     * @param request the pending approval request; never {@code null}
     */
    void notify(ApprovalRequest request);
}