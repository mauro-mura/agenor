package dev.agenor.adapters.persistence.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.hitl.ApprovalTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JdbcApprovalGate} using an in-process H2 database.
 */
@DisplayName("JdbcApprovalGate")
class JdbcApprovalGateTest {

    private HikariDataSource dataSource;
    private JdbcApprovalGate gate;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:hitl_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(cfg);

        new HitlSchemaManager(dataSource, "classpath:db/migration/agenor-hitl").migrate();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        gate = new JdbcApprovalGate(
                new JdbcHelper(dataSource),
                new ObjectMapper(),
                scheduler,
                false,  // no Postgres NOTIFY in unit tests
                null);
    }

    @AfterEach
    void tearDown() {
        if (gate != null) gate.close();
        scheduler.shutdownNow();
        if (dataSource != null) dataSource.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ApprovalRequest req(Duration timeout) {
        return ApprovalRequest.of("agent-1", "test-action", "payload", timeout);
    }

    // -------------------------------------------------------------------------
    // Happy path: decision submission
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Decision submission")
    class DecisionSubmission {

        @Test
        @DisplayName("APPROVED completes future and persists decision")
        void submitApproved() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Approved());

            var decision = future.get(2, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Approved.class);

            // Row must be APPROVED in DB
            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_APPROVED);
        }

        @Test
        @DisplayName("REJECTED completes future with reason and persists it")
        void submitRejected() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Rejected("too risky"));

            var decision = future.get(2, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Rejected.class);
            assertThat(((ApprovalDecision.Rejected) decision).reason()).isEqualTo("too risky");

            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_REJECTED);
        }

        @Test
        @DisplayName("MODIFIED completes future with newPayload and persists it")
        void submitModified() throws Exception {
            var request = req(Duration.ofMinutes(5));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            gate.submit(request.requestId(), new ApprovalDecision.Modified("revised"));

            var decision = future.get(2, TimeUnit.SECONDS);
            assertThat(decision).isInstanceOf(ApprovalDecision.Modified.class);
            assertThat(((ApprovalDecision.Modified) decision).newPayload()).isEqualTo("revised");

            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_MODIFIED);
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

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(ApprovalTimeoutException.class);
        }

        @Test
        @DisplayName("timed-out request is marked EXPIRED in DB")
        void timeout_markedExpiredInDb() throws Exception {
            var request = req(Duration.ofMillis(100));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);

            // Wait for timeout
            Thread.sleep(500);

            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_EXPIRED);
        }

        @Test
        @DisplayName("the row is marked EXPIRED before the caller learns of the timeout")
        void timeout_dbIsWrittenBeforeTheFutureCompletes() throws Exception {
            // This asserts an ordering, so it holds the write open rather than racing it. A
            // timing-based version cannot do the job: with the write second, H2 in-process
            // closes the window fast enough that the test passes anyway — which is exactly how
            // the defect survived, green here and red only under a loaded full build.
            var blocked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var armed = new AtomicBoolean(false);
            var slowScheduler = Executors.newSingleThreadScheduledExecutor();

            var slowGate = new JdbcApprovalGate(
                    new JdbcHelper(blockingDataSource(armed, blocked, release)),
                    new ObjectMapper(), slowScheduler, false, null);
            try {
                // Given a request whose deadline is about to pass
                var request = req(Duration.ofMillis(300));
                CompletableFuture<ApprovalDecision> future = slowGate.requestApproval(request);

                // When the timeout fires and its UPDATE is held open
                armed.set(true);
                assertThat(blocked.await(5, TimeUnit.SECONDS))
                        .as("the timeout task reached the database").isTrue();

                // Then the caller has not been told anything yet. Releasing it first would
                // hand out an ApprovalTimeoutException while the row still read PENDING.
                assertThat(future).isNotDone();

                // And once the write goes through, both agree
                release.countDown();
                assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ApprovalTimeoutException.class);
                assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_EXPIRED);
            } finally {
                release.countDown();
                slowGate.close();
                slowScheduler.shutdownNow();
            }
        }

        /**
         * Wraps the shared {@link DataSource} so that, once armed, the next connection request
         * blocks. The timeout task acquires its connection inside {@code markExpired}, so this
         * suspends the gate precisely between deciding to expire and having expired.
         */
        private DataSource blockingDataSource(AtomicBoolean armed,
                                              CountDownLatch blocked,
                                              CountDownLatch release) {
            return (DataSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())
                                && armed.compareAndSet(true, false)) {
                            blocked.countDown();
                            release.await(5, TimeUnit.SECONDS);
                        }
                        try {
                            return method.invoke(dataSource, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Test
        @DisplayName("a decision submitted before the deadline is not expired afterwards")
        void decisionBeatsTheDeadline() throws Exception {
            // Given a request decided while still pending
            var request = req(Duration.ofMillis(300));
            CompletableFuture<ApprovalDecision> future = gate.requestApproval(request);
            gate.submit(request.requestId(), new ApprovalDecision.Approved());

            // When its deadline passes anyway
            Thread.sleep(600);

            // Then the timeout does not overwrite the decision — the conditional UPDATE no
            // longer matches, so nothing is expired and the caller keeps its answer
            assertThat(future.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ApprovalDecision.Approved.class);
            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_APPROVED);
        }

        @Test
        @DisplayName("timed-out request is removed from getPendingRequests()")
        void timeout_removedFromPending() throws Exception {
            var request = req(Duration.ofMillis(100));
            gate.requestApproval(request);

            Thread.sleep(500);

            assertThat(gate.getPendingRequests())
                    .noneMatch(r -> r.requestId().equals(request.requestId()));
        }
    }

    // -------------------------------------------------------------------------
    // getPendingRequests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getPendingRequests")
    class PendingRequestsTests {

        @Test
        @DisplayName("returns PENDING requests from DB (visible across restarts)")
        void returnsPendingFromDb() {
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
        @DisplayName("decided request is removed from pending")
        void decidedRequest_removedFromPending() throws Exception {
            var request = req(Duration.ofMinutes(5));
            gate.requestApproval(request);
            gate.submit(request.requestId(), new ApprovalDecision.Approved());

            Thread.sleep(50); // let whenComplete run

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
        @DisplayName("null request throws IllegalArgumentException")
        void nullRequest_throws() {
            assertThatThrownBy(() -> gate.requestApproval(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("submit on unknown requestId throws IllegalArgumentException")
        void submitUnknown_throws() {
            assertThatThrownBy(() -> gate.submit("unknown-id", new ApprovalDecision.Approved()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown-id");
        }

        @Test
        @DisplayName("second submit on already-decided request is a silent no-op")
        void submitAlreadyDecided_noOp() throws Exception {
            var request = req(Duration.ofMinutes(5));
            gate.requestApproval(request);
            gate.submit(request.requestId(), new ApprovalDecision.Approved());
            Thread.sleep(50);

            // second submit: no exception, row stays APPROVED
            gate.submit(request.requestId(), new ApprovalDecision.Rejected("late"));
            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_APPROVED);
        }
    }

    // -------------------------------------------------------------------------
    // Startup recovery
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Startup recovery")
    class StartupRecovery {

        @Test
        @DisplayName("recoverExpired() marks already-expired PENDING rows as EXPIRED")
        void recoverExpired_marksExpired() throws Exception {
            var request = req(Duration.ofMillis(50));
            gate.requestApproval(request);

            // Wait for the row to be past expires_at
            Thread.sleep(200);

            // Run recovery on a fresh gate (simulating restart)
            try (var newGate = new JdbcApprovalGate(
                    new JdbcHelper(dataSource), new ObjectMapper(),
                    Executors.newSingleThreadScheduledExecutor(),
                    false, null)) {
                newGate.recoverExpired();
                assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_EXPIRED);
            }
        }

        @Test
        @DisplayName("recoverExpired() does not touch unexpired PENDING rows")
        void recoverExpired_leavesUnexpired() {
            var request = req(Duration.ofMinutes(5));
            gate.requestApproval(request);

            gate.recoverExpired();

            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_PENDING);
        }
    }

    // -------------------------------------------------------------------------
    // DB assertion helper
    // -------------------------------------------------------------------------

    private void assertDbStatus(String requestId, String expectedStatus) {
        var helper = new JdbcHelper(dataSource);
        String actualStatus = helper.query(conn ->
                helper.queryOne(conn,
                        "SELECT status FROM agenor_hitl_requests WHERE request_id = ?",
                        java.util.List.of(requestId),
                        rs -> rs.getString("status"))
        ).join();
        assertThat(actualStatus).isEqualTo(expectedStatus);
    }
}
