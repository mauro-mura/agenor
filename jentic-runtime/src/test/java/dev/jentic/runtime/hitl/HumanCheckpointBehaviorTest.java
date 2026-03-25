package dev.jentic.runtime.hitl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.jentic.core.hitl.ApprovalDecision;
import dev.jentic.core.hitl.ApprovalRequest;
import dev.jentic.core.hitl.ApprovalTimeoutException;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.behavior.advanced.HumanCheckpointBehavior;

@DisplayName("HumanCheckpointBehavior")
class HumanCheckpointBehaviorTest {

    private ScheduledExecutorService scheduler;
    private InMemoryApprovalGate gate;
    private ApprovalService service;
    private RecordingNotifier notifier;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        gate = new InMemoryApprovalGate(scheduler);
        service = new ApprovalService(gate);
        notifier = new RecordingNotifier();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    /** Notifier that records the last request it received. */
    static class RecordingNotifier implements dev.jentic.core.hitl.ApprovalNotifier {
        volatile ApprovalRequest last;
        volatile int callCount;

        @Override
        public void notify(ApprovalRequest request) {
            this.last = request;
            this.callCount++;
        }
    }

    /** Notifier that always throws. */
    static class ThrowingNotifier implements dev.jentic.core.hitl.ApprovalNotifier {
        @Override
        public void notify(ApprovalRequest request) {
            throw new RuntimeException("notifier failure");
        }
    }

    static class TestAgent extends BaseAgent {
        TestAgent() { super("test-agent", "Test Agent"); }
    }

    private HumanCheckpointBehavior<String> behavior(
            Duration timeout,
            java.util.function.Consumer<ApprovalDecision> handler) {
        var b = new HumanCheckpointBehavior<>(
                "test-checkpoint", gate, notifier, "original-payload", "test-action",
                timeout, handler);
        b.setAgent(new TestAgent());
        return b;
    }

    // -------------------------------------------------------------------------
    // Approved
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Approved decision")
    class ApprovedTests {

        @Test
        @DisplayName("Approved → decisionHandler called with Approved; original payload available")
        void approved_handlerCalled() throws Exception {
            AtomicBoolean handlerCalled = new AtomicBoolean(false);
            AtomicReference<ApprovalDecision> received = new AtomicReference<>();

            var b = behavior(Duration.ofMinutes(5), d -> {
                handlerCalled.set(true);
                received.set(d);
            });

            // Submit approval asynchronously after behavior starts
            var future = b.execute();
            Thread.sleep(50); // let behavior reach gate.requestApproval().join()
            service.approve(gate.getPendingRequests().getFirst().requestId());
            future.get(2, TimeUnit.SECONDS);

            assertThat(handlerCalled).isTrue();
            assertThat(received.get()).isInstanceOf(ApprovalDecision.Approved.class);
        }
    }

    // -------------------------------------------------------------------------
    // Rejected
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Rejected decision")
    class RejectedTests {

