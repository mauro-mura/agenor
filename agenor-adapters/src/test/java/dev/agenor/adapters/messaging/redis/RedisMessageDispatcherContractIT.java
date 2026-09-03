package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.deadletter.DeadLetterQueue;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.MessageDispatcherContractTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proves that {@link RedisMessageDispatcher} satisfies {@link MessageDispatcherContractTests} —
 * the same suite {@code InMemoryMessageDispatcher} is held to, from one source.
 *
 * <p>This is an integration test rather than a unit test on purpose. The contract is about
 * delivery, and every existing {@code RedisMessageDispatcher} unit test replaces
 * {@link RedisMessageTransport} with a mock: those prove the dispatcher delegates, never that a
 * message arrives. Until this test, nothing asserted that anything at all reached the other end
 * of the Redis transport through the dispatcher.
 *
 * <p>What it does not cover, and should not be assumed to: the suite subscribes one handler per
 * agent, so it would not have caught the defect fixed in {@code 81dad0a}, where a second
 * {@code subscribeRecipient} overwrote the first. That needs two subscribers, and
 * {@code RedisMessageDispatcherTest.SubscribeRecipient} has it.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters}
 *
 * @since 0.26.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("RedisMessageDispatcher — contract tests (Valkey)")
class RedisMessageDispatcherContractIT implements MessageDispatcherContractTests {

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    /** Stands in for the directory: every agent registered here lives on this node. */
    private final Map<String, AgentEndpoint> endpoints = new ConcurrentHashMap<>();

    private RedisMessagingFactory factory;

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    @Override
    public MessageDispatcher createDispatcher() {
        // A distinct consumer-group prefix per dispatcher, so streams left by an earlier test
        // cannot deliver into this one. The container is shared by the whole class.
        var uri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        factory = RedisMessagingFactory.builder()
                .uri(uri)
                .consumerGroupPrefix("contract-" + System.nanoTime())
                // The dead-letter cases have to exhaust redelivery before they can assert
                // anything. At the defaults that is three attempts thirty seconds apart; these
                // values change how long the suite waits, not what it proves.
                .maxDeliveryAttempts(1)
                .pendingEntriesTimeoutMs(500)
                .build();
        return factory.messageDispatcher(() -> resolver());
    }

    @Override
    public void registerAgent(String agentId) {
        endpoints.put(agentId, new AgentEndpoint(factory.config().nodeId(), "redis", Map.of()));
    }

    @Override
    public DeadLetterQueue deadLetters() {
        return factory.deadLetterQueue();
    }

    /** Redelivery has to time out before an entry can be dead-lettered at all. */
    @Override
    public Duration deadLetterTimeout() {
        return Duration.ofSeconds(20);
    }

    private AgentResolver resolver() {
        return agentId -> CompletableFuture.completedFuture(
                Optional.ofNullable(endpoints.get(agentId)));
    }
}
