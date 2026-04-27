package dev.jentic.runtime.directory;

import dev.jentic.core.directory.AgentDirectory;
import org.junit.jupiter.api.DisplayName;

/**
 * Proves that {@link InMemoryAgentDirectory} satisfies all four capability contracts.
 *
 * <p>The test cases live in the contract interfaces; this class only provides the subject.
 * Future adapters (Redis, JDBC) follow the same pattern: create a concrete test class,
 * implement the relevant contract interfaces, and supply the backend under test via
 * {@code createSubject()}.
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
}
