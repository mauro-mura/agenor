package dev.agenor.examples.distributed;

import dev.agenor.adapters.a2a.AgenorA2AAdapter;
import dev.agenor.adapters.a2a.AgenorAgentExecutor;
import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Event;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgenorAgentExecutor} on one runtime, the agent it fronts on another: a shared PostgreSQL
 * directory and a shared Valkey transport, wired as {@link CrossRuntimeDialogueIT} wires them.
 *
 * <p>The executor waits for the reply on a temporary recipient, {@code a2a-client-<taskId>}. On
 * one node the agent's reply finds that subscription in the dispatcher's local map. On two, the
 * agent's node has no such handler and has to resolve the recipient through the directory, so
 * the reply only arrives if the executor made the recipient resolvable. The same test runs
 * against an agent on the executor's own node as the control: if both fail, the defect is the
 * transport; if only the remote one fails, it is the node.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("A2A executor in front of an agent on another node (PostgreSQL + Valkey)")
class A2AExecutorCrossNodeIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "agenor_test")
            .withEnv("POSTGRES_USER", "agenor")
            .withEnv("POSTGRES_PASSWORD", "agenor_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .withStartupTimeout(Duration.ofSeconds(60));

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory factory1;
    private RedisMessagingFactory factory2;
    private JdbcAgentDirectory directory1;
    private JdbcAgentDirectory directory2;
    private AgenorRuntime runtime1;
    private AgenorRuntime runtime2;

    @BeforeEach
    void setUp() {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        var jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/agenor_test";
        var config = JdbcDirectoryConfig.of(jdbcUrl, "agenor", "agenor_test");
        // A prefix per test: the streams and the directory rows outlive a test in the shared
        // containers, and a consumer group left by an earlier one must not take this one's messages
        var prefix = "a2a-it-" + System.nanoTime();

        factory1 = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId(prefix + "-node-1").consumerGroupPrefix(prefix).build();
        factory2 = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId(prefix + "-node-2").consumerGroupPrefix(prefix).build();
        directory1 = JdbcAgentDirectory.create(config);
        directory2 = JdbcAgentDirectory.create(config);

        runtime1 = AgenorRuntime.builder()
                .messageDispatcher(factory1.messageDispatcher(directory1::resolver))
                .agentRegistry(directory1.registry())
                .agentDiscovery(directory1.discovery())
                .agentResolver(directory1.resolver())
                .build();
        runtime2 = AgenorRuntime.builder()
                .messageDispatcher(factory2.messageDispatcher(directory2::resolver))
                .agentRegistry(directory2.registry())
                .agentDiscovery(directory2.discovery())
                .agentResolver(directory2.resolver())
                .build();

        runtime1.registerAgent(new WorkerAgent("worker-local"));
        runtime2.registerAgent(new WorkerAgent("worker-remote"));

        runtime1.start().join();
        runtime2.start().join();
        awaitResolvable("worker-remote");
    }

    @AfterEach
    void tearDown() {
        if (runtime1 != null && runtime1.isRunning()) runtime1.stop().join();
        if (runtime2 != null && runtime2.isRunning()) runtime2.stop().join();
        if (factory1 != null) factory1.close();
        if (factory2 != null) factory2.close();
        if (directory1 != null) directory1.close();
        if (directory2 != null) directory2.close();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({
            "worker-local,  inform",
            "worker-local,  agree-then-inform",
            "worker-remote, inform",
            "worker-remote, agree-then-inform"
    })
    void theFinalReplyReachesTheExecutor(String agentId, String directive) {
        var executor = executorOnNode1(agentId);
        EventQueue queue = mock(EventQueue.class);

        var taskId = "task-" + agentId + "-" + directive + "-" + System.nanoTime();
        executor.execute(context(taskId, directive), queue);

        List<Event> events = events(queue);
        List<TaskStatus> statuses = events.stream()
                .filter(TaskStatusUpdateEvent.class::isInstance)
                .map(e -> ((TaskStatusUpdateEvent) e).getStatus())
                .toList();
        TaskStatus last = statuses.get(statuses.size() - 1);
        assertThat(last.state())
                .as("final state; status message: %s", text(last))
                .isEqualTo(TaskState.COMPLETED);
        assertThat(events).filteredOn(TaskArtifactUpdateEvent.class::isInstance)
                .flatExtracting(e -> ((TaskArtifactUpdateEvent) e).getArtifact().parts())
                .extracting(p -> ((TextPart) p).getText())
                .containsExactly(agentId + " did: " + directive);
        // The requester was in the directory for the life of the task, and only then
        awaitUnresolvable("a2a-client-" + taskId);
    }

    /**
     * The client-side counterpart: {@code AgenorA2AAdapter.sendInternal} waits on a temporary
     * subscription too, for the message's sender. A2AIntegrationExample names a sender no runtime
     * registers, so this asks the same question of the adapter that the test above asks of the
     * executor.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"worker-local", "worker-remote"})
    void anAdapterOnNode1HearsTheReply(String agentId) throws Exception {
        var adapter = new AgenorA2AAdapter(runtime1.getMessageDispatcher(),
                runtime1.getAgentDirectory(), "gateway", Duration.ofSeconds(8));

        DialogueMessage reply = adapter.send(DialogueMessage.builder()
                        .senderId("client-agent")
                        .receiverId(agentId)
                        .performative(Performative.REQUEST)
                        .content("inform")
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.content()).isEqualTo(agentId + " did: inform");
    }

    private AgenorAgentExecutor executorOnNode1(String agentId) {
        return new AgenorAgentExecutor(agentId, runtime1.getMessageDispatcher(),
                runtime1.getAgentDirectory(), Duration.ofSeconds(8));
    }

    /**
     * Answers each REQUEST with an INFORM, preceded by an AGREE when the request says
     * {@code agree-then-inform}: the two shapes ADR-026 allows.
     */
    public static class WorkerAgent extends BaseAgent {

        private final DialogueCapability dialogue = new DialogueCapability(this);

        WorkerAgent(String id) {
            super(id, id);
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handle(DialogueMessage msg) {
            String directive = String.valueOf(msg.content());
            if (directive.equals("agree-then-inform")) {
                dialogue.agree(msg).join();
            }
            dialogue.inform(msg, getAgentId() + " did: " + directive);
        }
    }

    private static RequestContext context(String taskId, String text) {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn(taskId);
        when(context.getContextId()).thenReturn("ctx-" + taskId);
        when(context.getMessage()).thenReturn(new io.a2a.spec.Message.Builder()
                .messageId("msg-" + taskId)
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart(text, null)))
                .build());
        return context;
    }

    private static List<Event> events(EventQueue queue) {
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(queue, atLeastOnce()).enqueueEvent(events.capture());
        return events.getAllValues();
    }

    private static String text(TaskStatus status) {
        if (status.message() == null) {
            return "<none>";
        }
        return status.message().getParts().stream()
                .map(p -> ((TextPart) p).getText())
                .reduce("", String::concat);
    }

    private void awaitUnresolvable(String agentId) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (runtime2.getAgentDirectory().resolveEndpoint(agentId).join().isEmpty()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + agentId, e);
            }
        }
        throw new AssertionError("'" + agentId + "' is still in the directory after its task ended");
    }

    private void awaitResolvable(String agentId) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = runtime1.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + agentId, e);
            }
        }
        throw new AssertionError("Agent '" + agentId + "' never became resolvable from node 1");
    }
}
