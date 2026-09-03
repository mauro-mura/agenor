package dev.agenor.runtime.messaging;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.deadletter.DeadLetterQueue;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.MessageDispatcherContractTests;
import dev.agenor.runtime.deadletter.InMemoryDeadLetterQueue;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Proves that {@link InMemoryMessageDispatcher} satisfies the {@link MessageDispatcherContractTests}.
 *
 * <p>The suite lives in {@code agenor-core}'s test-jar, so other backends are held to the same
 * rules from one source: {@code RedisMessageDispatcherContractIT} in {@code agenor-adapters} is
 * the other implementor.
 *
 * @since 0.20.0
 */
@DisplayName("InMemoryMessageDispatcher — contract tests")
class InMemoryMessageDispatcherContractTest implements MessageDispatcherContractTests {

    private InMemoryAgentDirectory directory;
    private InMemoryMessageDispatcher dispatcher;
    private InMemoryDeadLetterQueue deadLetters;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory("contract-node");
        dispatcher = new InMemoryMessageDispatcher(directory);
        deadLetters = new InMemoryDeadLetterQueue();
        dispatcher.setDeadLetterQueue(deadLetters);
    }

    @Override
    public MessageDispatcher createDispatcher() {
        return dispatcher;
    }

    @Override
    public void registerAgent(String agentId) {
        directory.register(AgentDescriptor.builder(agentId).agentName(agentId).build()).join();
    }

    @Override
    public DeadLetterQueue deadLetters() {
        return deadLetters;
    }
}
