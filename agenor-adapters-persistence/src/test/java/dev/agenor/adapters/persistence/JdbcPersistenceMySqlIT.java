package dev.agenor.adapters.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.adapters.persistence.hitl.HitlSchemaManager;
import dev.agenor.adapters.persistence.hitl.JdbcApprovalGate;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC directory and HITL queue on MySQL, the way an application reaches them: from a
 * {@code jdbc:mysql:} URL, through the public factory and constructor.
 *
 * <p>Up to 0.37.0 neither could start there: the module shipped Flyway's PostgreSQL support only,
 * and Flyway answered {@code Unsupported Database: MySQL 8.4}. The SQL itself was always portable.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters-persistence}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("JDBC directory and HITL — integration tests (MySQL)")
class JdbcPersistenceMySqlIT {

    @Container
    static GenericContainer<?> mysql = MySqlContainers.mysql();

    private static String url() {
        return MySqlContainers.serverUrl(mysql) + "agenor_test";
    }

    @Test
    @DisplayName("the directory starts from a MySQL URL and serves every capability")
    void directoryOnMySql() {
        try (var directory = JdbcAgentDirectory.create(
                JdbcDirectoryConfig.of(url(), "root", MySqlContainers.ROOT_PASSWORD))) {

            directory.registry().register(agent("worker-1", Set.of("pdf", "ocr"))).join();
            // A second register takes the update path, which depends on recognising MySQL's
            // duplicate-key error (1062) rather than PostgreSQL's SQLState
            directory.registry().register(agent("worker-1", Set.of("pdf", "txt"))).join();

            var found = directory.discovery().findById("worker-1").join();
            assertThat(found).isPresent();
            assertThat(found.get().capabilities()).containsExactlyInAnyOrder("pdf", "txt");
            assertThat(directory.discovery().findByCapability("pdf").join()).hasSize(1);
            assertThat(directory.discovery().findByCapability("ocr").join()).isEmpty();

            directory.registry().updateStatus("worker-1", AgentStatus.ERROR).join();
            assertThat(directory.discovery().findById("worker-1").join())
                    .hasValueSatisfying(d -> assertThat(d.status()).isEqualTo(AgentStatus.ERROR));

            assertThat(directory.resolver().resolveEndpoint("worker-1").join())
                    .hasValueSatisfying(e -> assertThat(e.nodeId()).isEqualTo("node-1"));

            directory.presence().heartbeat("worker-1").join();

            directory.registry().unregister("worker-1").join();
            assertThat(directory.discovery().findById("worker-1").join()).isEmpty();
        }
    }

    @Test
    @DisplayName("the HITL queue migrates on MySQL and carries a decision")
    void hitlOnMySql() throws Exception {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(url());
        cfg.setUsername("root");
        cfg.setPassword(MySqlContainers.ROOT_PASSWORD);
        try (var dataSource = new HikariDataSource(cfg)) {
            new HitlSchemaManager(dataSource, "classpath:db/agenor/hitl").migrate();

            try (var gate = new JdbcApprovalGate(dataSource, url())) {
                gate.recoverExpired();
                var request = ApprovalRequest.of("worker-1", "pay", "€5", Duration.ofMinutes(5));
                var decision = gate.requestApproval(request);
                assertThat(gate.getPendingRequests()).hasSize(1);

                gate.submit(request.requestId(), new ApprovalDecision.Approved());

                assertThat(decision.get(5, TimeUnit.SECONDS)).isInstanceOf(ApprovalDecision.Approved.class);
                assertThat(gate.getPendingRequests()).isEmpty();
            }
        }
    }

    private static AgentDescriptor agent(String agentId, Set<String> capabilities) {
        return AgentDescriptor.builder(agentId)
                .agentName(agentId)
                .agentType("Worker")
                .status(AgentStatus.RUNNING)
                .capabilities(capabilities)
                .metadata(Map.of("k", "v"))
                .endpoint(AgentEndpoint.local("node-1"))
                .build();
    }
}
