package dev.agenor.runtime.support;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the {@code AgentDescriptor}-building logic extracted from
 * {@code AgentFactory.createDescriptor(...)} into this shared helper (ADR-027, task
 * 1.5) — the extraction must not change what descriptor is produced for a given
 * agent, since {@code AgentFactory} now delegates here and this logic also runs
 * directly from {@code AgenorRuntime.registerAgent()} without going through
 * {@code AgentFactory} at all.
 *
 * <p>The parity check against {@code AgentFactory.createDescriptor()} itself lives in
 * {@code AgentFactoryDescriptorParityTest} in {@code agenor-runtime-scanning} (ADR-027,
 * task 4) — {@code AgentFactory} moved there and {@code agenor-runtime} cannot depend
 * on it.
 */
@DisplayName("AgentDescriptors")
class AgentDescriptorsTest {

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
