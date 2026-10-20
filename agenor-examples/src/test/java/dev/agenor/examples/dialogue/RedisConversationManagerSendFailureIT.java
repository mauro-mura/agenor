package dev.agenor.examples.dialogue;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.dialogue.ConversationManagerSendFailureContractTests;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.runtime.dialogue.DefaultConversationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Holds {@link DefaultConversationManager} over {@code RedisMessageDispatcher} to
 * {@link ConversationManagerSendFailureContractTests}, the suite
 * {@code InMemoryConversationManagerSendFailureTest} runs in memory.
 *
 * <p>Two nodes, so the responder is reached through a Redis stream and not the dispatcher's
 * local fast path. The unreachable agent is the endpoint a runtime without a Redis dispatcher
 * advertises, {@code nodeId=""} and transport {@code local}: the case two applications hit on
 * 0.37.0, and the one agenor-jobs re-ran against the snapshot to find this defect.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("DefaultConversationManager over RedisMessageDispatcher — send failures (Valkey)")
class RedisConversationManagerSendFailureIT implements ConversationManagerSendFailureContractTests {

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    /** Stands in for the directory both nodes share. */
    private final Map<String, AgentEndpoint> endpoints = new ConcurrentHashMap<>();

    private RedisMessagingFactory initiatorNode;
    private RedisMessagingFactory responderNode;
    private DefaultConversationManager initiator;

    @BeforeEach
    void setUp() {
        var uri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        // A prefix per test, so streams an earlier test left cannot deliver into this one
        var prefix = "send-failure-" + System.nanoTime();
        initiatorNode = RedisMessagingFactory.builder()
                .uri(uri).nodeId("node-1").consumerGroupPrefix(prefix).build();
        responderNode = RedisMessagingFactory.builder()
                .uri(uri).nodeId("node-2").consumerGroupPrefix(prefix).build();

        AgentResolver resolver = agentId ->
                CompletableFuture.completedFuture(Optional.ofNullable(endpoints.get(agentId)));
        var initiatorDispatcher = initiatorNode.messageDispatcher(() -> resolver);
        var responderDispatcher = responderNode.messageDispatcher(() -> resolver);

        endpoints.put("initiator", initiatorDispatcher.localEndpoint().orElseThrow());
        endpoints.put("responder", responderDispatcher.localEndpoint().orElseThrow());
        endpoints.put("unreachable", new AgentEndpoint("", "local", Map.of()));

        initiator = new DefaultConversationManager("initiator", initiatorDispatcher);
        var responder = new DefaultConversationManager("responder", responderDispatcher);
        initiatorDispatcher.subscribeRecipient("initiator",
                ConversationManagerSendFailureContractTests.initiatorInbox(initiator));
        responderDispatcher.subscribeRecipient("responder",
                ConversationManagerSendFailureContractTests.responderInbox(responder));
    }

    @AfterEach
    void tearDown() {
        if (initiatorNode != null) initiatorNode.close();
        if (responderNode != null) responderNode.close();
    }

    @Override
    public ConversationManager initiator() {
        return initiator;
    }

    @Override
    public String initiatorId() {
        return "initiator";
    }

    @Override
    public String responder() {
        return "responder";
    }

    @Override
    public String missingAgent() {
        return "ghost";
    }

    @Override
    public String unreachableAgent() {
        return "unreachable";
    }
}
