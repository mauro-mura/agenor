package dev.agenor.adapters.persistence.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link JdbcAgentPresence} against a real PostgreSQL database
 * (ADR-028 Phase A, task 7).
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters-persistence}
 *
 * <p>These cover what the H2 unit tests structurally cannot. Staleness is arithmetic on a
 * {@code TIMESTAMP} that crosses the JDBC driver twice, so its correctness depends on the
 * driver's conversion rather than on the code under test; and presence is only useful because
 * several nodes share one table, which one in-process database with one pool never exercises.
 * The unit tests also backdate {@code last_seen} by hand — here time simply passes, so the
 * whole path from {@code Instant.now()} to a stale verdict runs as it will in production.
 *
 * <p>Every node in these tests is a separate {@link JdbcAgentDirectory}, which means a
 * separate connection pool, exactly as two runtimes on two machines would be.
 *
 * @since 0.26.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("JdbcAgentPresence — integration tests (PostgreSQL)")
class JdbcAgentPresenceIT {

    private static final String DB = "agenor_test";
    private static final String USER = "agenor";
    private static final String PASSWORD = "agenor_test";

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withStartupTimeout(Duration.ofSeconds(60));

    /** Two nodes sharing one table, each with its own pool. */
    private static JdbcAgentDirectory nodeA;
    private static JdbcAgentDirectory nodeB;

    @BeforeAll
    static void startNodes() {
        nodeA = JdbcAgentDirectory.create(JdbcDirectoryConfig.of(jdbcUrl(), USER, PASSWORD));
        nodeB = JdbcAgentDirectory.create(JdbcDirectoryConfig.of(jdbcUrl(), USER, PASSWORD));
    }

    @AfterAll
    static void stopNodes() {
        if (nodeA != null) nodeA.close();
        if (nodeB != null) nodeB.close();
    }

    // -------------------------------------------------------------------------
    // The timestamp round-trip staleness is built on
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("last_seen survives the round-trip to PostgreSQL and back")
    void lastSeenSurvivesTheRoundTrip() throws Exception {
        // Given a registered agent
        register(nodeA, "round-trip");

        // When it heartbeats at a known moment
        var before = Instant.now();
        nodeA.presence().heartbeat("round-trip").join();
        var after = Instant.now();

        // Then the value PostgreSQL stored maps back to that moment. The column is a
        // TIMESTAMP without time zone and the driver converts through the JVM's default
        // zone in both directions: a mismatch would shift last_seen by whole hours, and
        // every agent would read as permanently fresh or permanently stale.
        var stored = readLastSeen("round-trip");
        assertThat(stored).isBetween(before.minusMillis(1000), after.plusMillis(1000));
    }

