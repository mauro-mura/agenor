package dev.agenor.runtime.hitl;

import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.hitl.ApprovalTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of {@link ApprovalGate}.
 *
 * <p>Each call to {@link #requestApproval} registers the request in a
 * {@link ConcurrentHashMap} and returns a {@link CompletableFuture} that is
 * completed by {@link ApprovalService} when the external decision arrives.
 * The calling virtual thread parks cheaply on {@code future.join()} (ADR-001).
 *
 * <p>A shared {@link ScheduledExecutorService} handles timeout expiry: when a
 * request's deadline passes, the future is completed exceptionally with
 * {@link ApprovalTimeoutException} and both internal maps are cleaned up.
 *
 * <p>This class is intended to be used as a singleton managed by
 * {@code AgenorRuntime}. The {@link #submit} method is package-private and
 * called exclusively by {@link ApprovalService}.
 *
 * @see ApprovalGate
 * @see ApprovalService
 * @since 0.13.0
 */
public class InMemoryApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(InMemoryApprovalGate.class);

    /** Pending futures keyed by requestId. */
    private final Map<String, CompletableFuture<ApprovalDecision>> pendingFutures =
            new ConcurrentHashMap<>();

    /** Pending requests keyed by requestId — used by ApprovalService.getPendingRequests(). */
    private final Map<String, ApprovalRequest> pendingRequests = new ConcurrentHashMap<>();

    /** Single-threaded scheduler for timeout callbacks (daemon threads). */
    private final ScheduledExecutorService scheduler;

    /** Creates a gate with a dedicated single-thread daemon scheduler. */
    public InMemoryApprovalGate() {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("hitl-timeout-scheduler");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Creates a gate with the supplied scheduler (injectable for testing).
     *
     * @param scheduler scheduler used for timeout callbacks
     */
    InMemoryApprovalGate(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    // -------------------------------------------------------------------------
    // ApprovalGate
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Registers the request, schedules a timeout callback, and returns a
     * future that the agent's virtual thread parks on via {@code .join()}.
     */
    @Override
    public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");

        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();

        // Remove from both maps when the future completes (normally or exceptionally)
        future.whenComplete((decision, ex) -> {
            pendingFutures.remove(request.requestId());
            pendingRequests.remove(request.requestId());
        });

        pendingFutures.put(request.requestId(), future);
        pendingRequests.put(request.requestId(), request);

        scheduleTimeout(request, future);

        log.debug("Approval requested: requestId={}, agentId={}, action={}, expiresAt={}",
                request.requestId(), request.agentId(), request.action(), request.expiresAt());

        return future;
    }

    /**
     * Submits a decision for a pending approval request.
     *
     * <p>If the future for {@code requestId} has already been completed
     * (e.g. timed out), this call is a silent no-op.
     *
     * @param requestId the UUID of the pending request
     * @param decision  the human decision
     * @throws IllegalArgumentException if no pending request exists for {@code requestId}
     */
    @Override
    public void submit(String requestId, ApprovalDecision decision) {
        CompletableFuture<ApprovalDecision> future = pendingFutures.get(requestId);
        if (future == null) {
            throw new IllegalArgumentException(
                    "No pending approval request found for requestId: " + requestId);
        }
        // complete() is a no-op if the future is already completed (timeout race)
        boolean completed = future.complete(decision);
        if (completed) {
            log.debug("Approval decision submitted: requestId={}, decision={}", requestId,
                    decision.getClass().getSimpleName());
        } else {
            log.debug("Decision ignored — future already completed for requestId={}", requestId);
        }
    }

    /**
     * Returns a snapshot of requests that are still pending (not yet decided or timed out).
     *
     * @return immutable list of pending {@link ApprovalRequest}s
     */
    @Override
    public List<ApprovalRequest> getPendingRequests() {
        return List.copyOf(pendingRequests.values());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void scheduleTimeout(ApprovalRequest request, CompletableFuture<ApprovalDecision> future) {
        long delayMs = Math.max(0L,
                Duration.between(Instant.now(), request.expiresAt()).toMillis());

        scheduler.schedule(() -> {
            boolean timedOut = future.completeExceptionally(
                    new ApprovalTimeoutException(request));
            if (timedOut) {
                log.warn("Approval request timed out: requestId={}, agentId={}, action={}",
                        request.requestId(), request.agentId(), request.action());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
