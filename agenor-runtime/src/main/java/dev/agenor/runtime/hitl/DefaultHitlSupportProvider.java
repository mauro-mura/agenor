package dev.agenor.runtime.hitl;

import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalHandle;
import dev.agenor.core.spi.HitlSupportProvider;

/**
 * Default {@link HitlSupportProvider}, discovered via {@link java.util.ServiceLoader}.
 *
 * @since 0.25.0
 */
public final class DefaultHitlSupportProvider implements HitlSupportProvider {

    @Override
    public ApprovalGate createDefaultApprovalGate() {
        return new InMemoryApprovalGate();
    }

    @Override
    public ApprovalHandle createApprovalHandle(ApprovalGate gate) {
        return new ApprovalService(gate);
    }
}
