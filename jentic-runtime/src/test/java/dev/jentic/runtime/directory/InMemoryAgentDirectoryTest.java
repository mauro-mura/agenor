package dev.jentic.runtime.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentDescriptor.AgentDescriptorBuilder;
import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.AgentQuery;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Page;
import dev.jentic.core.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryAgentDirectory}.
 */
class InMemoryAgentDirectoryTest {

    private static final String NODE_ID = "node-test";

    private InMemoryAgentDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory(NODE_ID);
    }

    // -------------------------------------------------------------------------
    // AgentRegistry
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AgentRegistry — register / unregister / updateStatus")
    class AgentRegistryTests {

        @Test
        @DisplayName("register stores descriptor; findById returns it")
        void registerAndFind() {
            var d = builder("agent-1").build();
            directory.register(d).join();

            Optional<AgentDescriptor> found = directory.findById("agent-1").join();
            assertThat(found).isPresent();
            assertThat(found.get().agentId()).isEqualTo("agent-1");
        }

        @Test
        @DisplayName("register without endpoint assigns local endpoint automatically")
        void registerAssignsLocalEndpoint() {
            var d = builder("agent-2").build();
            directory.register(d).join();

            var found = directory.findById("agent-2").join().orElseThrow();
            assertThat(found.endpoint()).isNotNull();
            assertThat(found.endpoint().transportType()).isEqualTo("local");
            assertThat(found.endpoint().nodeId()).isEqualTo(NODE_ID);
        }

        @Test
        @DisplayName("register with explicit endpoint preserves it")
        void registerPreservesExistingEndpoint() {
            var endpoint = new AgentEndpoint("other-node", "grpc", java.util.Map.of());
            var d = AgentDescriptor.builder("agent-3")
                    .agentName("Agent 3")
                    .endpoint(endpoint)
                    .build();
            directory.register(d).join();

            var found = directory.findById("agent-3").join().orElseThrow();
            assertThat(found.endpoint().transportType()).isEqualTo("grpc");
            assertThat(found.endpoint().nodeId()).isEqualTo("other-node");
        }

        @Test
        @DisplayName("unregister removes descriptor; findById returns empty")
        void unregisterRemovesAgent() {
            directory.register(builder("agent-4").build()).join();
            directory.unregister("agent-4").join();

            assertThat(directory.findById("agent-4").join()).isEmpty();
        }

        @Test
        @DisplayName("unregister of unknown agent is a no-op")
        void unregisterUnknownIsNoOp() {
            directory.unregister("nonexistent").join(); // should not throw
        }

        @Test
        @DisplayName("updateStatus changes status field")
        void updateStatusChangesField() {
            directory.register(builder("agent-5").status(AgentStatus.STARTING).build()).join();
            directory.updateStatus("agent-5", AgentStatus.RUNNING).join();

            var found = directory.findById("agent-5").join().orElseThrow();
            assertThat(found.status()).isEqualTo(AgentStatus.RUNNING);
        }

        @Test
        @DisplayName("register with null descriptor throws NullPointerException")
        void registerNullThrows() {
            assertThatThrownBy(() -> directory.register(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("unregister with null agentId throws NullPointerException")
        void unregisterNullThrows() {
            assertThatThrownBy(() -> directory.unregister(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // AgentResolver
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AgentResolver — resolveEndpoint")
    class AgentResolverTests {

        @Test
        @DisplayName("resolveEndpoint returns local endpoint for registered agent")
        void resolveRegisteredAgent() {
            directory.register(builder("agent-r").build()).join();

            Optional<AgentEndpoint> ep = directory.resolveEndpoint("agent-r").join();
            assertThat(ep).isPresent();
            assertThat(ep.get().transportType()).isEqualTo("local");
        }

        @Test
        @DisplayName("resolveEndpoint returns empty for unknown agent")
        void resolveUnknownReturnsEmpty() {
            Optional<AgentEndpoint> ep = directory.resolveEndpoint("no-such-agent").join();
            assertThat(ep).isEmpty();
        }

        @Test
        @DisplayName("resolveEndpoint with null agentId throws NullPointerException")
        void resolveNullThrows() {
            assertThatThrownBy(() -> directory.resolveEndpoint(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // AgentDiscovery
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AgentDiscovery — findByCapability / findByType / findAgents")
    class AgentDiscoveryTests {

        @BeforeEach
        void populate() {
            directory.register(builder("a1")
                    .agentType("worker")
                    .status(AgentStatus.RUNNING)
                    .capabilities(Set.of("compute", "storage"))
                    .build()).join();
            directory.register(builder("a2")
                    .agentType("coordinator")
                    .status(AgentStatus.RUNNING)
                    .capabilities(Set.of("routing"))
                    .build()).join();
            directory.register(builder("a3")
                    .agentType("worker")
                    .status(AgentStatus.STOPPED)
                    .capabilities(Set.of("compute"))
                    .build()).join();
        }

        @Test
        @DisplayName("findByCapability returns agents with matching capability")
        void findByCapability() {
            List<AgentDescriptor> result = directory.findByCapability("compute").join();
            assertThat(result).extracting(AgentDescriptor::agentId)
                    .containsExactlyInAnyOrder("a1", "a3");
        }

        @Test
        @DisplayName("findByType returns agents with matching type")
        void findByType() {
            List<AgentDescriptor> result = directory.findByType("worker").join();
            assertThat(result).extracting(AgentDescriptor::agentId)
                    .containsExactlyInAnyOrder("a1", "a3");
        }

        @Test
        @DisplayName("findByCapability returns empty list when no match")
        void findByCapabilityNoMatch() {
            assertThat(directory.findByCapability("nonexistent").join()).isEmpty();
        }

        @Test
        @DisplayName("findAgents(AgentQuery.all()) returns all agents")
        void findAgentsAllQuery() {
            Page<AgentDescriptor> page = directory.findAgents(AgentQuery.all(), PageRequest.first(100)).join();
            assertThat(page.totalElements()).isEqualTo(3);
            assertThat(page.content()).hasSize(3);
        }

        @Test
        @DisplayName("findAgents filters by agentType")
        void findAgentsByType() {
            var q = AgentQuery.builder().agentType("coordinator").build();
            Page<AgentDescriptor> page = directory.findAgents(q, PageRequest.first(10)).join();
            assertThat(page.content()).extracting(AgentDescriptor::agentId).containsExactly("a2");
        }

        @Test
        @DisplayName("findAgents filters by status")
        void findAgentsByStatus() {
            var q = AgentQuery.builder().status(AgentStatus.STOPPED).build();
            Page<AgentDescriptor> page = directory.findAgents(q, PageRequest.first(10)).join();
            assertThat(page.content()).extracting(AgentDescriptor::agentId).containsExactly("a3");
        }

        @Test
        @DisplayName("findAgents filters by required capabilities")
        void findAgentsByCapabilities() {
            var q = AgentQuery.builder().requiredCapabilities(Set.of("compute", "storage")).build();
            Page<AgentDescriptor> page = directory.findAgents(q, PageRequest.first(10)).join();
            assertThat(page.content()).extracting(AgentDescriptor::agentId).containsExactly("a1");
        }

        @Test
        @DisplayName("findAgents returns empty page when no match")
        void findAgentsNoMatch() {
            var q = AgentQuery.builder().agentType("nonexistent").build();
            Page<AgentDescriptor> page = directory.findAgents(q, PageRequest.first(10)).join();
            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isZero();
        }

        @Test
        @DisplayName("findAgents respects pagination offset and size")
        void findAgentsPagination() {
            // Add extra agents so we have 5 total workers
            directory.register(builder("a4").agentType("worker").build()).join();
            directory.register(builder("a5").agentType("worker").build()).join();

            var q = AgentQuery.builder().agentType("worker").build();

            Page<AgentDescriptor> page0 = directory.findAgents(q, PageRequest.of(0, 2)).join();
            assertThat(page0.content()).hasSize(2);
            assertThat(page0.totalElements()).isEqualTo(4); // a1, a3, a4, a5

            Page<AgentDescriptor> page1 = directory.findAgents(q, PageRequest.of(1, 2)).join();
            assertThat(page1.content()).hasSize(2);
        }
    }

    // -------------------------------------------------------------------------
    // AgentPresence
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AgentPresence — heartbeat / getStatus")
    class AgentPresenceTests {

        @Test
        @DisplayName("heartbeat updates status to RUNNING")
        void heartbeatSetsRunning() {
            directory.register(builder("agent-h").status(AgentStatus.STARTING).build()).join();
            directory.heartbeat("agent-h").join();

            assertThat(directory.getStatus("agent-h").join()).isEqualTo(AgentStatus.RUNNING);
        }

        @Test
        @DisplayName("getStatus returns UNKNOWN for unregistered agent")
        void getStatusUnknownForMissing() {
            assertThat(directory.getStatus("nobody").join()).isEqualTo(AgentStatus.UNKNOWN);
        }

        @Test
        @DisplayName("getStatus returns current status after updateStatus")
        void getStatusAfterUpdate() {
            directory.register(builder("agent-s").build()).join();
            directory.updateStatus("agent-s", AgentStatus.ERROR).join();

            assertThat(directory.getStatus("agent-s").join()).isEqualTo(AgentStatus.ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // nodeId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("nodeId() returns the value passed in the constructor")
    void nodeIdIsPreserved() {
        assertThat(directory.nodeId()).isEqualTo(NODE_ID);
    }

    @Test
    @DisplayName("Default constructor generates a non-blank nodeId")
    void defaultConstructorGeneratesNodeId() {
        var dir = new InMemoryAgentDirectory();
        assertThat(dir.nodeId()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AgentDescriptorBuilder builder(String id) {
        return AgentDescriptor.builder(id).agentName("Agent " + id);
    }
}
