package dev.jentic.adapters.persistence.directory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jentic.adapters.persistence.JdbcHelper;
import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.AgentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JdbcAgentRegistry}, {@link JdbcAgentDiscovery}, and
 * {@link JdbcAgentResolver} using an in-process H2 database.
 */
class JdbcAgentDirectoryTest {

    private HikariDataSource dataSource;
    private JdbcHelper helper;
    private JdbcAgentRegistry registry;
    private JdbcAgentDiscovery discovery;
    private JdbcAgentResolver resolver;

    @BeforeEach
    void setUp() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:jentic_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(cfg);

        new DirectorySchemaManager(dataSource, "classpath:db/migration/jentic-directory").migrate();

        helper = new JdbcHelper(dataSource);
        registry = new JdbcAgentRegistry(helper);
        discovery = new JdbcAgentDiscovery(helper);
        resolver = new JdbcAgentResolver(helper);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    // -------------------------------------------------------------------------
    // register / findById
    // -------------------------------------------------------------------------

    @Test
    void register_insertsNewAgent() {
        var descriptor = descriptor("payment-agent", "PaymentAgent", AgentStatus.RUNNING,
                Set.of("payment", "billing"), Map.of("region", "eu"));

        registry.register(descriptor).join();

        var found = discovery.findById("payment-agent").join();
        assertThat(found).isPresent();
        assertThat(found.get().agentId()).isEqualTo("payment-agent");
        assertThat(found.get().agentType()).isEqualTo("PaymentAgent");
        assertThat(found.get().status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(found.get().capabilities()).containsExactlyInAnyOrder("payment", "billing");
        assertThat(found.get().metadata()).containsEntry("region", "eu");
    }

    @Test
    void register_upserts_onDuplicateAgentId() {
        var first = descriptor("order-agent", "OrderAgent", AgentStatus.RUNNING, Set.of("order"), Map.of());
        registry.register(first).join();

        var second = descriptor("order-agent", "OrderAgent", AgentStatus.STOPPED, Set.of("order", "shipping"), Map.of());
        registry.register(second).join();  // must not throw

        var found = discovery.findById("order-agent").join();
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(AgentStatus.STOPPED);
        assertThat(found.get().capabilities()).containsExactlyInAnyOrder("order", "shipping");
    }

    @Test
    void register_preservesRegisteredAt_onUpsert() {
        var first = descriptor("stable-agent", "StableAgent", AgentStatus.RUNNING, Set.of(), Map.of());
        registry.register(first).join();
        var registeredAt = discovery.findById("stable-agent").join().get().registeredAt();

        registry.register(first).join();

        var secondRead = discovery.findById("stable-agent").join().get().registeredAt();
        assertThat(secondRead).isEqualTo(registeredAt);
    }

    // -------------------------------------------------------------------------
    // unregister
    // -------------------------------------------------------------------------

    @Test
    void unregister_removesAgent() {
        registry.register(descriptor("temp-agent", "TempAgent", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        registry.unregister("temp-agent").join();

        assertThat(discovery.findById("temp-agent").join()).isEmpty();
    }

    @Test
    void unregister_isNoOp_whenAgentNotFound() {
        // must not throw
        registry.unregister("nonexistent").join();
    }

    // -------------------------------------------------------------------------
    // updateStatus
    // -------------------------------------------------------------------------

    @Test
    void updateStatus_changesStatus() {
        registry.register(descriptor("svc-agent", "SvcAgent", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        registry.updateStatus("svc-agent", AgentStatus.STOPPED).join();

        assertThat(discovery.findById("svc-agent").join().get().status()).isEqualTo(AgentStatus.STOPPED);
    }

    // -------------------------------------------------------------------------
    // AgentDiscovery
    // -------------------------------------------------------------------------

    @Test
    void findByCapability_returnsMatchingAgents() {
        registry.register(descriptor("a1", "TypeA", AgentStatus.RUNNING, Set.of("cap-x", "cap-y"), Map.of())).join();
        registry.register(descriptor("a2", "TypeA", AgentStatus.RUNNING, Set.of("cap-x"), Map.of())).join();
        registry.register(descriptor("a3", "TypeB", AgentStatus.RUNNING, Set.of("cap-z"), Map.of())).join();

        var result = discovery.findByCapability("cap-x").join();
        assertThat(result).extracting(AgentDescriptor::agentId).containsExactlyInAnyOrder("a1", "a2");
    }

    @Test
    void findByType_returnsMatchingAgents() {
        registry.register(descriptor("b1", "WorkerAgent", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        registry.register(descriptor("b2", "WorkerAgent", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        registry.register(descriptor("b3", "SupervisorAgent", AgentStatus.RUNNING, Set.of(), Map.of())).join();

        var result = discovery.findByType("WorkerAgent").join();
        assertThat(result).extracting(AgentDescriptor::agentId).containsExactlyInAnyOrder("b1", "b2");
    }

    @Test
    void findAgents_withStatusFilter() {
        registry.register(descriptor("c1", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        registry.register(descriptor("c2", "T", AgentStatus.STOPPED, Set.of(), Map.of())).join();

        var result = discovery.findAgents(
                dev.jentic.core.AgentQuery.byStatus(AgentStatus.RUNNING),
                dev.jentic.core.PageRequest.first(10)).join();

        assertThat(result.content()).extracting(AgentDescriptor::agentId).containsExactly("c1");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void findAgents_withRequiredCapabilities() {
        registry.register(descriptor("d1", "T", AgentStatus.RUNNING, Set.of("cap-a", "cap-b"), Map.of())).join();
        registry.register(descriptor("d2", "T", AgentStatus.RUNNING, Set.of("cap-a"), Map.of())).join();

        var result = discovery.findAgents(
                dev.jentic.core.AgentQuery.withCapabilities(Set.of("cap-a", "cap-b")),
                dev.jentic.core.PageRequest.first(10)).join();

        assertThat(result.content()).extracting(AgentDescriptor::agentId).containsExactly("d1");
    }

    // -------------------------------------------------------------------------
    // AgentResolver
    // -------------------------------------------------------------------------

    @Test
    void resolveEndpoint_returnsEndpointForRegisteredAgent() {
        registry.register(descriptor("e1", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();

        var endpoint = resolver.resolveEndpoint("e1").join();
        assertThat(endpoint).isPresent();
        assertThat(endpoint.get().transportType()).isEqualTo("local");
    }

    @Test
    void resolveEndpoint_returnsEmpty_forUnknownAgent() {
        assertThat(resolver.resolveEndpoint("unknown").join()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentDescriptor descriptor(String agentId, String agentType, AgentStatus status,
                                       Set<String> capabilities, Map<String, String> metadata) {
        return AgentDescriptor.builder(agentId)
                .agentName(agentId)
                .agentType(agentType)
                .status(status)
                .capabilities(capabilities)
                .metadata(metadata)
                .endpoint(AgentEndpoint.local("test-node-id"))
                .build();
    }
}
