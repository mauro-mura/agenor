package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalNotifier;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.hitl.ApprovalTimeoutException;
import dev.agenor.core.telemetry.JenticTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanStatus;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.hitl.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A ONE_SHOT behavior that suspends execution until a human approves, rejects, or
 * modifies the wrapped critical action.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Notifier fires (fire-and-forget) to alert an external observer.</li>
 *   <li>{@link ApprovalGate#requestApproval} is called; the virtual thread parks.</li>
 *   <li>When {@link ApprovalService#submit} completes the future, the behavior
 *       resumes and dispatches to {@code decisionHandler}.</li>
 * </ol>
 *
 * <p>Example:
 * <pre>{@code
 * new HumanCheckpointBehavior<>(
 *     "payment-checkpoint",
 *     approvalGate,
 *     notifier,
 *     paymentPayload,
 *     "process-payment",
 *     Duration.ofMinutes(30),
 *     decision -> switch (decision) {
 *         case ApprovalDecision.Approved  a -> processPayment(paymentPayload);
 *         case ApprovalDecision.Rejected  r -> log.warn("Rejected: {}", r.reason());
 *         case ApprovalDecision.Modified  m -> processPayment((PaymentRequest) m.newPayload());
 *     }
 * )
 * }</pre>
 *
 * <p>Timeout: if no decision arrives within {@code timeout},
 * {@link ApprovalTimeoutException} is thrown inside {@code action()} and routed
 * to {@link #onError} by {@code BaseBehavior} (which swallows all throwables and
 * completes the future normally). Override {@link #onError} to add custom fallback
 * or escalation logic.
 *
 * @param <T> type of the action payload
 * @see ApprovalGate
 * @see ApprovalNotifier
 * @see ApprovalDecision
 * @since 0.13.0
 */
public class HumanCheckpointBehavior<T> extends BaseBehavior {

    private static final Logger log = LoggerFactory.getLogger(HumanCheckpointBehavior.class);

    private final ApprovalGate gate;
    private final ApprovalNotifier notifier;
    private final T payload;
    private final String actionName;
    private final Duration timeout;
    private final Consumer<ApprovalDecision> decisionHandler;
    private final JenticTelemetry telemetry;

    /**
     * Creates a new {@code HumanCheckpointBehavior}.
     *
     * @param behaviorId      unique identifier for this behavior
     * @param gate            gate used to park the virtual thread until a decision arrives
     * @param notifier        notifier invoked before the gate parks (fire-and-forget)
     * @param payload         data describing the action to be approved
     * @param actionName      human-readable action name included in the {@link ApprovalRequest}
     * @param timeout         maximum wait time for a human decision
     * @param decisionHandler called with the {@link ApprovalDecision} when one is received;
     *                        responsible for executing or skipping the critical action
     */
    public HumanCheckpointBehavior(
            String behaviorId,
            ApprovalGate gate,
            ApprovalNotifier notifier,
            T payload,
            String actionName,
            Duration timeout,
            Consumer<ApprovalDecision> decisionHandler) {
        this(behaviorId, gate, notifier, payload, actionName, timeout, decisionHandler, JenticTelemetry.noop());
    }

    /**
     * Creates a new {@code HumanCheckpointBehavior} with telemetry support.
     *
     * @param behaviorId      unique identifier for this behavior
     * @param gate            gate used to park the virtual thread until a decision arrives
     * @param notifier        notifier invoked before the gate parks (fire-and-forget)
     * @param payload         data describing the action to be approved
     * @param actionName      human-readable action name included in the {@link ApprovalRequest}
     * @param timeout         maximum wait time for a human decision
     * @param decisionHandler called with the {@link ApprovalDecision} when one is received
     * @param telemetry       telemetry instance for emitting {@code hitl.approval} spans;
     *                        {@code null} uses noop
     * @since 0.19.0
     */
    public HumanCheckpointBehavior(
            String behaviorId,
            ApprovalGate gate,
            ApprovalNotifier notifier,
            T payload,
            String actionName,
            Duration timeout,
            Consumer<ApprovalDecision> decisionHandler,
            JenticTelemetry telemetry) {

        super(behaviorId, BehaviorType.ONE_SHOT, null);
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
        this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
        this.payload = payload;
        this.actionName = Objects.requireNonNull(actionName, "actionName must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.decisionHandler = Objects.requireNonNull(decisionHandler, "decisionHandler must not be null");
        this.telemetry = telemetry != null ? telemetry : JenticTelemetry.noop();
    }

    // -------------------------------------------------------------------------
    // BaseBehavior
    // -------------------------------------------------------------------------

    @Override
    protected void action() {
        String agentId = getAgent() != null ? getAgent().getAgentId() : "unknown";

        ApprovalRequest request = ApprovalRequest.of(agentId, actionName, payload, timeout);

        log.debug("Requesting human approval: behaviorId={}, requestId={}, action={}",
                getBehaviorId(), request.requestId(), actionName);

        // Fire-and-forget notification (must not throw)
        try {
            notifier.notify(request);
        } catch (Exception e) {
            log.warn("ApprovalNotifier threw an exception (ignored): behaviorId={}, notifier={}",
                    getBehaviorId(), notifier.getClass().getSimpleName(), e);
        }

        Span span = telemetry.spanBuilder("hitl.approval")
                .setAttribute("hitl.request_id", request.requestId().toString())
                .setAttribute("hitl.action", actionName)
                .startSpan();

        long startMs = System.currentTimeMillis();
        try {
            // Park the virtual thread until the gate future is completed
            ApprovalDecision decision = gate.requestApproval(request).join();

            long waitMs = System.currentTimeMillis() - startMs;
            span.setAttribute("hitl.decision", decision.getClass().getSimpleName())
                .setAttribute("hitl.wait_ms", waitMs)
                .setStatus(SpanStatus.OK);

            log.debug("Approval decision received: requestId={}, decision={}",
                    request.requestId(), decision.getClass().getSimpleName());

            decisionHandler.accept(decision);
        } catch (Exception e) {
            span.recordException(e).setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
