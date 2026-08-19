package dev.agenor.adapters.persistence.directory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.directory.AgentPresenceContractTests;
import dev.agenor.core.telemetry.AgenorTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link JdbcAgentPresence} against an in-process H2 database.
 *
 * <p>Also runs {@link AgentPresenceContractTests}, so this backend is held to the same rules
 * as the in-memory one from a single source (ADR-028 D-6).
 *
 * <p>Staleness is driven by writing {@code last_seen} directly rather than by waiting or by
 * injecting a clock: {@link JdbcAgentRegistry#register} always stamps {@code last_seen} with
 * the current time and ignores the descriptor's value, so backdating is the only way to place
 * a row on either side of the window deterministically.
 */
@DisplayName("JdbcAgentPresence — H2")
class JdbcAgentPresenceTest implements AgentPresenceContractTests {

    private static final String BACKDATE =
            "UPDATE agenor_agents SET last_seen = ? WHERE agent_id = ?";

    private HikariDataSource dataSource;
    private JdbcHelper helper;
    private JdbcAgentRegistry registry;
    private JdbcAgentDiscovery discovery;
    private JdbcAgentPresence presence;

    @BeforeEach
    void setUp() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:agenor_presence_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(cfg);

        new DirectorySchemaManager(dataSource, "classpath:db/migration/agenor-directory").migrate();

        helper = new JdbcHelper(dataSource);
        registry = new JdbcAgentRegistry(helper, AgenorTelemetry.noop());
        discovery = new JdbcAgentDiscovery(helper, AgenorTelemetry.noop());
        presence = new JdbcAgentPresence(helper, AgenorTelemetry.noop(), Duration.ofSeconds(60));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    // -------------------------------------------------------------------------
    // Contract suite
    // -------------------------------------------------------------------------

    @Override
    public Fixture createPresenceFixture() {
        return new Fixture() {

            @Override
            public AgentPresence presence() {
                return presence;
            }

            @Override
            public void register(String agentId, AgentStatus status) {
                JdbcAgentPresenceTest.this.register(agentId, status);
            }

            @Override
            public void updateStatus(String agentId, AgentStatus status) {
                registry.updateStatus(agentId, status).join();
            }
        };
    }

    // -------------------------------------------------------------------------
    // Staleness — the part of the contract only this backend can demonstrate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an agent seen inside the window reports its stored status")
    void freshAgentReportsStoredStatus() {
        // Given an agent last seen 30 seconds ago, inside the 60-second window
        register("fresh", AgentStatus.RUNNING);
        backdateLastSeen("fresh", Duration.ofSeconds(30));

        // When its status is read / Then the stored value comes back
        assertThat(presence.getStatus("fresh").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("an agent unseen past the window reports UNKNOWN")
    void staleAgentReportsUnknown() {
        // Given an agent last seen 90 seconds ago, outside the 60-second window
        register("stale", AgentStatus.RUNNING);
        backdateLastSeen("stale", Duration.ofSeconds(90));

        // When its status is read / Then the stored RUNNING is not trusted
        assertThat(presence.getStatus("stale").join()).isEqualTo(AgentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("a heartbeat brings a stale agent back")
    void heartbeatRevivesStaleAgent() {
        // Given an agent that has gone stale
        register("revived", AgentStatus.RUNNING);
        backdateLastSeen("revived", Duration.ofSeconds(90));
        assertThat(presence.getStatus("revived").join()).isEqualTo(AgentStatus.UNKNOWN);

        // When it heartbeats
        presence.heartbeat("revived").join();

        // Then it is visible again, with the status it always had — this is the assertion
        // the shared contract suite cannot make, because an unbounded-window backend has no
        // way to show that a heartbeat did anything at all
        assertThat(presence.getStatus("revived").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("a heartbeat does not change the status it revives")
    void heartbeatDoesNotPromoteStatus() {
        // Given an agent stuck in STARTING and long unseen
        register("stuck", AgentStatus.STARTING);
        backdateLastSeen("stuck", Duration.ofSeconds(90));

        // When it heartbeats
        presence.heartbeat("stuck").join();

        // Then it is still STARTING (ADR-028 D-1)
        assertThat(presence.getStatus("stuck").join()).isEqualTo(AgentStatus.STARTING);
    }

    @Test
    @DisplayName("an unbounded window never expires a status")
    void unboundedWindowNeverExpires() {
        // Given a presence view that does not expire, and an agent unseen for ten years
        var unbounded = new JdbcAgentPresence(helper, AgenorTelemetry.noop(),
                JdbcAgentPresence.UNBOUNDED_STALENESS_WINDOW);
        register("ancient", AgentStatus.RUNNING);
        backdateLastSeen("ancient", Duration.ofDays(3650));

        // When its status is read / Then the stored value still comes back
        assertThat(unbounded.getStatus("ancient").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("a lastSeen in the future is not stale")
    void futureLastSeenIsNotStale() {
        // Given a row written by a node whose clock runs ahead
        register("skewed", AgentStatus.RUNNING);
        backdateLastSeen("skewed", Duration.ofSeconds(-30));

        // When its status is read / Then negative elapsed time reads as fresh, not stale
        assertThat(presence.getStatus("skewed").join()).isEqualTo(AgentStatus.RUNNING);
    }

    // -------------------------------------------------------------------------
    // Unregistered agents
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getStatus returns UNKNOWN for an agent that was never registered")
    void unregisteredAgentIsUnknown() {
        assertThat(presence.getStatus("nobody").join()).isEqualTo(AgentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("a heartbeat for an unregistered agent updates nothing and creates nothing")
    void heartbeatForUnregisteredAgentIsIgnored() {
        // Given no such agent / When heartbeated / Then no row appears
        presence.heartbeat("ghost").join();

        assertThat(presence.getStatus("ghost").join()).isEqualTo(AgentStatus.UNKNOWN);
        assertThat(discovery.findById("ghost").join()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the default window is 90 seconds")
    void defaultWindowIsNinetySeconds() {
        assertThat(new JdbcAgentPresence(helper).stalenessWindow())
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("a null or negative staleness window is rejected")
    void invalidWindowIsRejected() {
        assertThatNullPointerException().isThrownBy(
                () -> new JdbcAgentPresence(helper, AgenorTelemetry.noop(), null));

        assertThatIllegalArgumentException().isThrownBy(
                () -> new JdbcAgentPresence(helper, AgenorTelemetry.noop(),
                        Duration.ofSeconds(-1)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void register(String agentId, AgentStatus status) {
        registry.register(AgentDescriptor.builder(agentId)
                .agentName("Agent " + agentId)
                .agentType("TestAgent")
                .status(status)
                .build()).join();
    }

    /**
     * Moves an agent's {@code last_seen} back by {@code age}. A negative age moves it into
     * the future, which is how clock skew between nodes presents itself.
     */
    private void backdateLastSeen(String agentId, Duration age) {
        helper.mutate(conn -> {
            try {
                helper.update(conn, BACKDATE,
                        List.of(Timestamp.from(Instant.now().minus(age)), agentId));
            } catch (SQLException e) {
                throw new IllegalStateException("failed to backdate " + agentId, e);
            }
        }).join();
    }
}
