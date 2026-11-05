package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What it means that one consumer loop serves a node's whole stream, one message at a time, and
 * waits for the handler (ADR-033).
 *
 * <p>This pins documented limits, not desired behaviour: {@code docs/adapters/redis.md} §
 * "One loop per node" says what to do about each. The first test is the one that matters. A
 * handler waiting for a reply from another node holds the only loop that can read that reply, so
 * it can only time out, while the same code answers in a millisecond in memory and on one node.
 * A change to the loop that makes it fail is a good change, and should take the page with it.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("One consumer loop per node stream — integration tests (Valkey)")
class SerialNodeConsumerIT {

    /** Short, because the failing cases spend all of it. */
    private static final Duration INNER_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private final List<Runnable> cleanup = new ArrayList<>();
    private InMemoryAgentDirectory directory;
    private String prefix;
    private AgenorRuntime node1;
    private AgenorRuntime node2;
    private Client client;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory();
        prefix = "serial-" + UUID.randomUUID();
        node1 = node("node-1");
        node2 = node("node-2");
        client = new Client();
        node2.registerAgent(client);
    }

    @AfterEach
    void tearDown() {
        cleanup.reversed().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a handler blocking on a reply from another node times out: it holds the loop that would read it")
    void blockingHandlerCannotReceiveAReplyFromAnotherNode() {
        var coordinator = new BlockingCoordinator();
        node1.registerAgent(coordinator);
        node2.registerAgent(new Worker());
        start();

        var reply = client.ask("coordinator");

        assertThat(reply.performative()).isEqualTo(Performative.FAILURE);
        assertThat(coordinator.innerFailure.get()).isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("the same handler answers when the two agents share a node: the fast path skips the loop")
    void blockingHandlerWorksOnOneNode() {
        node1.registerAgent(new BlockingCoordinator());
        node1.registerAgent(new Worker());
        start();

        assertThat(client.ask("coordinator").performative()).isEqualTo(Performative.INFORM);
    }

    @Test
    @DisplayName("the same handler answers in memory, which is why nothing shows this before Redis")
    void blockingHandlerWorksInMemory() {
        var runtime = AgenorRuntime.builder().build();
        var caller = new Client();
        runtime.registerAgent(new BlockingCoordinator());
        runtime.registerAgent(new Worker());
        runtime.registerAgent(caller);
        runtime.start().join();
        cleanup.add(() -> runtime.stop().join());

        assertThat(caller.ask("coordinator").performative()).isEqualTo(Performative.INFORM);
    }

    @Test
    @DisplayName("returning the chain does not help: the loop waits for the returned future as well")
    void handlerReturningTheChainCannotReceiveAReplyEither() {
        var chaining = new ChainingAgent();
        node1.registerAgent(chaining);
        node2.registerAgent(new Worker());
        start();

        node2.getMessageDispatcher().sendTo(Message.builder()
                .topic("job.start").senderId("client").receiverId("chaining").content("go").build()).join();

        awaitUntil(() -> chaining.innerFailure.get() != null);
        assertThat(chaining.innerFailure.get()).isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("a @DialogueHandler that chains and returns releases the loop, and gets the reply")
    void handlerThatChainsAndReturnsReceivesTheReply() {
        node1.registerAgent(new CallbackCoordinator());
        node2.registerAgent(new Worker());
        start();

        assertThat(client.ask("coordinator").performative()).isEqualTo(Performative.INFORM);
    }

    @Test
    @DisplayName("a handler on a topic may block on a reply: a topic has its own loop")
    void blockingHandlerOnATopicReceivesTheReply() {
        var subscriber = new TopicSubscriber();
        node1.registerAgent(subscriber);
        node2.registerAgent(new Worker());
        start();
        // A topic group starts at $, so a message published before the group exists is not seen
        sleep(500);

        node2.getMessageDispatcher().publish(Message.builder()
                .topic("job.announced").senderId("client").content("go").build()).join();

        awaitUntil(() -> subscriber.innerReply.get() != null || subscriber.innerFailure.get() != null);
        assertThat(subscriber.innerFailure.get()).isNull();
        assertThat(subscriber.innerReply.get().performative()).isEqualTo(Performative.INFORM);
    }

    @Test
    @DisplayName("a handler holding the loop delays every other agent on its node")
    void slowHandlerDelaysItsNeighbour() throws Exception {
        var slow = new HoldingAgent();
        node1.registerAgent(slow);
        node1.registerAgent(new Worker());
        start();

        var held = client.dialogue.request("holding", "go", TIMEOUT);
        assertThat(slow.entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        var neighbour = client.dialogue.request("worker", "go", TIMEOUT);
        sleep(1000);
        assertThat(neighbour).as("a neighbour's message waits behind the held loop").isNotDone();

        slow.release.countDown();
        assertThat(neighbour.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).performative())
                .isEqualTo(Performative.INFORM);
        held.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a request whose caller has given up still runs when its node comes back")
    void timedOutRequestRunsWhenTheNodeComesBack() {
        // The directory still says node-3 owns it, and node-3 is not consuming
        directory.register(AgentDescriptor.builder("returning")
                .endpoint(new AgentEndpoint("node-3", "redis", Map.of())).build()).join();
        node2.start().join();

        assertThatThrownBy(() -> client.dialogue.request("returning", "go", Duration.ofMillis(500)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        var returning = new ReturningAgent();
        var node3 = node("node-3");
        node3.registerAgent(returning);
        node3.start().join();

        awaitUntil(() -> returning.invocations.get() > 0);
    }

    // -------------------------------------------------------------------------

    private AgenorRuntime node(String nodeId) {
        var factory = RedisMessagingFactory.builder()
                .uri("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379))
                .nodeId(nodeId)
                .consumerGroupPrefix(prefix)
                .build();
        var runtime = AgenorRuntime.builder()
                .messageDispatcher(factory.messageDispatcher(() -> directory))
                .agentRegistry(directory)
                .agentDiscovery(directory)
                .agentResolver(directory)
                .build();
        cleanup.add(factory::close);
        cleanup.add(() -> {
            if (runtime.isRunning()) {
                runtime.stop().join();
            }
        });
        return runtime;
    }

    private void start() {
        node1.start().join();
        node2.start().join();
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("condition never became true within " + TIMEOUT);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    // -------------------------------------------------------------------------

    static class Client extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);

        Client() {
            super("client", "Client");
        }

        DialogueMessage ask(String target) {
            return dialogue.request(target, "go", TIMEOUT).join();
        }
    }

    static class Worker extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);

        Worker() {
            super("worker", "Worker");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage message) {
            dialogue.inform(message, "done").join();
        }
    }

    /** What agenor-research's Coordinatore does. */
    static class BlockingCoordinator extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicReference<Throwable> innerFailure = new AtomicReference<>();

        BlockingCoordinator() {
            super("coordinator", "Coordinator");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage message) {
            try {
                dialogue.request("worker", "part", INNER_TIMEOUT).join();
                dialogue.inform(message, "ok").join();
            } catch (CompletionException e) {
                innerFailure.set(unwrap(e));
                dialogue.failure(message, "the worker did not answer").join();
            }
        }
    }

    static class CallbackCoordinator extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);

        CallbackCoordinator() {
            super("coordinator", "Coordinator");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage message) {
            dialogue.request("worker", "part", INNER_TIMEOUT).whenComplete((reply, failure) -> {
                if (failure == null) {
                    dialogue.inform(message, "ok");
                } else {
                    dialogue.failure(message, "the worker did not answer");
                }
            });
        }
    }

    /** The form docs/messaging.md recommends to stay inside at-least-once. */
    static class ChainingAgent extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicReference<Throwable> innerFailure = new AtomicReference<>();

        ChainingAgent() {
            super("chaining", "Chaining");
        }

        @AgenorMessageHandler("job.start")
        public CompletableFuture<Void> onStart(Message message) {
            return dialogue.request("worker", "part", INNER_TIMEOUT)
                    .<Void>thenApply(reply -> null)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            innerFailure.compareAndSet(null, unwrap(failure));
                        }
                    });
        }
    }

    static class TopicSubscriber extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicReference<DialogueMessage> innerReply = new AtomicReference<>();
        final AtomicReference<Throwable> innerFailure = new AtomicReference<>();

        TopicSubscriber() {
            super("subscriber", "Subscriber");
        }

        @AgenorMessageHandler("job.announced")
        public void onAnnounced(Message message) {
            try {
                innerReply.set(dialogue.request("worker", "part", INNER_TIMEOUT).join());
            } catch (CompletionException e) {
                innerFailure.set(unwrap(e));
            }
        }
    }

    static class HoldingAgent extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        HoldingAgent() {
            super("holding", "Holding");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage message) throws InterruptedException {
            entered.countDown();
            release.await();
            dialogue.inform(message, "done").join();
        }
    }

    static class ReturningAgent extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicInteger invocations = new AtomicInteger();

        ReturningAgent() {
            super("returning", "Returning");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage message) {
            invocations.incrementAndGet();
        }
    }
}
