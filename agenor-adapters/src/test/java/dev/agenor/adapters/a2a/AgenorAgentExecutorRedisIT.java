package dev.agenor.adapters.a2a;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.messaging.MessageDispatcher;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Event;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgenorAgentExecutor} in front of an agent that answers {@code AGREE} and then
 * {@code INFORM}, every message crossing Valkey through {@code RedisMessageDispatcher}.
 *
 * <p>The unit test proves the mapping; this proves it holds when the replies arrive on the
 * transport's own threads, one at a time, rather than inside {@code sendTo}. One node only:
 * the executor and the agent share a dispatcher. Two nodes are a different defect.
 *
 * <p>The agent is a recipient subscription, not a {@code BaseAgent}: this module does not depend
 * on {@code agenor-runtime}, and what is under test is the executor and the transport.
 *
 * <p>Enable via: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-adapters}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("AgenorAgentExecutor — final reply over Redis (Valkey)")
class AgenorAgentExecutorRedisIT {

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private static final String AGENT = "worker";

    private RedisMessagingFactory factory;
    private MessageDispatcher dispatcher;
    /** Everything the agent receives, in order. */
    private final BlockingQueue<DialogueMessage> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void startAgent() {
        factory = RedisMessagingFactory.builder()
                .uri("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379))
                .consumerGroupPrefix("a2a-executor-" + System.nanoTime())
                .build();
        var agentEndpoint = new AgentEndpoint(factory.config().nodeId(), "redis", Map.of());
        AgentResolver resolver = id -> CompletableFuture.completedFuture(
                AGENT.equals(id) ? Optional.of(agentEndpoint) : Optional.empty());
        dispatcher = factory.messageDispatcher(() -> resolver);
    }

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void agreeThenInformCompletesWithTheInform() throws Exception {
        answerRequests(true);
        var executor = new AgenorAgentExecutor(AGENT, dispatcher, Duration.ofSeconds(10));
        EventQueue queue = mock(EventQueue.class);

        executor.execute(context("task-redis-inform"), queue);

        List<Event> events = events(queue);
        assertThat(events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .extracting(e -> ((TaskStatusUpdateEvent) e).getStatus().state())
                .containsExactly(TaskState.SUBMITTED, TaskState.WORKING, TaskState.WORKING,
                        TaskState.COMPLETED);
        assertThat(events).filteredOn(TaskArtifactUpdateEvent.class::isInstance)
                .flatExtracting(e -> ((TaskArtifactUpdateEvent) e).getArtifact().parts())
                .extracting(p -> ((TextPart) p).getText())
                .containsExactly("done: the question");
    }

    @Test
    void cancelAfterAgreeReachesTheAgentFromTheRequester() throws Exception {
        CountDownLatch agreed = answerRequests(false);
        var executor = new AgenorAgentExecutor(AGENT, dispatcher, Duration.ofSeconds(10));
        EventQueue queue = mock(EventQueue.class);

        var running = CompletableFuture.runAsync(() ->
                executor.execute(context("task-redis-cancel"), queue));
        assertThat(agreed.await(5, TimeUnit.SECONDS)).isTrue();
        executor.cancel(context("task-redis-cancel"), mock(EventQueue.class));

        running.get(2, TimeUnit.SECONDS);
        assertThat(received.poll(5, TimeUnit.SECONDS)).extracting(DialogueMessage::performative)
                .isEqualTo(Performative.REQUEST);
        DialogueMessage cancel = received.poll(5, TimeUnit.SECONDS);
        assertThat(cancel).isNotNull();
        assertThat(cancel.performative()).isEqualTo(Performative.CANCEL);
        assertThat(cancel.senderId()).isEqualTo("a2a-client-task-redis-cancel");
    }

    /**
     * Subscribes the agent: it answers each REQUEST with AGREE and, if {@code inform}, then with
     * INFORM. The latch opens once the AGREE has been sent.
     */
    private CountDownLatch answerRequests(boolean inform) {
        CountDownLatch agreed = new CountDownLatch(1);
        dispatcher.subscribeRecipient(AGENT, msg -> {
            DialogueMessage request = DialogueMessage.fromMessage(msg);
            received.add(request);
            if (request.performative() != Performative.REQUEST) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.sendTo(answer(request, Performative.AGREE, "working on it").toMessage())
                    .thenRun(agreed::countDown)
                    .thenCompose(v -> inform
                            ? dispatcher.sendTo(answer(request, Performative.INFORM,
                                    "done: " + request.content()).toMessage())
                            : CompletableFuture.completedFuture(null));
        });
        return agreed;
    }

    private static DialogueMessage answer(DialogueMessage request, Performative performative,
                                          Object content) {
        return DialogueMessage.builder()
                .conversationId(request.conversationId())
                .senderId(AGENT)
                .receiverId(request.senderId())
                .performative(performative)
                .content(content)
                .build();
    }

    private static RequestContext context(String taskId) {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn(taskId);
        when(context.getContextId()).thenReturn("ctx-" + taskId);
        when(context.getMessage()).thenReturn(new io.a2a.spec.Message.Builder()
                .messageId("msg-" + taskId)
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("the question", null)))
                .build());
        return context;
    }

    private static List<Event> events(EventQueue queue) {
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(queue, atLeastOnce()).enqueueEvent(events.capture());
        return events.getAllValues();
    }
}
