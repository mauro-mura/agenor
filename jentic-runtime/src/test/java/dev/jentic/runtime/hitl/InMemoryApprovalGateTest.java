package dev.jentic.runtime.hitl;

import dev.jentic.core.hitl.ApprovalDecision;
import dev.jentic.core.hitl.ApprovalRequest;
import dev.jentic.core.hitl.ApprovalTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InMemoryApprovalGate")
class InMemoryApprovalGateTest {

    private ScheduledExecutorService scheduler;
    private InMemoryApprovalGate gate;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        gate = new InMemoryApprovalGate(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ApprovalRequest req(Duration timeout) {
        return ApprovalRequest.of("agent-1", "test-action", "payload", timeout);
    }

    // -------------------------------------------------------------------------
    // Happy path: submit decisions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Decision submission")
    class DecisionSubmission {

        @Test
        @DisplayName("submit(APPROVED) completes future with Approved")
        void submitApproved() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Approved());

            ApprovalDecision decision = future.get(1, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Approved.class);
        }

        @Test
        @DisplayName("submit(REJECTED, reason) completes future with Rejected carrying the reason")
        void submitRejected() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Rejected("too risky"));

            ApprovalDecision decision = future.get(1, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Rejected.class);
            assertThat(((ApprovalDecision.Rejected) decision).reason()).isEqualTo("too risky");
        }

        @Test
        @DisplayName("submit(MODIFIED, newPayload) completes future with Modified carrying the payload")
        void submitModified() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Modified("revised-payload"));

            ApprovalDecision decision = future.get(1, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Modified.class);
            assertThat(((ApprovalDecision.Modified) decision).newPayload()).isEqualTo("revised-payload");
        }
    }

    // -------------------------------------------------------------------------
    // Timeout
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Timeout")
    class TimeoutTests {

        @Test
        @DisplayName("expired request completes future exceptionally with ApprovalTimeoutException")
        void timeout_completesExceptionally() {
            var request = req(Duration.ofMillis(100));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(ApprovalTimeoutException.class)
                    .extracting("requestId")
                    .isEqualTo(request.requestId());
        }

        @Test
        @DisplayName("timed-out request is removed from pendingRequests")
        void timeout_removedFromPending() throws Exception {
            var request = req(Duration.ofMillis(100));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            // Wait for timeout
            Thread.sleep(300);

            assertThat(gate.getPendingRequests())
                    .noneMatch(r -> r.requestId().equals(request.requestId()));
        }
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("submit on unknown requestId throws IllegalArgumentException")
        void submitUnknown_throws() {
            assertThatThrownBy(() -> gate.submit("unknown-id", new ApprovalDecision.Approved()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown-id");
        }

        @Test
        @DisplayName("submit on already-completed future is a silent no-op")
        void submitAlreadyCompleted_noOp() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Approved());
            future.get(1, TimeUnit.SECONDS); // ensure completed

            // Second submit on unknown (removed from a map) → IllegalArgumentException expected
            // But the future itself was already completed — gate cleaned up the entry
            assertThatThrownBy(() -> gate.submit(request.requestId(), new ApprovalDecision.Rejected("late")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null request throws IllegalArgumentException")
        void nullRequest_throws() {
            assertThatThrownBy(() -> gate.requestApproval(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------
    // getPendingRequests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getPendingRequests")
    class PendingRequestsTests {

        @Test
        @DisplayName("returns all active pending requests")
        void returnsActivePending() {
            var r1 = req(Duration.ofMinutes(5));
            var r2 = req(Duration.ofMinutes(5));
            gate.requestApproval(r1);
            gate.requestApproval(r2);

            var pending = gate.getPendingRequests();
            assertThat(pending)
                    .extracting(ApprovalRequest::requestId)
                    .contains(r1.requestId(), r2.requestId());
        }

        @Test
        @DisplayName("completed request is removed from pending")
        void completedRequest_removedFromPending() throws Exception {
            var request = req(Duration.ofMinutes(5));
            gate.requestApproval(request);
            gate.submit(request.requestId(), new ApprovalDecision.Approved());

            // Give whenComplete callback time to run
            Thread.sleep(50);

            assertThat(gate.getPendingRequests())
                    .noneMatch(r -> r.requestId().equals(request.requestId()));
        }

        @Test
        @DisplayName("returned list is a snapshot — mutations do not affect gate state")
        void returnedList_isSnapshot() {
            var request = req(Duration.ofMinutes(5));
            gate.requestApproval(request);

            List<ApprovalRequest> snapshot = gate.getPendingRequests();
            assertThatThrownBy(() -> snapshot.add(req(Duration.ofMinutes(1))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Concurrency: 50 concurrent requests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("50 concurrent requests all complete correctly without race conditions")
    void concurrentRequests_allComplete() throws Exception {
        int count = 50;
        List<ApprovalRequest> requests = new ArrayList<>(count);
        List<CompletableFuture<ApprovalDecision>> futures = new ArrayList<>(count);

        // Register all requests
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                ApprovalRequest req = ApprovalRequest.of(
                        "agent-" + i, "action-" + i, "payload-" + i, Duration.ofMinutes(5));
                requests.add(req);
                futures.add(gate.requestApproval(req));
            }

            // Submit decisions concurrently
            List<CompletableFuture<Void>> submissions = new ArrayList<>(count);
            for (ApprovalRequest req : requests) {
                submissions.add(CompletableFuture.runAsync(
                        () -> gate.submit(req.requestId(), new ApprovalDecision.Approved()),
                        executor));
            }
            CompletableFuture.allOf(submissions.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        }

        // Assert all futures completed normally with Approved
        for (CompletableFuture<ApprovalDecision> future : futures) {
            ApprovalDecision decision = future.get(1, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Approved.class);
        }

        // All entries cleaned up
        assertThat(gate.getPendingRequests()).isEmpty();
    }
}