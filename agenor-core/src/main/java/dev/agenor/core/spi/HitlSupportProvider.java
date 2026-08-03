package dev.agenor.core.spi;

import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalHandle;

/**
 * Optional HITL (human-in-the-loop) support, discovered via
 * {@link java.util.ServiceLoader}.
 *
 * <p>{@code agenor-runtime} depends on this interface only; the implementation
 * (in-memory approval gate, approval decision routing) lives in
 * {@code agenor-runtime-ext}. When absent, {@code AgenorRuntime} falls back to a
 * {@code null} default approval gate and a
 * {@link dev.agenor.core.hitl.NoopApprovalHandle}.
 *
 * @since 0.25.0
 */
public interface HitlSupportProvider {

    /**
     * Creates the default {@link ApprovalGate} used when the builder is not given one.
     *
     * @return a new approval gate; never {@code null}
     */
    ApprovalGate createDefaultApprovalGate();

    /**
     * Creates the {@link ApprovalHandle} that external systems use to submit decisions.
     *
     * @param gate the approval gate backing the handle; never {@code null}
     * @return a new approval handle; never {@code null}
     */
    ApprovalHandle createApprovalHandle(ApprovalGate gate);
}
