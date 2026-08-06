package dev.agenor.runtime.hitl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalHandle;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.hitl.RequiresApproval;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.HitlSupportProvider;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.behavior.advanced.HumanCheckpointBehavior;

/**
 * Verifies the real {@link DefaultHitlSupportProvider} and
 * {@link HitlRegistrationExtension} — not a synthetic test double, as used by
 * {@code AgentRegistrationExtensionSpiTest} — are actually discovered via
 * {@link ServiceLoader} and wired end-to-end through {@link AgenorRuntime}.
 *
 * <p>Written ahead of the ADR-027 task-3 module extraction (moving both classes
 * from {@code agenor-runtime} to {@code agenor-runtime-ext}) as a regression
 * guard for exactly the failure mode a botched module move could cause: a
 * missing or misplaced {@code META-INF/services} entry. This test depends only
 * on public SPI types and {@link AgenorRuntime}, so it keeps passing unchanged
 * after the move — proving the wiring survived it.
 *
 * @since 0.25.0
 */
@DisplayName("HITL SPI wiring (DefaultHitlSupportProvider + HitlRegistrationExtension)")
class HitlSpiWiringTest {

    private AgenorRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop().join();
        }
    }

    @Test
    @DisplayName("ServiceLoader discovers DefaultHitlSupportProvider producing a working gate + handle")
    void hitlSupportProvider_discoveredAndFunctional() throws Exception {
        HitlSupportProvider provider = ServiceLoader.load(HitlSupportProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p instanceof DefaultHitlSupportProvider)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "DefaultHitlSupportProvider not found via ServiceLoader — "
                        + "check META-INF/services/" + HitlSupportProvider.class.getName()));

        ApprovalGate gate = provider.createDefaultApprovalGate();
        assertThat(gate).isInstanceOf(InMemoryApprovalGate.class);

        ApprovalHandle handle = provider.createApprovalHandle(gate);
        assertThat(handle).isInstanceOf(ApprovalService.class);

        // Functional check: a request submitted on the gate is visible and
        // approvable through the handle it was paired with.
        ApprovalRequest request = ApprovalRequest.of(
                "spi-test-agent", "spi-test-action", null, Duration.ofSeconds(30));
        var future = gate.requestApproval(request);

        assertThat(handle.getPendingRequests())
                .extracting(ApprovalRequest::requestId)
                .contains(request.requestId());

        handle.approve(request.requestId());

        assertThat(future.get(2, TimeUnit.SECONDS)).isInstanceOf(ApprovalDecision.Approved.class);
    }

    @Test
    @DisplayName("ServiceLoader discovers HitlRegistrationExtension among AgentRegistrationExtensions")
    void hitlRegistrationExtension_discovered() {
        var extensions = ServiceLoader.load(AgentRegistrationExtension.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        assertThat(extensions)
                .as("check META-INF/services/" + AgentRegistrationExtension.class.getName())
                .anyMatch(e -> e instanceof HitlRegistrationExtension);
    }

    @Test
    @DisplayName("end-to-end: AgenorRuntime wires HITL support and getApprovalService().approve() unblocks the behavior")
    void endToEnd_runtimeWiresHitlSupport() throws Exception {
        runtime = AgenorRuntime.builder().build();

        var executed = new AtomicBoolean(false);
        var agent = new SpiTestAgent();
        agent.addBehavior(new SpiTrackingBehavior(executed));

        runtime.registerAgent(agent);

        // registerAgent() dispatched HitlRegistrationExtension (via ServiceLoader),
        // which wraps @RequiresApproval behaviors with HumanCheckpointBehavior.
        BaseBehavior checkpoint = (BaseBehavior) agent.getBehaviors().stream()
                .filter(b -> b instanceof HumanCheckpointBehavior)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "@RequiresApproval behavior was not wrapped — "
                        + "HitlRegistrationExtension not wired into AgenorRuntime"));
        checkpoint.setAgent(agent);

        var future = checkpoint.execute();

        long deadline = System.currentTimeMillis() + 3_000;
        while (runtime.getApprovalService().getPendingRequests().isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(runtime.getApprovalService().getPendingRequests()).isNotEmpty();

        String requestId = runtime.getApprovalService().getPendingRequests().getFirst().requestId();
        runtime.getApprovalService().approve(requestId);

        future.get(2, TimeUnit.SECONDS);

        assertThat(executed).isTrue();
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    static class SpiTestAgent extends BaseAgent {
        SpiTestAgent() {
            super("hitl-spi-test-agent", "HITL SPI Test Agent");
        }
    }

    @RequiresApproval(timeout = "5m")
    static class SpiTrackingBehavior extends BaseBehavior {
        private final AtomicBoolean executed;

        SpiTrackingBehavior(AtomicBoolean executed) {
            super("spi-tracking", BehaviorType.ONE_SHOT, null);
            this.executed = executed;
        }

        @Override
        protected void action() {
            executed.set(true);
        }
    }
}
