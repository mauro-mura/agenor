package dev.agenor.runtime;

import dev.agenor.core.AgenorConfiguration;
import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgenorRuntime} behavior that requires {@code agenor-runtime-scanning} on the
 * classpath: {@code createAgent(Class)} (DI-based construction) and
 * {@code scanPackage(...)}-driven discovery at {@code start()}.
 *
 * <p>Split out of {@code AgenorRuntimeTest} in {@code agenor-runtime} (ADR-027, task 4):
 * that suite covers {@link AgenorRuntime} behavior that works with zero optional
 * modules on the classpath; these tests only pass once {@code agenor-runtime-scanning}
 * (which provides the {@code AgentDiscoveryEngine} SPI implementation) is present —
 * {@code agenor-runtime} cannot depend on it.
 *
 * <p>Note (ADR-027 amendment): {@code @AgenorMessageHandler}/{@code @Behavior}
 * annotation processing is no longer part of this module's concern — it runs
 * unconditionally in {@code agenor-runtime} regardless of whether this module is
 * present. Tests that only exercised annotation processing on manually-registered
 * agents (no real classpath scanning) moved back to {@code AgenorRuntimeTest}.
 */
class AgenorRuntimeScanningIntegrationTest {

    private AgenorRuntime runtimeUnderTest;

    @AfterEach
    void stopRuntime() {
        if (runtimeUnderTest != null && runtimeUnderTest.isRunning()) {
            runtimeUnderTest.stop().join();
        }
    }

    // ========== createAgent ==========

    @Test
    void shouldCreateAgentFromClass() {
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        TestAgent agent = runtime.createAgent(TestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(runtime.getAgents()).contains(agent);
        assertThat(agent.getMessageDispatcher()).isNotNull();
    }

    @Test
    void createAgent_shouldProcessAnnotationsIfRuntimeAlreadyRunning() {
        runtimeUnderTest = AgenorRuntime.builder().build();
        runtimeUnderTest.start().join();

        // Exercises the `if (running)` branch inside createAgent
        TestAgent agent = runtimeUnderTest.createAgent(TestAgent.class);

        assertThat(agent).isNotNull();
        assertThat(runtimeUnderTest.getAgents()).contains(agent);
    }

    // ========== STATS ==========

    @Test
    void shouldGetRuntimeStats() {
        runtimeUnderTest = AgenorRuntime.builder()
            .scanPackage("com.example")
            .build();
        runtimeUnderTest.registerAgent(new TestAgent("agent-1", "Agent 1"));
        runtimeUnderTest.registerAgent(new TestAgent("agent-2", "Agent 2"));
        runtimeUnderTest.start().join();

        AgenorRuntime.RuntimeStats stats = runtimeUnderTest.getStats();

        assertThat(stats.totalAgents()).isEqualTo(2);
        assertThat(stats.runningAgents()).isEqualTo(2);
        assertThat(stats.scannedPackages()).isEqualTo(1);
        assertThat(stats.registeredServices()).isEqualTo(0);
    }

    // ========== BUILDER OPTIONS ==========

    @Test
    void shouldDiscoverAndStartAgentsFromConfigurationScanPackages() {
        AgenorConfiguration config = new AgenorConfiguration(
                new AgenorConfiguration.RuntimeConfig("test-runtime", "development", null),
                new AgenorConfiguration.AgentsConfig(
                        true,
                        null,
                        null,
                        List.of("dev.agenor.runtime"),
                        null
                ),
                null,
                null,
                null
        );

        runtimeUnderTest = AgenorRuntime.builder()
                .withConfiguration(config)
                .build();

        runtimeUnderTest.start().join();

        assertThat(runtimeUnderTest.getAgents()).isNotEmpty();
        assertThat(runtimeUnderTest.getAgents())
                .allSatisfy(agent -> assertThat(agent.isRunning()).isTrue());
    }

    // ========== HELPERS ==========

    static class TestAgent extends BaseAgent {
        TestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }

        public TestAgent() {
            super();
        }
    }

    @Agent("discoverable-test-agent")
    static class DiscoverableTestAgent extends BaseAgent {
        public DiscoverableTestAgent() {
            super("discoverable-test-agent", "Discoverable Test Agent");
        }
    }
}
