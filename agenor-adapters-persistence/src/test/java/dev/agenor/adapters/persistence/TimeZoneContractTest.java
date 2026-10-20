package dev.agenor.adapters.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.directory.DirectorySchemaManager;
import dev.agenor.adapters.persistence.directory.JdbcAgentDiscovery;
import dev.agenor.adapters.persistence.directory.JdbcAgentPresence;
import dev.agenor.adapters.persistence.directory.JdbcAgentRegistry;
import dev.agenor.adapters.persistence.hitl.HitlSchemaManager;
import dev.agenor.adapters.persistence.hitl.JdbcApprovalGate;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.telemetry.AgenorTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Two nodes in different time zones sharing one database must read the same instant back.
 *
 * <p>Agenor's timestamp columns are {@code TIMESTAMP} without a zone, so what reaches the
 * database is a wall-clock time. Up to 0.37.0 each node converted with its JVM's default zone,
 * which made that wall-clock time a different instant to each: a node in UTC and a node in
 * Tokyo disagreed about {@code last_seen} and {@code expires_at} by nine hours.
 *
 * <p>A node here is a connection pool opened while the JVM default zone is that node's, and
 * every call it makes runs under that zone too: a driver may fix a session's zone when it
 * connects (MySQL) or convert at each call (PostgreSQL), and both have to be what a separate
 * JVM would see. The default zone is global, so it is restored after every call.
 *
 * <p>H2 cannot take part: it reads the default zone once per JVM and keeps it, so a test in
 * one JVM passes there whatever the code does.
 */
public abstract class TimeZoneContractTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

    /** @return the JDBC URL of an empty database for this test; called once per test */
    protected abstract String freshDatabaseUrl();

    protected abstract String user();

    protected abstract String password();

    private final List<HikariDataSource> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(HikariDataSource::close);
        pools.clear();
    }

    @Test
    void anAgentRegisteredInUtcIsFreshToANodeInTokyo() {
        var url = freshDatabaseUrl();
        var utc = node(UTC, url);
        var tokyo = node(TOKYO, url);

        utc.run(() -> new JdbcAgentRegistry(utc.helper(), AgenorTelemetry.noop())
                .register(AgentDescriptor.builder("worker")
                        .agentName("worker").agentType("Worker").status(AgentStatus.RUNNING)
                        .build()).join());

        var presence = new JdbcAgentPresence(tokyo.helper(), AgenorTelemetry.noop(), Duration.ofSeconds(60));
        var discovery = new JdbcAgentDiscovery(tokyo.helper(), AgenorTelemetry.noop());
        assertThat(tokyo.run(() -> presence.getStatus("worker").join()))
                .isEqualTo(AgentStatus.RUNNING);
        assertThat(tokyo.run(() -> discovery.findById("worker").join().orElseThrow().lastSeen()))
                .isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
    }

    @Test
    void anApprovalRequestedInUtcKeepsItsDeadlineOnANodeInTokyo() {
        var url = freshDatabaseUrl();
        var utc = node(UTC, url);
        var tokyo = node(TOKYO, url);
        var request = ApprovalRequest.of("agent-1", "send", "payload", Duration.ofHours(1));

        try (var requester = utc.run(() -> new JdbcApprovalGate(utc.dataSource(), url));
             var reader = tokyo.run(() -> new JdbcApprovalGate(tokyo.dataSource(), url))) {
            utc.run(() -> requester.requestApproval(request));

            // Nine hours ahead, a request with an hour left used to be past its deadline
            var pending = tokyo.run(reader::getPendingRequests);

            assertThat(pending).singleElement().satisfies(r ->
                    assertThat(r.expiresAt()).isCloseTo(request.expiresAt(), within(Duration.ofSeconds(1))));
        }
    }

    private Node node(TimeZone zone, String url) {
        var node = new Node(zone, null);
        var dataSource = node.run(() -> {
            var cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(user());
            cfg.setPassword(password());
            cfg.setMaximumPoolSize(2);
            var ds = new HikariDataSource(cfg);
            new DirectorySchemaManager(ds, "classpath:db/agenor/directory").migrate();
            new HitlSchemaManager(ds, "classpath:db/agenor/hitl").migrate();
            return ds;
        });
        pools.add(dataSource);
        return new Node(zone, dataSource);
    }

    private record Node(TimeZone zone, HikariDataSource dataSource) {

        JdbcHelper helper() {
            return new JdbcHelper(dataSource);
        }

        <T> T run(Supplier<T> work) {
            var before = TimeZone.getDefault();
            TimeZone.setDefault(zone);
            try {
                return work.get();
            } finally {
                TimeZone.setDefault(before);
            }
        }
    }
}