    // -------------------------------------------------------------------------
    // Real elapsed time, not a backdated column
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an agent goes UNKNOWN once its window passes, and a heartbeat brings it back")
    void agentExpiresThenRevives() {
        // Given a one-second view of an agent that has just registered
        var presence = nodeA.presence(Duration.ofSeconds(1));
        register(nodeA, "expiring", AgentStatus.RUNNING);
        assertThat(presence.getStatus("expiring").join()).isEqualTo(AgentStatus.RUNNING);

        // When a full window passes with no heartbeat
        sleep(1400);

        // Then the stored RUNNING is no longer trusted
        assertThat(presence.getStatus("expiring").join()).isEqualTo(AgentStatus.UNKNOWN);

        // And when it beats again, it returns with the status it always had
        presence.heartbeat("expiring").join();
        assertThat(presence.getStatus("expiring").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("a heartbeat does not promote the status it refreshes")
    void heartbeatDoesNotPromoteStatus() {
        // Given an agent that never left STARTING
        var presence = nodeA.presence(Duration.ofSeconds(30));
        register(nodeA, "stuck-starting", AgentStatus.STARTING);

        // When it heartbeats
        presence.heartbeat("stuck-starting").join();

        // Then it is alive and still STARTING — a heartbeat is liveness, not progress
        assertThat(presence.getStatus("stuck-starting").join()).isEqualTo(AgentStatus.STARTING);
    }

    // -------------------------------------------------------------------------
    // Several nodes, one table
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a node that stops beating goes UNKNOWN while the live one stays visible")
    void stoppedNodeExpiresWhileLiveNodeStaysVisible() {
        // Given one agent per node, seen through a shared one-second window
        register(nodeA, "live-agent", AgentStatus.RUNNING);
        register(nodeB, "crashed-agent", AgentStatus.RUNNING);
        var observer = nodeA.presence(Duration.ofSeconds(1));

        // When node A keeps beating for two seconds and node B never does — the process
        // died, so nothing unregistered its agent and the row it left behind still says
        // RUNNING
        for (int i = 0; i < 7; i++) {
            nodeA.presence().heartbeat("live-agent").join();
            sleep(300);
        }

        // Then the observer separates them: liveness is what was reported, not what the
        // row claims
        assertThat(observer.getStatus("live-agent").join()).isEqualTo(AgentStatus.RUNNING);
        assertThat(observer.getStatus("crashed-agent").join()).isEqualTo(AgentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("a heartbeat on one node is visible from the other")
    void heartbeatCrossesNodes() {
        // Given an agent registered on node B and gone stale
        var observer = nodeA.presence(Duration.ofSeconds(1));
        register(nodeB, "cross-node", AgentStatus.RUNNING);
        sleep(1400);
        assertThat(observer.getStatus("cross-node").join()).isEqualTo(AgentStatus.UNKNOWN);

        // When node B heartbeats it
        nodeB.presence().heartbeat("cross-node").join();

        // Then node A sees it immediately — the write is committed, not pooled or buffered
        assertThat(observer.getStatus("cross-node").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("both nodes heartbeating at once lose nothing")
    void concurrentHeartbeatsFromTwoNodes() {
        // Given four agents beaten from both nodes at the same time
        var agents = List.of("concurrent-1", "concurrent-2", "concurrent-3", "concurrent-4");
        agents.forEach(id -> register(nodeA, id, AgentStatus.RUNNING));

        // When two hundred heartbeats are issued concurrently across two pools
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (int round = 0; round < 25; round++) {
            for (String id : agents) {
                futures.add(nodeA.presence().heartbeat(id));
                futures.add(nodeB.presence().heartbeat(id));
            }
        }
        var all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        // Then none of them fails or wedges — a single-row UPDATE on a primary key does not
        // take locks that can order themselves into a deadlock
        assertThatCode(() -> all.orTimeout(30, TimeUnit.SECONDS).join())
                .doesNotThrowAnyException();

        // And every agent is alive, still present, and no row was created or destroyed
        var presence = nodeA.presence(Duration.ofSeconds(30));
        for (String id : agents) {
            assertThat(presence.getStatus(id).join()).isEqualTo(AgentStatus.RUNNING);
            assertThat(nodeA.discovery().findById(id).join()).isPresent();
        }
    }

    @Test
    @DisplayName("a heartbeat racing a status update leaves the row consistent")
    void heartbeatRacingStatusUpdate() {
        // Given one agent written by two nodes at once: node B refreshing last_seen, node A
        // writing status and last_seen together. Both target the same row.
        register(nodeA, "contended", AgentStatus.RUNNING);

        var futures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 40; i++) {
            futures.add(nodeB.presence().heartbeat("contended"));
            futures.add(nodeA.registry().updateStatus("contended", AgentStatus.ERROR));
        }
        var all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

        // When they all complete
        assertThatCode(() -> all.orTimeout(30, TimeUnit.SECONDS).join())
                .doesNotThrowAnyException();

        // Then the status is the one that was written, not one a heartbeat invented, and the
        // agent is alive
        assertThat(nodeA.presence(Duration.ofSeconds(30)).getStatus("contended").join())
                .isEqualTo(AgentStatus.ERROR);
    }

    // -------------------------------------------------------------------------
    // Absent rows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an unregistered agent is UNKNOWN and heartbeating it creates nothing")
    void unregisteredAgentStaysAbsent() {
        // Given no such agent / When beaten from either node
        nodeA.presence().heartbeat("never-registered").join();
        nodeB.presence().heartbeat("never-registered").join();

        // Then no row appeared — a heartbeat refreshes a registration, it does not make one
        assertThat(nodeA.presence().getStatus("never-registered").join())
                .isEqualTo(AgentStatus.UNKNOWN);
        assertThat(nodeA.discovery().findById("never-registered").join()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/" + DB;
    }

    private static void register(JdbcAgentDirectory node, String agentId) {
        register(node, agentId, AgentStatus.RUNNING);
    }

    private static void register(JdbcAgentDirectory node, String agentId, AgentStatus status) {
        node.registry().register(AgentDescriptor.builder(agentId)
                .agentName(agentId)
                .agentType("PresenceTestAgent")
                .status(status)
                .capabilities(Set.of())
                .metadata(Map.of())
                .endpoint(AgentEndpoint.local("node-" + agentId))
                .build()).join();
    }

    /** Reads {@code last_seen} outside the class under test, so the assertion is independent. */
    private static Instant readLastSeen(String agentId) throws SQLException {
        try (var conn = DriverManager.getConnection(jdbcUrl(), USER, PASSWORD);
             var stmt = conn.prepareStatement(
                     "SELECT last_seen FROM agenor_agents WHERE agent_id = ?")) {
            stmt.setString(1, agentId);
            try (var rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("row for %s", agentId).isTrue();
                return rs.getTimestamp("last_seen").toInstant();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
