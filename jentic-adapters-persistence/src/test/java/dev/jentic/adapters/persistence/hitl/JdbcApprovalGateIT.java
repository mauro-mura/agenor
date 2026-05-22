package dev.jentic.adapters.persistence.hitl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jentic.core.hitl.ApprovalDecision;
import dev.jentic.core.hitl.ApprovalRequest;
import dev.jentic.core.hitl.ApprovalTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link JdbcApprovalGate} using a real PostgreSQL database
 * managed by Testcontainers.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl jentic-adapters-persistence}
 *
 * @since 0.23.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("JdbcApprovalGate — integration tests (PostgreSQL)")
class JdbcApprovalGateIT {

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "jentic_test")
            .withEnv("POSTGRES_USER", "jentic")
            .withEnv("POSTGRES_PASSWORD", "jentic_test")
            .withStartupTimeout(Duration.ofSeconds(60));

    private HikariDataSource dataSource;
    private JdbcApprovalGate gate;
    private String jdbcUrl;

    @BeforeEach
    void setUp() {
        jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/jentic_test";
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername("jentic");
        cfg.setPassword("jentic_test");
        cfg.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(cfg);

        new HitlSchemaManager(dataSource, "classpath:db/migration/jentic-hitl").migrate();

        gate = new JdbcApprovalGate(dataSource, jdbcUrl);
        gate.recoverExpired();
    }

    @AfterEach
    void tearDown() {
        if (gate != null) gate.close();
        if (dataSource != null) dataSource.close();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("requestApproval persists row; submit(APPROVED) completes future and updates DB")
    void happyPath_approved() throws Exception {
        var request = ApprovalRequest.of("payment-agent", "process-payment", "€500", Duration.ofMinutes(5));
        var future = gate.requestApproval(request);

        gate.submit(request.requestId(), new ApprovalDecision.Approved());

        var decision = future.get(3, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ApprovalDecision.Approved.class);
        assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_APPROVED);
    }

    @Test
    @DisplayName("REJECTED decision propagates reason to future and DB")
    void happyPath_rejected() throws Exception {
        var request = ApprovalRequest.of("agent-x", "risky-action", "data", Duration.ofMinutes(5));
        var future = gate.requestApproval(request);

        gate.submit(request.requestId(), new ApprovalDecision.Rejected("policy violation"));

        var decision = future.get(3, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ApprovalDecision.Rejected.class);
        assertThat(((ApprovalDecision.Rejected) decision).reason()).isEqualTo("policy violation");
        assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_REJECTED);
    }

    // -------------------------------------------------------------------------
    // Timeout + recovery
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("timed-out request completes exceptionally and is marked EXPIRED")
    void timeout_markedExpiredInDb() throws Exception {
        var request = ApprovalRequest.of("agent-t", "short-action", "x", Duration.ofMillis(300));
        var future = gate.requestApproval(request);

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(ApprovalTimeoutException.class);

        assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_EXPIRED);
    }

    @Test
    @DisplayName("recoverExpired() on a new gate marks rows expired before startup")
    void recoverExpired_onRestart() throws Exception {
        // Submit a request with a very short timeout
        var request = ApprovalRequest.of("agent-r", "restart-action", "data", Duration.ofMillis(200));
        gate.requestApproval(request);

        // Wait for the row to expire in the DB (the gate's scheduler fires too, but we're testing recovery)
        Thread.sleep(500);

        // Simulate restart: new gate instance, no knowledge of the old future
        try (var newGate = new JdbcApprovalGate(dataSource, jdbcUrl)) {
            newGate.recoverExpired();
            assertDbStatus(request.requestId(), JdbcApprovalGate.STATUS_EXPIRED);
        }
    }

    // -------------------------------------------------------------------------
    // getPendingRequests — cross-node visibility
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getPendingRequests() returns rows created by another gate instance (cross-node)")
    void pendingRequests_visibleAcrossGates() {
        var r1 = ApprovalRequest.of("agent-a", "action-1", "p1", Duration.ofMinutes(5));
        var r2 = ApprovalRequest.of("agent-b", "action-2", "p2", Duration.ofMinutes(5));
        gate.requestApproval(r1);
        gate.requestApproval(r2);

        // Second gate (simulating another node, shares same DB)
        try (var gate2 = new JdbcApprovalGate(dataSource, jdbcUrl)) {
            var pending = gate2.getPendingRequests();
            assertThat(pending)
                    .extracting(ApprovalRequest::requestId)
                    .contains(r1.requestId(), r2.requestId());
        }
    }

    // -------------------------------------------------------------------------
    // DB assertion helper
    // -------------------------------------------------------------------------

    private void assertDbStatus(String requestId, String expectedStatus) {
        var helper = new dev.jentic.adapters.persistence.JdbcHelper(dataSource);
        String actual = helper.query(conn ->
                helper.queryOne(conn,
                        "SELECT status FROM jentic_hitl_requests WHERE request_id = ?",
                        java.util.List.of(requestId),
                        rs -> rs.getString("status"))
        ).join();
        assertThat(actual).isEqualTo(expectedStatus);
    }
}
