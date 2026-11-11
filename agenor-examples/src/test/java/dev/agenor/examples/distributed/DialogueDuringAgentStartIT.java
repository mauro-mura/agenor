package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.Message;
import dev.agenor.core.TransportEndpoint;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * A dialogue message waiting on a node's stream reaches the agent's {@code @DialogueHandler}
 * when the agent starts.
 *
 * <p>The window this probes is inside {@code BaseAgent.start()}: the agent subscribes for direct
 * messages first, and its {@code DialogueCapability} attaches to the mailbox only from a start
 * hook, after {@code onStart()}. A message the mailbox routes in between finds no dialogue
 * consumer, goes to the push consumer, ends in {@code onDirectMessage} — which by default logs
 * at TRACE — and its completed future acknowledges it on the stream.
 *
 * <p>On Redis the window is reachable: an entry written while the node was down is delivered as
 * soon as the node's consumer loop starts, which is during that window. The {@code onStart()}
 * here takes a second, standing in for one that opens a pool or reads a table; with an
 * instantaneous one the outcome is a race, not a guarantee. The in-memory dispatcher has no
 * such window: a message for an agent that has not subscribed is dead-lettered at send.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("A dialogue message waiting when the agent starts — integration test (Valkey)")
class DialogueDuringAgentStartIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String PREFIX = "agenor-start-it";
    private static final String RECEIVER_NODE = "start-node-2";
    private static final String RECEIVER_ID = "slow-starter";
    private static final long PENDING_MS = 1_000;

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory senderFactory;
    private RedisMessagingFactory receiverFactory;
    private AgenorRuntime receiver;

    @AfterEach
    void tearDown() {
        if (receiver != null && receiver.isRunning()) receiver.stop().join();
        if (receiverFactory != null) receiverFactory.close();
        if (senderFactory != null) senderFactory.close();
    }

    @Test
    @DisplayName("a REQUEST written while the node was down reaches the dialogue handler, "
            + "not onDirectMessage")
    void aDialogueMessageWaitingOnTheStreamReachesTheDialogueHandler() throws Exception {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);

        // The receiving node is down: nothing reads its stream. Another node writes a REQUEST to
        // it, as a peer would to an agent it resolved before the node went away.
        senderFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("start-node-1").consumerGroupPrefix(PREFIX).build();
        var request = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("peer")
                .receiverId(RECEIVER_ID)
                .performative(Performative.REQUEST)
                .content("work that arrived while the node was down")
                .build()
                .toMessage();
        senderFactory.messageTransport()
                .send(new TransportEndpoint("redis", RECEIVER_NODE, Map.of()), request).join();

        // The node comes back.
        receiverFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId(RECEIVER_NODE).consumerGroupPrefix(PREFIX)
                .readBlockTimeoutMs(500).pendingEntriesTimeoutMs(PENDING_MS).build();
        var directory = new InMemoryAgentDirectory();
        receiver = AgenorRuntime.builder()
                .messageDispatcher(receiverFactory.messageDispatcher(() -> directory))
                .agentRegistry(directory).agentDiscovery(directory).agentResolver(directory)
                .build();
        var agent = new SlowStarter();
        receiver.registerAgent(agent);
        receiver.start().join();

        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)
                && agent.dialogue.isEmpty() && agent.direct.isEmpty()) {
            Thread.sleep(100);
        }
        // Past a few reclaim intervals: an entry still pending would be redelivered by now, so
        // whatever has not reached the dialogue handler is not coming.
        Thread.sleep(PENDING_MS * 3);

        assertSoftly(softly -> {
            softly.assertThat(agent.dialogue).as("REQUESTs reaching the @DialogueHandler")
                    .containsExactly(request.id());
            softly.assertThat(agent.direct)
                    .as("dialogue messages routed to onDirectMessage, and acknowledged unhandled")
                    .isEmpty();
        });
    }

    /** An agent whose {@code onStart()} takes a second, as one doing real setup does. */
    static class SlowStarter extends BaseAgent {

        final DialogueCapability dialogueCapability = new DialogueCapability(this);
        final List<String> dialogue = new CopyOnWriteArrayList<>();
        final List<String> direct = new CopyOnWriteArrayList<>();

        SlowStarter() {
            super(RECEIVER_ID, "Slow Starter");
        }

        @Override
        protected void onStart() {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage message) {
            dialogue.add(message.id());
        }

        @Override
        protected void onDirectMessage(Message message) {
            direct.add(message.id());
        }
    }
}
