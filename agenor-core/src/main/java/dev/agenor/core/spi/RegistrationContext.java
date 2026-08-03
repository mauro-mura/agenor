package dev.agenor.core.spi;

import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.telemetry.AgenorTelemetry;

/**
 * Services made available to {@link AgentRegistrationExtension} implementations
 * during {@code AgenorRuntime.registerAgent()}.
 *
 * @param telemetry    the runtime's telemetry instance, never {@code null}
 * @param approvalGate the runtime's approval gate, {@code null} if no HITL support
 *                      is configured
 * @since 0.25.0
 */
public record RegistrationContext(AgenorTelemetry telemetry, ApprovalGate approvalGate) {
}
