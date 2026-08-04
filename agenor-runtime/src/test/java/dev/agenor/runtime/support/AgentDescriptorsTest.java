package dev.agenor.runtime.support;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.AgentDirectory;
import dev.agenor.core.BehaviorScheduler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.discovery.AgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the {@code AgentDescriptor}-building logic extracted from
 * {@code AgentFactory.createDescriptor(...)} into this shared helper (ADR-027, task
 * 1.5) — the extraction must not change what descriptor is produced for a given
 * agent, since {@code AgentFactory} now delegates here and this logic also runs
 * directly from {@code AgenorRuntime.registerAgent()} without going through
 * {@code AgentFactory} at all.
 */
@DisplayName("AgentDescriptors")
class AgentDescriptorsTest {

    @Mock
    private MessageDispatcher messageDispatcher;
    @Mock
    private AgentDirectory agentDirectory;
    @Mock
    private BehaviorScheduler behaviorScheduler;
    @Mock
    private MemoryStore memoryStore;

    private AgentFactory agentFactory;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        agentFactory = new AgentFactory(messageDispatcher, agentDirectory, behaviorScheduler, memoryStore);
    }

    @Test
    @DisplayName("builds full metadata for an annotated agent")
    void create_annotatedAgent_buildsFullDescriptor() {
        SampleAnnotatedAgent agent = new SampleAnnotatedAgent();

        AgentDescriptor descriptor = AgentDescriptors.create(SampleAnnotatedAgent.class, agent);

        assertThat(descriptor.agentId()).isEqualTo("sample-agent");
        assertThat(descriptor.agentName()).isEqualTo("Sample Agent");
        assertThat(descriptor.agentType()).isEqualTo("sample-type");
        assertThat(descriptor.capabilities()).containsExactlyInAnyOrder("cap-a", "cap-b");
        assertThat(descriptor.status()).isEqualTo(AgentStatus.STOPPED);
        assertThat(descriptor.metadata())
                .containsEntry("class", SampleAnnotatedAgent.class.getName())
                .containsEntry("autoStart", "true");
    }

    @Test
    @DisplayName("builds a minimal descriptor for a non-annotated agent")
    void create_nonAnnotatedAgent_buildsMinimalDescriptor() {
        SampleUnannotatedAgent agent = new SampleUnannotatedAgent();

        AgentDescriptor descriptor = AgentDescriptors.create(SampleUnannotatedAgent.class, agent);

        assertThat(descriptor.agentId()).isEqualTo("unannotated-agent");
        assertThat(descriptor.agentName()).isEqualTo("Unannotated Agent");
        assertThat(descriptor.agentType()).isEqualTo("SampleUnannotatedAgent");
        assertThat(descriptor.status()).isEqualTo(AgentStatus.STOPPED);
    }

    @Test
    @DisplayName("AgentFactory.createDescriptor() delegates here and produces an identical descriptor")
    void agentFactoryCreateDescriptor_matchesDirectCall() {
        SampleAnnotatedAgent agent = new SampleAnnotatedAgent();

        AgentDescriptor viaHelper = AgentDescriptors.create(SampleAnnotatedAgent.class, agent);
        AgentDescriptor viaFactory = agentFactory.createDescriptor(SampleAnnotatedAgent.class, agent);

        // registeredAt/lastSeen are independently stamped with Instant.now() by each call
        // (AgentDescriptor defaults them when unset), so they are excluded here to avoid
        // flakiness across the two clock reads.
        assertThat(viaFactory)
                .usingRecursiveComparison()
                .ignoringFields("registeredAt", "lastSeen")
                .isEqualTo(viaHelper);
    }

    @Agent(value = "sample-agent", type = "sample-type", capabilities = {"cap-a", "cap-b"}, autoStart = true)
    static class SampleAnnotatedAgent extends BaseAgent {
        SampleAnnotatedAgent() {
            super("sample-agent", "Sample Agent");
        }
    }

    static class SampleUnannotatedAgent extends BaseAgent {
        SampleUnannotatedAgent() {
            super("unannotated-agent", "Unannotated Agent");
        }
    }
}
