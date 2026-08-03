package dev.agenor.runtime;

import java.util.concurrent.atomic.AtomicInteger;

import dev.agenor.core.Agent;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.RegistrationContext;

/**
 * Test-only {@link AgentRegistrationExtension}, registered via
 * {@code src/test/resources/META-INF/services/dev.agenor.core.spi.AgentRegistrationExtension}
 * alongside the real extensions shipped in {@code src/main/resources}. Used by
 * {@link AgentRegistrationExtensionSpiTest} to verify that {@code AgenorRuntime}
 * discovers and invokes every registered extension, not just the built-in ones.
 *
 * <p>Public no-arg constructor is required by {@link java.util.ServiceLoader}.
 */
public final class CountingRegistrationExtension implements AgentRegistrationExtension {

    public static final AtomicInteger INVOCATION_COUNT = new AtomicInteger();

    public CountingRegistrationExtension() {
    }

    @Override
    public void onAgentRegistered(Agent agent, RegistrationContext context) {
        INVOCATION_COUNT.incrementAndGet();
    }
}
