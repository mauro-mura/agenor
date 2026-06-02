package dev.agenor.adapters.persistence.directory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.JdbcHelper;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanBuilder;
import dev.agenor.core.telemetry.SpanScope;
import dev.agenor.core.telemetry.SpanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private RecordingTelemetry telemetry;

    @BeforeEach
    void setUp() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:agenor_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(cfg);

        new DirectorySchemaManager(dataSource, "classpath:db/migration/agenor-directory").migrate();

        helper = new JdbcHelper(dataSource);
        telemetry = new RecordingTelemetry();
        registry = new JdbcAgentRegistry(helper, telemetry);
        discovery = new JdbcAgentDiscovery(helper, telemetry);
        resolver = new JdbcAgentResolver(helper, telemetry);
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
                dev.agenor.core.AgentQuery.byStatus(AgentStatus.RUNNING),
                dev.agenor.core.PageRequest.first(10)).join();

        assertThat(result.content()).extracting(AgentDescriptor::agentId).containsExactly("c1");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void findAgents_withRequiredCapabilities() {
        registry.register(descriptor("d1", "T", AgentStatus.RUNNING, Set.of("cap-a", "cap-b"), Map.of())).join();
        registry.register(descriptor("d2", "T", AgentStatus.RUNNING, Set.of("cap-a"), Map.of())).join();

        var result = discovery.findAgents(
                dev.agenor.core.AgentQuery.withCapabilities(Set.of("cap-a", "cap-b")),
                dev.agenor.core.PageRequest.first(10)).join();

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
    // Telemetry — span emission
    // -------------------------------------------------------------------------

    @Test
    void resolveEndpoint_emitsDirectoryResolveSpan_forExistingAgent() {
        registry.register(descriptor("span-agent", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        telemetry.clear();

        resolver.resolveEndpoint("span-agent").join();

        assertThat(telemetry.spans()).hasSize(1);
        var span = telemetry.spans().get(0);
        assertThat(span.name()).isEqualTo("directory.resolve");
        assertThat(span.attributes()).containsEntry("agent.id", "span-agent");
        assertThat(span.attributes()).containsEntry("endpoint.type", "local");
        assertThat(span.status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void resolveEndpoint_emitsDirectoryResolveSpan_withNotFoundType_forMissingAgent() {
        resolver.resolveEndpoint("ghost-agent").join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.resolve")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("endpoint.type", "not-found");
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void register_emitsDirectoryRegisterSpan() {
        registry.register(descriptor("reg-span-agent", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.register")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("agent.id", "reg-span-agent");
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void updateStatus_emitsDirectoryUpdateStatusSpan() {
        registry.register(descriptor("status-agent", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        telemetry.clear();

        registry.updateStatus("status-agent", AgentStatus.STOPPED).join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.update_status")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("agent.id", "status-agent");
        assertThat(spans.get(0).attributes()).containsEntry("agent.status", "STOPPED");
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void findByCapability_emitsDirectoryFindSpan() {
        registry.register(descriptor("cap-agent", "T", AgentStatus.RUNNING, Set.of("cap-x"), Map.of())).join();
        telemetry.clear();

        discovery.findByCapability("cap-x").join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.find")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.type", "by_capability");
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.result_count", 1L);
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void unregister_emitsDirectoryUnregisterSpan() {
        registry.register(descriptor("del-agent", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        telemetry.clear();

        registry.unregister("del-agent").join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.unregister")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("agent.id", "del-agent");
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void findById_emitsDirectoryFindSpan() {
        registry.register(descriptor("fbi-agent", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        telemetry.clear();

        discovery.findById("fbi-agent").join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.find")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.type", "by_id");
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.result_count", 1L);
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void findByType_emitsDirectoryFindSpan() {
        registry.register(descriptor("fbt-agent", "FindByTypeAgent", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        telemetry.clear();

        discovery.findByType("FindByTypeAgent").join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.find")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.type", "by_type");
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.result_count", 1L);
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
    }

    @Test
    void findAgents_emitsDirectoryFindSpan_withQueryType() {
        registry.register(descriptor("paged-agent", "T", AgentStatus.RUNNING, Set.of(), Map.of())).join();
        telemetry.clear();

        discovery.findAgents(
                dev.agenor.core.AgentQuery.byStatus(AgentStatus.RUNNING),
                dev.agenor.core.PageRequest.first(10)).join();

        var spans = telemetry.spans().stream()
                .filter(s -> s.name().equals("directory.find")).toList();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.type", "query");
        assertThat(spans.get(0).attributes()).containsEntry("directory.find.result_count", 1L);
        assertThat(spans.get(0).status()).isEqualTo(SpanStatus.OK);
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

    // -------------------------------------------------------------------------
    // Test telemetry — captures span name, attributes, and final status
    // -------------------------------------------------------------------------

    static final class RecordingTelemetry implements AgenorTelemetry {

        record CapturedSpan(String name, Map<String, Object> attributes, SpanStatus status) {}

        private final List<CapturedSpan> spans = new ArrayList<>();

        void clear() { spans.clear(); }

        List<CapturedSpan> spans() { return List.copyOf(spans); }

        @Override
        public SpanBuilder spanBuilder(String operationName) {
            var attrs = new HashMap<String, Object>();
            return new SpanBuilder() {
                @Override
                public SpanBuilder setAttribute(String key, String value) { attrs.put(key, value); return this; }
                @Override
                public SpanBuilder setAttribute(String key, long value) { attrs.put(key, value); return this; }
                @Override
                public SpanBuilder setAttribute(String key, boolean value) { attrs.put(key, value); return this; }
                @Override
                public Span startSpan() {
                    var finalStatus = new SpanStatus[]{SpanStatus.UNSET};
                    return new Span() {
                        @Override
                        public Span setAttribute(String key, String value) { attrs.put(key, value); return this; }
                        @Override
                        public Span setAttribute(String key, long value) { attrs.put(key, value); return this; }
                        @Override
                        public Span setAttribute(String key, boolean value) { attrs.put(key, value); return this; }
                        @Override
                        public Span setAttribute(String key, double value) { attrs.put(key, value); return this; }
                        @Override
                        public Span recordException(Throwable t) { return this; }
                        @Override
                        public Span setStatus(SpanStatus s) { finalStatus[0] = s; return this; }
                        @Override
                        public SpanScope makeCurrent() { return () -> {}; }
                        @Override
                        public void end() { spans.add(new CapturedSpan(operationName, Map.copyOf(attrs), finalStatus[0])); }
                    };
                }
            };
        }
    }
}
