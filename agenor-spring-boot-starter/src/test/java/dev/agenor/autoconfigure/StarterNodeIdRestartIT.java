package dev.agenor.autoconfigure;

import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A starter-built node restarted under the same {@code agenor.messaging.redis.node-id} handles
 * the requests its previous run accepted and did not get to.
 *
 * <p>The node id names the node's stream and its consumer. Before 0.41.0 the starter had no
 * property for it, so every start took a new UUID: whatever the previous run left in its stream,
 * read or not, stayed there with nobody reading it, and the requester waited out its timeout.
 * agenor-sentinel measured it as requests timing out across every restart of a worker node.
 *
 * <p>The worker handles one request at a time, slowly, and its context is closed while the first
 * is in flight. That one is answered by the stopping run; the rest are answered by the next run,
 * which gets them within seconds. The reclaim timeout is a minute, so they came from the node
 * reading its own stream again, not from the reclaim.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-spring-boot-starter}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Starter — a node restarted under a fixed node-id gets its mail (Valkey)")
class StarterNodeIdRestartIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HANDLING = Duration.ofMillis(1_000);
    private static final String ASKER_ID = "restart-asker";
    private static final String WORKER_ID = "restart-worker";
    private static final int REQUESTS = 3;

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private ApplicationContextRunner node(Class<?> agents, String... extra) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
                .withUserConfiguration(agents)
                .withPropertyValues(
                        "agenor.messaging.provider=redis",
                        "agenor.messaging.redis.uri=redis://" + valkey.getHost() + ":"
                                + valkey.getMappedPort(6379),
                        "agenor.messaging.redis.consumer-group-prefix=agenor-node-id-it",
                        "agenor.messaging.redis.read-block-timeout-ms=500",
                        "agenor.messaging.redis.pending-entries-timeout-ms=60000",
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.jdbc.url=jdbc:h2:mem:starter_node_id"
                                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                .withPropertyValues(extra);
    }

    private ApplicationContextRunner worker(Class<?> run) {
        return node(run, "agenor.messaging.redis.node-id=restart-worker-node");
    }

    @Test
    @DisplayName("requests the first run did not handle are answered by the next one")
    void theNextRunAnswersWhatThePreviousOneLeft() {
        node(AskerNode.class).run(askerNode -> {
            assertThat(askerNode).hasNotFailed();
            var asker = askerNode.getBean(Asker.class);
            List<CompletableFuture<DialogueMessage>> replies = new ArrayList<>();

            worker(FirstRun.class).run(first -> {
                assertThat(first).hasNotFailed();
                awaitResolvable(askerNode.getBean(AgenorRuntime.class));
                for (int i = 0; i < REQUESTS; i++) {
                    replies.add(asker.ask("job-" + i));
                }
                assertThat(first.getBean(Worker.class).handling.await(10, TimeUnit.SECONDS))
                        .as("the first run started handling").isTrue();
            }); // closing the context stops the first run with one request in flight

            worker(SecondRun.class).run(second -> {
                assertThat(second).hasNotFailed();
                var answeredBy = new ArrayList<String>();
                for (var reply : replies) {
                    var answer = reply.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    assertThat(answer.performative()).isEqualTo(Performative.INFORM);
                    answeredBy.add(answer.contentAs(String.class));
                }
                assertThat(answeredBy).as("which run answered each request")
                        .contains("first", "second").hasSize(REQUESTS);
            });
        });
    }

    /** Waits until the worker is advertised, so the requests have a node to go to. */
    private static void awaitResolvable(AgenorRuntime runtime) throws InterruptedException {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = runtime.getAgentDirectory().resolveEndpoint(WORKER_ID).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Worker never resolved with a node id");
    }

    @Configuration(proxyBeanMethods = false)
    static class AskerNode {
        @Bean
        Asker asker(AgenorRuntime runtime) {
            var agent = new Asker();
            runtime.registerAgent(agent);
            return agent;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FirstRun {
        @Bean
        Worker worker(AgenorRuntime runtime) {
            var agent = new Worker("first");
            runtime.registerAgent(agent);
            return agent;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondRun {
        @Bean
        Worker worker(AgenorRuntime runtime) {
            var agent = new Worker("second");
            runtime.registerAgent(agent);
            return agent;
        }
    }

    static class Asker extends BaseAgent {
        private final DialogueCapability dialogue = new DialogueCapability(this);

        Asker() {
            super(ASKER_ID, "Restart Asker");
        }

        CompletableFuture<DialogueMessage> ask(String content) {
            return dialogue.request(WORKER_ID, content, TIMEOUT.plus(TIMEOUT));
        }
    }

    static class Worker extends BaseAgent {
        private final DialogueCapability dialogue = new DialogueCapability(this);
        private final String run;
        final CountDownLatch handling = new CountDownLatch(1);

        Worker(String run) {
            super(WORKER_ID, "Restart Worker");
            this.run = run;
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage msg) throws InterruptedException {
            handling.countDown();
            Thread.sleep(HANDLING.toMillis());
            dialogue.inform(msg, run).join();
        }
    }
}
