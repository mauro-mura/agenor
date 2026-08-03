package dev.agenor.runtime;

import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code AgentRegistrationExtension} SPI wiring in
 * {@code AgenorRuntime.registerAgent()} (ADR-027, task 1.5): every extension
 * discovered via {@link java.util.ServiceLoader} — not just the built-in LLM
 * guardrail/HITL ones — is invoked exactly once per registered agent.
 *
 * @see CountingRegistrationExtension
 */
@DisplayName("AgentRegistrationExtension SPI")
class AgentRegistrationExtensionSpiTest {

    private AgenorRuntime runtime;

    @BeforeEach
    void resetCounter() {
        CountingRegistrationExtension.INVOCATION_COUNT.set(0);
    }

    @AfterEach
    void stopRuntime() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop().join();
        }
    }

    @Test
    @DisplayName("a test-registered extension is discovered and invoked once per registerAgent() call")
    void customExtension_invokedOncePerRegisteredAgent() {
        runtime = AgenorRuntime.builder().build();

        runtime.registerAgent(new SpiTestAgent("agent-1", "Agent One"));
        runtime.registerAgent(new SpiTestAgent("agent-2", "Agent Two"));

        assertThat(CountingRegistrationExtension.INVOCATION_COUNT.get()).isEqualTo(2);
    }

    static class SpiTestAgent extends BaseAgent {
        SpiTestAgent(String id, String name) {
            super(id, name);
        }
    }
}
