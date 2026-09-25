package dev.agenor.autoconfigure.testagents;

import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.agent.BaseAgent;

/**
 * A single {@code @Agent} class in its own package, scanned by
 * {@code AgenorScanningAutoConfigurationTest} and
 * {@code AgenorStarterIntegrationTest} via {@code agenor.agents.base-package} — the
 * documented Quick Start (docs/spring-boot-starter.md). It exists only to prove
 * that classpath discovery actually runs end-to-end through the starter, which no
 * existing test does (the prior assertion only checked that the property value
 * reached {@code AgenorConfiguration}).
 */
@Agent("discoverable-starter-agent")
public class DiscoverableAgent extends BaseAgent {

    public DiscoverableAgent() {
        super("discoverable-starter-agent", "Discoverable Starter Agent");
    }
}
