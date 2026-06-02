package dev.agenor.adapters.persistence.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentQuery;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.PageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcAgentDirectory} using a real PostgreSQL database
 * managed by Testcontainers.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters-persistence}
 *
 * @since 0.22.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("JdbcAgentDirectory — integration tests (PostgreSQL)")
class JdbcAgentDirectoryIT {

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "jentic_test")
            .withEnv("POSTGRES_USER", "agenor")
            .withEnv("POSTGRES_PASSWORD", "jentic_test")
            .withStartupTimeout(Duration.ofSeconds(60));

    private JdbcAgentDirectory directory;

    @BeforeEach
    void setUp() {
        var url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/jentic_test";
        directory = JdbcAgentDirectory.create(
                JdbcDirectoryConfig.of(url, "agenor", "jentic_test"));
    }

    @AfterEach
    void tearDown() {
        if (directory != null) directory.close();
    }

    @Test
    @DisplayName("register and find by id")
    void registerAndFindById() {
        var descriptor = agentDescriptor("payment-agent", "PaymentAgent",
                AgentStatus.RUNNING, Set.of("payment", "billing"));

        directory.registry().register(descriptor).join();

        var found = directory.discovery().findById("payment-agent").join();
        assertThat(found).isPresent();
        assertThat(found.get().agentId()).isEqualTo("payment-agent");
        assertThat(found.get().status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(found.get().capabilities()).containsExactlyInAnyOrder("payment", "billing");
    }

    @Test
    @DisplayName("upsert on duplicate agent id")
    void upsertOnDuplicate() {
        var first = agentDescriptor("order-agent", "OrderAgent", AgentStatus.RUNNING, Set.of("order"));
        directory.registry().register(first).join();

        var updated = agentDescriptor("order-agent", "OrderAgent", AgentStatus.STOPPED,
                Set.of("order", "fulfillment"));
        directory.registry().register(updated).join();

        var found = directory.discovery().findById("order-agent").join().orElseThrow();
        assertThat(found.status()).isEqualTo(AgentStatus.STOPPED);
        assertThat(found.capabilities()).containsExactlyInAnyOrder("order", "fulfillment");
    }

    @Test
    @DisplayName("unregister removes agent")
    void unregister() {
        directory.registry().register(
                agentDescriptor("temp", "Temp", AgentStatus.RUNNING, Set.of())).join();
        directory.registry().unregister("temp").join();

        assertThat(directory.discovery().findById("temp").join()).isEmpty();
    }

    @Test
    @DisplayName("updateStatus changes agent status")
    void updateStatus() {
        directory.registry().register(
                agentDescriptor("svc", "Svc", AgentStatus.RUNNING, Set.of())).join();
        directory.registry().updateStatus("svc", AgentStatus.STOPPED).join();

        assertThat(directory.discovery().findById("svc").join().get().status())
                .isEqualTo(AgentStatus.STOPPED);
    }

    @Test
    @DisplayName("findByCapability returns matching agents")
    void findByCapability() {
        directory.registry().register(
                agentDescriptor("a1", "T", AgentStatus.RUNNING, Set.of("cap-x", "cap-y"))).join();
        directory.registry().register(
                agentDescriptor("a2", "T", AgentStatus.RUNNING, Set.of("cap-x"))).join();
        directory.registry().register(
                agentDescriptor("a3", "T", AgentStatus.RUNNING, Set.of("cap-z"))).join();

        var result = directory.discovery().findByCapability("cap-x").join();
        assertThat(result).extracting(AgentDescriptor::agentId)
                .containsExactlyInAnyOrder("a1", "a2");
    }

    @Test
    @DisplayName("findAgents with paginated query")
    void findAgentsPaginated() {
        for (int i = 0; i < 5; i++) {
            directory.registry().register(
                    agentDescriptor("worker-" + i, "Worker", AgentStatus.RUNNING, Set.of())).join();
        }

        var page = directory.discovery()
                .findAgents(AgentQuery.byType("Worker"), PageRequest.of(0, 3)).join();

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.content()).hasSize(3);
    }

    @Test
    @DisplayName("resolveEndpoint returns stored endpoint")
    void resolveEndpoint() {
        directory.registry().register(
                agentDescriptor("resolver-test", "T", AgentStatus.RUNNING, Set.of())).join();

        var endpoint = directory.resolver().resolveEndpoint("resolver-test").join();
        assertThat(endpoint).isPresent();
        assertThat(endpoint.get().transportType()).isEqualTo("local");
        assertThat(endpoint.get().nodeId()).isEqualTo("test-node-id");
    }

    @Test
    @DisplayName("resolveEndpoint returns empty for unknown agent")
    void resolveEndpointUnknown() {
        assertThat(directory.resolver().resolveEndpoint("nonexistent").join()).isEmpty();
    }

    @Test
    @DisplayName("factory create runs migrations and returns usable directory")
    void createRunsMigrationsAndIsUsable() {
        var url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/jentic_test";
        try (var dir = JdbcAgentDirectory.create(JdbcDirectoryConfig.of(url, "agenor", "jentic_test"))) {
            var desc = agentDescriptor("smoke-test", "Smoke", AgentStatus.RUNNING, Set.of("smoke"));
            dir.registry().register(desc).join();
            assertThat(dir.discovery().findById("smoke-test").join()).isPresent();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentDescriptor agentDescriptor(String agentId, String agentType,
                                             AgentStatus status, Set<String> capabilities) {
        return AgentDescriptor.builder(agentId)
                .agentName(agentId)
                .agentType(agentType)
                .status(status)
                .capabilities(capabilities)
                .metadata(Map.of())
                .endpoint(AgentEndpoint.local("test-node-id"))
                .build();
    }
}
