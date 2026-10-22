package dev.agenor.adapters.a2a;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.messaging.MessageDispatcher;
import io.a2a.A2A;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgenorA2AClient} against a real A2A server: the SDK's own handlers
 * ({@link A2ATestServer}) in front of {@link AgenorAgentExecutor}, whose agent answers over
 * {@code RedisMessageDispatcher}. Agenor on both sides, which is the case nothing else covers.
 *
 * <p>The agent reads its script from the request text: {@code inform}, {@code agree-inform},
 * {@code failure}, {@code refuse}, or {@code slow} (an {@code AGREE}, then the {@code INFORM}
 * after {@link #SLOW}, longer than the 5 s the SDK server holds a blocking call).
 *
 * <p>Each agent has its own port: the SDK's card resolver reads the card at the root of the host.
 *
 * <p>The client's timeout is {@link #TIMEOUT}; an answer counts as on time when it arrives well
 * before it, because a client that waits out its timeout still returns the right answer.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("AgenorA2AClient — against a real A2A server")
class AgenorA2AClientIT {

    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private static final String AGENT = "worker";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration ON_TIME = Duration.ofSeconds(3);
    private static final Duration SLOW = Duration.ofSeconds(7);

    private static RedisMessagingFactory factory;
    private static MessageDispatcher dispatcher;
    private static A2ATestServer streaming;
    private static A2ATestServer blocking;
    private static A2ATestServer message;

    private final AgenorA2AClient client = new AgenorA2AClient(TIMEOUT);

    @BeforeAll
    static void start() throws Exception {
        valkey.start();
        factory = RedisMessagingFactory.builder()
                .uri("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379))
                .consumerGroupPrefix("a2a-client-" + System.nanoTime())
                .build();
        var agentEndpoint = new AgentEndpoint(factory.config().nodeId(), "redis", Map.of());
        AgentResolver resolver = id -> CompletableFuture.completedFuture(
                AGENT.equals(id) ? Optional.of(agentEndpoint) : Optional.empty());
        dispatcher = factory.messageDispatcher(() -> resolver);
        dispatcher.subscribeRecipient(AGENT, msg -> answer(DialogueMessage.fromMessage(msg)));

        var executor = new AgenorAgentExecutor(AGENT, dispatcher, TIMEOUT);
        streaming = new A2ATestServer(true, executor);
        blocking = new A2ATestServer(false, executor);
        message = new A2ATestServer(false, new MessageReply());
    }

    @AfterAll
    static void stop() {
        for (A2ATestServer server : new A2ATestServer[] {streaming, blocking, message}) {
            if (server != null) {
                server.close();
            }
        }
        if (factory != null) {
            factory.close();
        }
        valkey.stop();
    }

    @ParameterizedTest(name = "{0} card, {1} → {2}")
    @CsvSource({
            "streaming, inform,       INFORM,  done: inform",
            "streaming, agree-inform, INFORM,  done: agree-inform",
            "streaming, failure,      FAILURE, could not: failure",
            "streaming, refuse,       REFUSE,  will not: refuse",
            "blocking,  inform,       INFORM,  done: inform",
            "blocking,  agree-inform, INFORM,  done: agree-inform",
            "blocking,  failure,      FAILURE, could not: failure",
            "blocking,  refuse,       REFUSE,  will not: refuse",
    })
    void theFinalReplyArrivesOnTime(String card, String script, Performative performative, String content)
            throws Exception {
        long start = System.nanoTime();

        DialogueMessage reply = client.send(url(card), request(script), "local")
                .get(TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reply.performative()).isEqualTo(performative);
        assertThat(reply.content()).isEqualTo(content);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(ON_TIME);
    }

    /**
     * The SDK server holds a blocking call for 5 s and then drops what the agent sends; an
     * answer that takes longer reaches only a client that did not block.
     */
    @Test
    void anAnswerSlowerThanFiveSecondsIsStillTheAnswer() throws Exception {
        long start = System.nanoTime();

        DialogueMessage reply = client.send(blocking.url(), request("slow"), "local")
                .get(TIMEOUT.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.content()).isEqualTo("done: slow");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(SLOW.plus(ON_TIME));
    }

    /** An agent may answer with a message instead of a task. */
    @Test
    void aMessageReplyIsAnInform() throws Exception {
        long start = System.nanoTime();

        DialogueMessage reply = client.send(message.url(), request("hello"), "local")
                .get(TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.content()).isEqualTo("echo: hello");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(ON_TIME);
    }

    @Test
    void streamingReportsEachStateAndEndsWithTheInform() throws Exception {
        List<String> states = new CopyOnWriteArrayList<>();

        DialogueMessage reply = client.sendWithStreaming(streaming.url(), request("agree-inform"),
                        "local", (state, message) -> states.add(state))
                .get(TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.content()).isEqualTo("done: agree-inform");
        assertThat(states).contains("WORKING").last().isEqualTo("COMPLETED");
    }

    /** A card that does not declare streaming is answered without it. */
    @Test
    void streamingToAnAgentWithoutItStillAnswers() throws Exception {
        long start = System.nanoTime();

        DialogueMessage reply = client.sendWithStreaming(blocking.url(), request("inform"),
                        "local", (state, message) -> { })
                .get(TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.content()).isEqualTo("done: inform");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(ON_TIME);
    }

    private static String url(String card) {
        return "streaming".equals(card) ? streaming.url() : blocking.url();
    }

    private static DialogueMessage request(String script) {
        return DialogueMessage.builder()
                .senderId("local")
                .receiverId("remote")
                .performative(Performative.REQUEST)
                .content(script)
                .build();
    }

    /** The agent behind the executor: plays the script named in the request. */
    private static CompletableFuture<Void> answer(DialogueMessage request) {
        if (request.performative() != Performative.REQUEST) {
            return CompletableFuture.completedFuture(null);
        }
        String script = String.valueOf(request.content());
        return switch (script) {
            case "inform" -> reply(request, Performative.INFORM, "done: " + script);
            case "agree-inform" -> reply(request, Performative.AGREE, "on it")
                    .thenCompose(v -> reply(request, Performative.INFORM, "done: " + script));
            case "failure" -> reply(request, Performative.FAILURE, "could not: " + script);
            case "refuse" -> reply(request, Performative.REFUSE, "will not: " + script);
            case "slow" -> reply(request, Performative.AGREE, "on it")
                    .thenComposeAsync(v -> reply(request, Performative.INFORM, "done: " + script),
                            CompletableFuture.delayedExecutor(SLOW.toMillis(), TimeUnit.MILLISECONDS));
            default -> reply(request, Performative.REFUSE, "unknown script: " + script);
        };
    }

    private static CompletableFuture<Void> reply(DialogueMessage request, Performative performative,
                                                 String content) {
        return dispatcher.sendTo(DialogueMessage.builder()
                .conversationId(request.conversationId())
                .senderId(AGENT)
                .receiverId(request.senderId())
                .performative(performative)
                .content(content)
                .build()
                .toMessage());
    }

    /** An agent written with the SDK alone, answering with a message rather than a task. */
    private static final class MessageReply implements AgentExecutor {
        @Override
        public void execute(RequestContext context, EventQueue queue) {
            queue.enqueueEvent(A2A.toAgentMessage("echo: " + context.getUserInput(" ")));
        }

        @Override
        public void cancel(RequestContext context, EventQueue queue) {
        }
    }
}
