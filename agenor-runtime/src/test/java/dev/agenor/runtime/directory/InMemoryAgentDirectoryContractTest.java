package dev.agenor.runtime.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentDirectory;
import dev.agenor.core.directory.AgentDiscoveryContractTests;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.core.directory.AgentPresenceContractTests;
import dev.agenor.core.directory.AgentRegistryContractTests;
import dev.agenor.core.directory.AgentResolverContractTests;
import org.junit.jupiter.api.DisplayName;

/**
 * Proves that {@link InMemoryAgentDirectory} satisfies all four capability contracts.
 *
 * <p>The test cases live in the contract interfaces, which ship from {@code agenor-core}'s
 * test-jar; this class only provides the subject. Future adapters (Redis, JDBC) follow the
 * same pattern: create a concrete test class, implement the relevant contract interfaces, and
 * supply the backend under test.
 *
 * @since 0.20.0
 */
@DisplayName("InMemoryAgentDirectory — capability contracts")
class InMemoryAgentDirectoryContractTest implements
        AgentRegistryContractTests,
        AgentResolverContractTests,
        AgentDiscoveryContractTests,
        AgentPresenceContractTests {

    @Override
    public AgentDirectory createSubject() {
        return new InMemoryAgentDirectory("contract-node");
    }

    /**
     * The in-memory directory is its own presence backend, so the fixture seeds and observes
     * one and the same instance.
     */
    @Override
    public Fixture createPresenceFixture() {
        var directory = new InMemoryAgentDirectory("contract-node");
        return new Fixture() {

            @Override
            public AgentPresence presence() {
                return directory;
            }

            @Override
            public void register(String agentId, AgentStatus status) {
                directory.register(AgentDescriptor.builder(agentId)
                        .agentName(agentId)
                        .status(status)
                        .build()).join();
            }

            @Override
            public void updateStatus(String agentId, AgentStatus status) {
                directory.updateStatus(agentId, status).join();
            }
        };
    }
}
