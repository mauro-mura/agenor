package dev.agenor.examples.hitl;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import dev.agenor.core.annotations.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalNotifier;
import dev.agenor.core.hitl.RequiresApproval;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.behavior.CyclicBehavior;
import dev.agenor.runtime.behavior.advanced.HumanCheckpointBehavior;
import dev.agenor.runtime.hitl.InMemoryApprovalGate;
import dev.agenor.runtime.hitl.LoggingApprovalNotifier;

/**
 * Demonstrates the Human-in-the-Loop Checkpoint pattern (ADR-015) with real
 * Jentic agents registered in {@link AgenorRuntime}.
 *
 * <p>A {@link TreasuryAgent} submits large wire transfers as
 * {@link HumanCheckpointBehavior}s. An {@link OperatorAgent} polls the shared
 * {@link InMemoryApprovalGate} and submits decisions after 1 second.
 *
 * <p>Three transfers are processed:
 * <ol>
 *   <li>TRF-001 (€ 1,500) — approved as-is</li>
 *   <li>TRF-002 (€ 50,000) — rejected (exceeds daily limit)</li>
 *   <li>TRF-003 (€ 9,999) — modified to € 5,000 (partial approval)</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   mvn exec:java -pl agenor-examples -Dexec.mainClass=dev.agenor.examples.HitlExample
 * </pre>
 *
 * @since 0.13.0
 */
public class HitlExample {

    private static final Logger log = LoggerFactory.getLogger(HitlExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Jentic HITL Checkpoint Example ===\n");

        // Shared gate: TreasuryAgent registers requests, OperatorAgent reads pending
        InMemoryApprovalGate sharedGate = new InMemoryApprovalGate();

        // Latch: 3 transfers, main thread waits for all completions
        CountDownLatch done = new CountDownLatch(3);

        AgenorRuntime runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new TreasuryAgent(sharedGate, done));
        runtime.registerAgent(new OperatorAgent(sharedGate));
        runtime.start().join();

        done.await();

        log.info("\n=== All transfers processed — shutting down ===");
        runtime.stop().join();
    }

    // =========================================================================
    // Domain record
    // =========================================================================

    record WireTransfer(String id, String beneficiary, double amount) {
        @Override public String toString() {
            return String.format("%s → %s € %.2f", id, beneficiary, amount);
        }
    }

    // =========================================================================
    // TreasuryAgent
    // Registers one HumanCheckpointBehavior per transfer in onStart().
    // =========================================================================

    @Agent(value = "treasury-agent", type = "finance",
                 capabilities = {"wire-transfer", "hitl"})
    static class TreasuryAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(TreasuryAgent.class);

        private final ApprovalGate gate;
        private final CountDownLatch done;

        TreasuryAgent(ApprovalGate gate, CountDownLatch done) {
            super("treasury-agent", "Treasury Agent");
            this.gate = gate;
            this.done = done;
        }

        @Override
        protected void onStart() {
            log.info("Treasury agent started — queuing 3 wire transfers for approval");

            addTransfer(new WireTransfer("TRF-001", "Supplier A", 1_500.00));
            addTransfer(new WireTransfer("TRF-002", "Supplier B", 50_000.00));
            addTransfer(new WireTransfer("TRF-003", "Supplier C", 9_999.00));
        }

        private void addTransfer(WireTransfer transfer) {
            ApprovalNotifier notifier = new LoggingApprovalNotifier();

            HumanCheckpointBehavior<WireTransfer> checkpoint =
                    new HumanCheckpointBehavior<>(
                            "transfer-" + transfer.id(),
                            gate,
                            notifier,
                            transfer,
                            "wire-transfer",
                            Duration.ofMinutes(2),
                            decision -> {
                                switch (decision) {
                                    case ApprovalDecision.Approved a -> {
                                        log.info("✅ APPROVED  — executing {}", transfer);
                                        executeTransfer(transfer);
                                    }
                                    case ApprovalDecision.Rejected r ->
                                        log.warn("❌ REJECTED  — {} | reason: {}", transfer.id(), r.reason());
                                    case ApprovalDecision.Modified m -> {
                                        WireTransfer revised = (WireTransfer) m.newPayload();
                                        log.info("✏️  MODIFIED  — executing revised {}", revised);
                                        executeTransfer(revised);
                                    }
                                }
                                done.countDown();
                            }
                    );
            checkpoint.setAgent(this);
            addBehavior(checkpoint);
        }

        private void executeTransfer(WireTransfer t) {
            log.info("   → Bank transfer sent: {}", t);
        }
    }

    // =========================================================================
    // OperatorAgent
    // Polls pending approvals every second and submits decisions.
    // In production this would be driven by an HTTP/webhook callback;
    // here it simulates a human operator at a console.
    // =========================================================================

    @Agent(value = "operator-agent", type = "ops",
                 capabilities = {"approval-console"})
    static class OperatorAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(OperatorAgent.class);

        private final InMemoryApprovalGate gate;

        OperatorAgent(InMemoryApprovalGate gate) {
            super("operator-agent", "Operator Agent");
            this.gate = gate;
        }

        @Override
        protected void onStart() {
            log.info("Operator console started — polling for pending approvals every second");
            addBehavior(CyclicBehavior.from("approval-poller", Duration.ofSeconds(1),
                    this::reviewPending));
        }

        private void reviewPending() {
            List<dev.agenor.core.hitl.ApprovalRequest> pending = gate.getPendingRequests();
            if (pending.isEmpty()) return;

            // Simulate operator reaction time: decide after the first poll finds requests
            for (dev.agenor.core.hitl.ApprovalRequest req : pending) {
                String id = req.requestId();
                Object payload = req.payload();

                if (!(payload instanceof WireTransfer transfer)) continue;

                log.info("Operator reviewing: {} (€ {})", transfer.id(), transfer.amount());

                if (transfer.amount() > 30_000) {
                    gate.submit(id, new ApprovalDecision.Rejected("Exceeds €30k daily limit"));
                } else if (transfer.amount() > 5_000) {
                    double reduced = transfer.amount() * 0.5;
                    gate.submit(id, new ApprovalDecision.Modified(
                            new WireTransfer(transfer.id(), transfer.beneficiary(), reduced)));
                } else {
                    gate.submit(id, new ApprovalDecision.Approved());
                }
            }
        }
    }

    // =========================================================================
    // Annotation-based variant — for documentation purposes.
    // After T7 patch, registering this behavior via AgenorRuntime will cause
    // HitlAnnotationProcessor to wrap it automatically in HumanCheckpointBehavior.
    // =========================================================================

    @RequiresApproval(timeout = "2m", notifier = LoggingApprovalNotifier.class)
    static class WireTransferBehavior extends BaseBehavior {

        private static final Logger log = LoggerFactory.getLogger(WireTransferBehavior.class);
        private final WireTransfer transfer;

        WireTransferBehavior(WireTransfer transfer) {
            super("annotated-transfer-" + transfer.id(), BehaviorType.ONE_SHOT, null);
            this.transfer = transfer;
        }

        @Override
        protected void action() {
            log.info("Executing wire transfer (post-approval): {}", transfer);
        }
    }
}
