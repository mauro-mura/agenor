package dev.agenor.runtime.support;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.directory.AgentDirectory;
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
 * Verifies {@code AgentFactory.createDescriptor(...)} delegates to
 * {@link AgentDescriptors#create} and produces an identical descriptor (ADR-027,
 * task 1.5's extraction contract). Split out of {@code AgentDescriptorsTest} in
 * {@code agenor-runtime} (ADR-027, task 4): {@code AgentFactory} moved to
 * {@code agenor-runtime-scanning}, which {@code agenor-runtime} cannot depend on.
 */
@DisplayName("AgentFactory.createDescriptor() / AgentDescriptors parity")
class AgentFactoryDescriptorParityTest {

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
}
