package dev.agenor.runtime.messaging;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Proves that {@link InMemoryMessageDispatcher} satisfies the {@link MessageDispatcherContractTests}.
 *
 * <p>Future adapters (Redis, …) follow the same pattern: implement the contract interface
 * and wire the backend under test in the factory methods.
 *
 * @since 0.20.0
 */
@DisplayName("InMemoryMessageDispatcher — contract tests")
class InMemoryMessageDispatcherContractTest implements MessageDispatcherContractTests {

    private InMemoryAgentDirectory directory;
    private InMemoryMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory("contract-node");
        dispatcher = new InMemoryMessageDispatcher(directory);
    }

    @Override
    public MessageDispatcher createDispatcher() {
        return dispatcher;
    }

    @Override
    public void registerAgent(String agentId) {
        directory.register(AgentDescriptor.builder(agentId).agentName(agentId).build()).join();
    }
}
