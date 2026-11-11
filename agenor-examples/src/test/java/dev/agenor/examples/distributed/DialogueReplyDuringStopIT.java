package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent stopped in an orderly way while a dialogue handler is running still sends the reply
 * that handler was written to send.
 *
 * <p>{@code BaseAgent.stop()} waits for handlers already in flight, but only in its last step,
 * when it closes the mailbox. The stop hooks run first, and {@code DialogueCapability} shuts
 * down from one: by the time the handler calls {@code inform}, the capability is gone and the
 * call throws. The work was done and its result is discarded; on Redis the entry is left
 * unacknowledged on a node that is going away, and the requester waits out its own timeout.
 * agenor-sentinel measured it on 0.39.0 at every orderly stop with a request in flight.
 *
 * <p>It is the mirror of {@link DialogueDuringAgentStartIT}: at start, dialogue arrived too late
 * for the messages already waiting; at stop, it leaves too early for the ones being handled.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("A dialogue reply sent while the agent stops — integration test (Valkey)")
class DialogueReplyDuringStopIT {

    private static final String PREFIX = "agenor-stop-it";
    private static final Duration HANDLING = Duration.ofMillis(1_500);

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory askerFactory;
    private RedisMessagingFactory workerFactory;
    private AgenorRuntime askerRuntime;
    private AgenorRuntime workerRuntime;

    @AfterEach
    void tearDown() {
        if (askerRuntime != null && askerRuntime.isRunning()) askerRuntime.stop().join();
        if (workerRuntime != null && workerRuntime.isRunning()) workerRuntime.stop().join();
        if (askerFactory != null) askerFactory.close();
        if (workerFactory != null) workerFactory.close();
    }

    @Test
    @DisplayName("the handler's INFORM reaches the requester, and nothing is left pending")
    void aReplySentWhileStoppingReachesTheRequester() throws Exception {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        var directory = new InMemoryAgentDirectory();
        askerFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("stop-node-1").consumerGroupPrefix(PREFIX)
                .readBlockTimeoutMs(500).build();
        workerFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("stop-node-2").consumerGroupPrefix(PREFIX)
                .readBlockTimeoutMs(500).build();
        askerRuntime = AgenorRuntime.builder()
                .messageDispatcher(askerFactory.messageDispatcher(() -> directory))
                .agentRegistry(directory).agentDiscovery(directory).agentResolver(directory)
                .build();
        workerRuntime = AgenorRuntime.builder()
                .messageDispatcher(workerFactory.messageDispatcher(() -> directory))
                .agentRegistry(directory).agentDiscovery(directory).agentResolver(directory)
                .build();

        var asker = new Asker();
        askerRuntime.registerAgent(asker);
        var worker = new SlowWorker();
        workerRuntime.registerAgent(worker);
        askerRuntime.start().join();
        workerRuntime.start().join();

        // A request reaches the worker, whose handler is still working when its runtime stops
        var reply = asker.dialogue.request(SlowWorker.ID, "convert this", Duration.ofSeconds(15));
        assertThat(worker.handling.await(10, TimeUnit.SECONDS)).as("handler started").isTrue();
        var stopStarted = Instant.now();
        workerRuntime.stop().join();

        assertThat(worker.replyFailure.get()).as("the handler's inform() failure").isNull();
        var answer = reply.get(10, TimeUnit.SECONDS);
        assertThat(answer.performative()).isEqualTo(Performative.INFORM);
        assertThat(answer.content()).isEqualTo("converted");
        // The stop did wait for the handler, as it did before: what changes is the order
        assertThat(Duration.between(stopStarted, Instant.now()))
                .isGreaterThanOrEqualTo(HANDLING.minusMillis(500));
    }

    static class Asker extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);

        Asker() {
            super("asker", "Asker");
        }
    }

    static class SlowWorker extends BaseAgent {
        static final String ID = "slow-worker";

        final DialogueCapability dialogue = new DialogueCapability(this);
        final CountDownLatch handling = new CountDownLatch(1);
        final AtomicReference<Throwable> replyFailure = new AtomicReference<>();

        SlowWorker() {
            super(ID, "Slow Worker");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage request) throws InterruptedException {
            handling.countDown();
            Thread.sleep(HANDLING.toMillis());
            try {
                dialogue.inform(request, "converted").join();
            } catch (RuntimeException e) {
                replyFailure.set(e);
                throw e;
            }
        }
    }
}
