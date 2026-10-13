package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport's delivery chain, driven through an agent's mailbox (ADR-033).
 *
 * <p>{@link MailboxCrossTransportIT} proves messages reach both of an agent's inbound lanes.
 * This proves what happens when the handler at the end of one of them <em>fails</em>, which is
 * the case a dispatcher-level contract suite cannot reach: there the test subscribes its own
 * handler and no mailbox sits in the path, so the chain is intact whatever the mailbox does.
 *
 * <p>Since 0.32.0 {@code MessageDispatcherContractTests} covers dead-lettering on both
 * transports, and that is where the cross-transport question now lives. What stays here is the
 * half it still cannot reach: that the chain survives passing <em>through a mailbox</em>, and
 * through an {@code @AgenorMessageHandler} that fails either by throwing or by returning a
 * failed future, or through a {@code @DialogueHandler} that throws.
 *
 * <p>Before ADR-033 this failed. {@code BaseAgent} handed the consumer loop
 * {@code MessageHandler.sync(box::offer)}, which returned as soon as the message was queued,
 * so every message was acknowledged at enqueue and a failing handler ran on a detached thread
 * where its exception was logged and forgotten — no redelivery, no dead-letter, and with the
 * default {@code DROP_OLDEST} policy no trace beyond a WARN.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 *
 * @since 0.27.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Mailbox delivery semantics over the Redis transport — integration tests (Valkey)")
class MailboxDeliverySemanticsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String PREFIX = "agenor-dlq";
    private static final String RECEIVER_NODE = "dlq-node-2";
    private static final int MAX_ATTEMPTS = 2;

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory senderFactory;
    private RedisMessagingFactory receiverFactory;
    private AgenorRuntime sender;
    private AgenorRuntime receiver;
    private FailingAgent agent;
    private AsyncFailingAgent asyncAgent;
    private DialogueFailingAgent dialogueAgent;

    @BeforeEach
    void setUp() {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        var directory = new InMemoryAgentDirectory();

        // Short windows so redelivery and the DLQ hop happen in seconds rather than the
        // 30s/3-attempt defaults. The mechanism under test is the same either way.
        senderFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("dlq-node-1").consumerGroupPrefix(PREFIX)
                .readBlockTimeoutMs(500).pendingEntriesTimeoutMs(1_000)
                .maxDeliveryAttempts(MAX_ATTEMPTS).build();
        receiverFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId(RECEIVER_NODE).consumerGroupPrefix(PREFIX)
                .readBlockTimeoutMs(500).pendingEntriesTimeoutMs(1_000)
                .maxDeliveryAttempts(MAX_ATTEMPTS).build();

        sender = AgenorRuntime.builder()
                .messageDispatcher(senderFactory.messageDispatcher(() -> directory))
                .agentRegistry(directory).agentDiscovery(directory).agentResolver(directory)
                .build();
        receiver = AgenorRuntime.builder()
                .messageDispatcher(receiverFactory.messageDispatcher(() -> directory))
                .agentRegistry(directory).agentDiscovery(directory).agentResolver(directory)
                .build();

        agent = new FailingAgent();
        receiver.registerAgent(agent);
        asyncAgent = new AsyncFailingAgent();
        receiver.registerAgent(asyncAgent);
        dialogueAgent = new DialogueFailingAgent();
        receiver.registerAgent(dialogueAgent);

        sender.start().join();
        receiver.start().join();
        awaitResolvable("always-fails");
        awaitResolvable("fails-later");
        awaitResolvable("dialogue-fails");

    }

    @AfterEach
    void tearDown() {
        if (sender != null && sender.isRunning()) sender.stop().join();
        if (receiver != null && receiver.isRunning()) receiver.stop().join();
        if (senderFactory != null) senderFactory.close();
        if (receiverFactory != null) receiverFactory.close();
    }

    @Test
    @DisplayName("a handler that throws is redelivered, then reaches the dead-letter stream")
    void failingHandlerIsRedeliveredThenDeadLettered() {
        var deadLetters = receiverFactory.deadLetterQueue();

        // When a message crosses the transport to a handler that always throws
        sender.getMessageDispatcher().sendTo(Message.builder()
                .topic("task.explode")
                .senderId("coordinator")
                .receiverId("always-fails")
                .content("this will throw")
                .build());

        // Then the transport did not take the mailbox's word for it: the entry went
        // unacknowledged and was redelivered...
        awaitUntil(() -> agent.attempts.get() > 1);

        // ...and after maxDeliveryAttempts it landed in the dead-letter queue, asked through
        // the framework. Until 0.32.0 this assertion had to rebuild the Redis key by hand and
        // run XLEN on a connection of its own, because nothing could read the DLQ back.
        // Wait for this test's own entry: the container, and so the dead-letter stream, is
        // shared by every test in the class, and "not empty" can be another test's leftover.
        awaitUntil(() -> deadLetters.recent(10).stream()
                .anyMatch(dl -> "task.explode".equals(dl.message().topic())));

        assertThat(agent.attempts.get()).isGreaterThan(1);
        assertThat(deadLetters.recent(10))
                .isNotEmpty()
                .anySatisfy(dl -> {
                    assertThat(dl.message().topic()).isEqualTo("task.explode");
                    assertThat(dl.recipientId()).isEqualTo("always-fails");
                    assertThat(dl.reason()).isNotBlank();
                });
    }

    @Test
    @DisplayName("a handler whose returned future fails is redelivered, then dead-lettered")
    void handlerReturningFailedFutureIsRedeliveredThenDeadLettered() {
        var deadLetters = receiverFactory.deadLetterQueue();

        // A handler that fails asynchronously has to reach the transport the same way one that
        // throws does. It did not before 0.30.0, and not because the failure was swallowed:
        // a handler declaring CompletableFuture<Void> was rejected as an invalid signature and
        // never registered at all, so asynchronous work could only be written as a void method
        // that starts a chain and returns — outside every guarantee ADR-033 makes.
        sender.getMessageDispatcher().sendTo(Message.builder()
                .topic("task.explode.later")
                .senderId("coordinator")
                .receiverId("fails-later")
                .content("this will fail after the method returns")
                .build());

        awaitUntil(() -> asyncAgent.attempts.get() > 1);
        awaitUntil(() -> deadLetters.recent(10).stream()
                .anyMatch(dl -> "task.explode.later".equals(dl.message().topic())));

        assertThat(asyncAgent.attempts.get()).isGreaterThan(1);
        assertThat(deadLetters.recent(10))
                .anySatisfy(dl -> {
                    assertThat(dl.message().topic()).isEqualTo("task.explode.later");
                    assertThat(dl.recipientId()).isEqualTo("fails-later");
                });
    }

    @Test
    @DisplayName("a @DialogueHandler that throws is redelivered, then dead-lettered")
    void failingDialogueHandlerIsRedeliveredThenDeadLettered() {
        var deadLetters = receiverFactory.deadLetterQueue();

        // Until 0.37.0 the dialogue lane ended the chain twice over: DialogueHandlerRegistry
        // caught what the handler threw, and DialogueCapability caught anything left. The
        // mailbox saw a success, the entry was acknowledged, and the handler ran exactly once.
        var request = DialogueMessage.builder()
                .conversationId("conv-dialogue-fails")
                .senderId("coordinator")
                .receiverId("dialogue-fails")
                .performative(Performative.REQUEST)
                .content("this will throw")
                .build()
                .toMessage();
        sender.getMessageDispatcher().sendTo(request);

        awaitUntil(() -> dialogueAgent.attempts.get() > 1);
        awaitUntil(() -> deadLetters.recent(10).stream()
                .anyMatch(dl -> request.id().equals(dl.message().id())));

        assertThat(dialogueAgent.attempts.get()).isGreaterThan(1);
        assertThat(deadLetters.recent(10))
                .anySatisfy(dl -> {
                    assertThat(dl.message().id()).isEqualTo(request.id());
                    assertThat(dl.recipientId()).isEqualTo("dialogue-fails");
                    // The handler's own exception, not InvocationTargetException: the reflective
                    // wrapper has no message, and a reason naming it would say nothing.
                    assertThat(dl.reason()).startsWith("IllegalStateException: dialogue handler failed");
                });
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("condition never became true within " + TIMEOUT);
    }

    private void awaitResolvable(String agentId) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = sender.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Agent '" + agentId + "' never became resolvable");
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    /** Its handler always throws, which is the whole point. */
    static class FailingAgent extends BaseAgent {

        final AtomicInteger attempts = new AtomicInteger();

        FailingAgent() {
            super("always-fails", "Always Fails");
        }

        @AgenorMessageHandler("task.explode")
        public void handle(Message message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("handler failed on purpose: " + message.id());
        }
    }

    /**
     * Its handler returns normally and fails afterwards, which is the shape every handler doing
     * real asynchronous work has.
     */
    static class AsyncFailingAgent extends BaseAgent {

        final AtomicInteger attempts = new AtomicInteger();

        AsyncFailingAgent() {
            super("fails-later", "Fails Later");
        }

        @AgenorMessageHandler("task.explode.later")
        public CompletableFuture<Void> handle(Message message) {
            attempts.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("async handler failed on purpose: " + message.id());
            });
        }
    }

    /** The dialogue lane's counterpart of {@link FailingAgent}. */
    static class DialogueFailingAgent extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicInteger attempts = new AtomicInteger();

        DialogueFailingAgent() {
            super("dialogue-fails", "Dialogue Fails");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("dialogue handler failed on purpose: " + message.id());
        }
    }
}
