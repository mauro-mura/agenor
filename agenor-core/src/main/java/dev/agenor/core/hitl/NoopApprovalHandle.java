package dev.agenor.core.hitl;

import java.util.List;

/**
 * Default {@link ApprovalHandle} used when no HITL provider (e.g.
 * {@code agenor-runtime-ext}) is on the classpath.
 *
 * <p>Query methods return an empty result; decision-submitting methods fail
 * fast with a message pointing at the missing module, rather than silently
 * doing nothing.
 *
 * @since 0.25.0
 */
public final class NoopApprovalHandle implements ApprovalHandle {

    private static final String MESSAGE = "HITL requires agenor-runtime-ext";

    @Override
    public void approve(String requestId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void reject(String requestId, String reason) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void modify(String requestId, Object newPayload) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void submit(String requestId, ApprovalDecision decision) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<ApprovalRequest> getPendingRequests() {
        return List.of();
    }
}