        @Test
        @DisplayName("Rejected → decisionHandler called with Rejected; critical action NOT invoked separately")
        void rejected_handlerCalledWithRejected() throws Exception {
            AtomicBoolean criticalActionCalled = new AtomicBoolean(false);
            AtomicReference<ApprovalDecision> received = new AtomicReference<>();

            var b = behavior(Duration.ofMinutes(5), d -> {
                received.set(d);
                if (d instanceof ApprovalDecision.Approved) {
                    criticalActionCalled.set(true);
                }
                // Rejected: skip critical action
            });

            var future = b.execute();
            Thread.sleep(50);
            service.reject(gate.getPendingRequests().getFirst().requestId(), "budget exceeded");
            future.get(2, TimeUnit.SECONDS);

            assertThat(received.get()).isInstanceOf(ApprovalDecision.Rejected.class);
            assertThat(((ApprovalDecision.Rejected) received.get()).reason())
                    .isEqualTo("budget exceeded");
            assertThat(criticalActionCalled).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Modified
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Modified decision")
    class ModifiedTests {

        @Test
        @DisplayName("Modified → decisionHandler called with Modified carrying the new payload")
        void modified_handlerCalledWithModified() throws Exception {
            AtomicReference<Object> usedPayload = new AtomicReference<>();

            var b = behavior(Duration.ofMinutes(5), d -> {
                if (d instanceof ApprovalDecision.Modified m) {
                    usedPayload.set(m.newPayload());
                } else if (d instanceof ApprovalDecision.Approved) {
                    usedPayload.set("original-payload");
                }
            });

            var future = b.execute();
            Thread.sleep(50);
            service.modify(gate.getPendingRequests().getFirst().requestId(), "revised-payload");
            future.get(2, TimeUnit.SECONDS);

            assertThat(usedPayload.get()).isEqualTo("revised-payload");
        }
    }

    // -------------------------------------------------------------------------
    // Timeout
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Timeout")
    class TimeoutTests {

        @Test
        @DisplayName("timeout → ApprovalTimeoutException delivered to onError(); future completes normally")
        void timeout_deliveredToOnError() throws Exception {
            // BaseBehavior.execute() catches all Throwables and routes them to onError();
            // the CompletableFuture itself completes normally (returns null).
            AtomicReference<Exception> capturedError = new AtomicReference<>();

            var b = new HumanCheckpointBehavior<>(
                    "timeout-checkpoint", gate, notifier, "p", "action",
                    Duration.ofMillis(100), d -> {}) {
                @Override
                protected void onError(Exception e) {
                    capturedError.set(e);
                }
            };
            b.setAgent(new TestAgent());

            // execute() future completes normally — BaseBehavior swallows the exception
            b.execute().get(2, TimeUnit.SECONDS);

            assertThat(capturedError.get())
                    .satisfies(e -> {
                        Throwable root = e.getCause() != null ? e.getCause() : e;
                        assertThat(root).isInstanceOf(ApprovalTimeoutException.class);
                        assertThat(((ApprovalTimeoutException) root).getRequestId()).isNotNull();
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Notifier
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Notifier behavior")
    class NotifierTests {

        @Test
        @DisplayName("notifier.notify() called exactly once before gate parks")
        void notifier_calledOnce() throws Exception {
            var b = behavior(Duration.ofMinutes(5), d -> {});
            var future = b.execute();
            Thread.sleep(50);
            service.approve(gate.getPendingRequests().getFirst().requestId());
            future.get(2, TimeUnit.SECONDS);

            assertThat(notifier.callCount).isEqualTo(1);
            assertThat(notifier.last).isNotNull();
            assertThat(notifier.last.action()).isEqualTo("test-action");
        }

        @Test
        @DisplayName("throwing notifier does not abort behavior execution")
        void throwingNotifier_doesNotAbort() throws Exception {
            AtomicBoolean handlerCalled = new AtomicBoolean(false);

            var b = new HumanCheckpointBehavior<>(
                    "resilient-checkpoint", gate, new ThrowingNotifier(),
                    "p", "action", Duration.ofMinutes(5),
                    d -> handlerCalled.set(true));
            b.setAgent(new TestAgent());

            var future = b.execute();
            Thread.sleep(50);
            service.approve(gate.getPendingRequests().getFirst().requestId());
            future.get(2, TimeUnit.SECONDS);

            assertThat(handlerCalled).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Constructor guards
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null gate throws NullPointerException")
    void nullGate_throws() {
        assertThatThrownBy(() -> new HumanCheckpointBehavior<>(
                "id", null, notifier, "p", "action", Duration.ofMinutes(1), d -> {}))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null decisionHandler throws NullPointerException")
    void nullHandler_throws() {
        assertThatThrownBy(() -> new HumanCheckpointBehavior<>(
                "id", gate, notifier, "p", "action", Duration.ofMinutes(1), null))
                .isInstanceOf(NullPointerException.class);
    }
}