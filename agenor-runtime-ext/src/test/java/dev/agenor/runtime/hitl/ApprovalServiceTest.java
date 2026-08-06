package dev.agenor.runtime.hitl;

import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ApprovalService")
class ApprovalServiceTest {

    private ScheduledExecutorService scheduler;
    private InMemoryApprovalGate gate;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        gate = new InMemoryApprovalGate(scheduler);
        service = new ApprovalService(gate);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private ApprovalRequest req() {
        return ApprovalRequest.of("agent-1", "test-action", "payload", Duration.ofMinutes(5));
    }

    // -------------------------------------------------------------------------
    // approve()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("approve()")
    class ApproveTests {

        @Test
        @DisplayName("approve() completes the gate future with Approved")
        void approve_completesWithApproved() throws Exception {
            var request = req();
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            service.approve(request.requestId());

            assertThat(future.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ApprovalDecision.Approved.class);
        }

        @Test
        @DisplayName("approve() on unknown requestId throws IllegalArgumentException")
        void approve_unknownId_throws() {
            assertThatThrownBy(() -> service.approve("no-such-id"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no-such-id");
        }
    }

    // -------------------------------------------------------------------------
    // reject()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("reject()")
    class RejectTests {

        @Test
        @DisplayName("reject() completes the gate future with Rejected carrying the reason")
        void reject_completesWithRejected() throws Exception {
            var request = req();
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            service.reject(request.requestId(), "too sensitive");

            ApprovalDecision decision = future.get(1, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Rejected.class);
            assertThat(((ApprovalDecision.Rejected) decision).reason()).isEqualTo("too sensitive");
        }

        @Test
        @DisplayName("reject() with null reason throws NullPointerException")
        void reject_nullReason_throws() {
            var request = req();
            gate.requestApproval(request);
            assertThatThrownBy(() -> service.reject(request.requestId(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // modify()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("modify()")
    class ModifyTests {

        @Test
        @DisplayName("modify() completes the gate future with Modified carrying the new payload")
        void modify_completesWithModified() throws Exception {
            var request = req();
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            service.modify(request.requestId(), "revised-payload");

            ApprovalDecision decision = future.get(1, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Modified.class);
            assertThat(((ApprovalDecision.Modified) decision).newPayload()).isEqualTo("revised-payload");
        }

        @Test
        @DisplayName("modify() with null newPayload throws NullPointerException")
        void modify_nullPayload_throws() {
            var request = req();
            gate.requestApproval(request);
            assertThatThrownBy(() -> service.modify(request.requestId(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // getPendingRequests()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getPendingRequests()")
    class PendingRequestsTests {

        @Test
        @DisplayName("returns active requests before any decision")
        void returnsPendingRequests() {
            var r1 = req();
            var r2 = req();
            gate.requestApproval(r1);
            gate.requestApproval(r2);

            var pending = service.getPendingRequests();
            assertThat(pending)
                    .extracting(ApprovalRequest::requestId)
                    .contains(r1.requestId(), r2.requestId());
        }

        @Test
        @DisplayName("decided request is not in pending list")
        void decidedRequest_notInPending() throws Exception {
            var request = req();
            gate.requestApproval(request);
            service.approve(request.requestId());

            // Allow whenComplete callback to fire
            Thread.sleep(50);

            assertThat(service.getPendingRequests())
                    .noneMatch(r -> r.requestId().equals(request.requestId()));
        }

        @Test
        @DisplayName("expired request is not in pending list")
        void expiredRequest_notInPending() throws Exception {
            var request = ApprovalRequest.of("agent-1", "action", "p", Duration.ofMillis(80));
            gate.requestApproval(request);

            Thread.sleep(300);

            assertThat(service.getPendingRequests())
                    .noneMatch(r -> r.requestId().equals(request.requestId()));
        }
    }

    // -------------------------------------------------------------------------
    // Constructor guard
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null gate throws NullPointerException")
    void nullGate_throws() {
        assertThatThrownBy(() -> new ApprovalService(null))
                .isInstanceOf(NullPointerException.class);
    }
}
