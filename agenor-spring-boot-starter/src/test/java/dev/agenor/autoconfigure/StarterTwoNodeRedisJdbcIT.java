package dev.agenor.autoconfigure;

import dev.agenor.core.AgentEndpoint;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two starter-built nodes sharing a Valkey transport and a JDBC directory — the multi-node setup
 * the Redis and JDBC pages document, configured through properties only.
 *
 * <p>Each node is its own Spring context. The directory is a named in-memory H2 database, so
 * both contexts read and write the same rows while the test needs no second container.
 *
 * <p>Before the starter composed its backends into one runtime, the JDBC directory won and the
 * runtime kept the in-memory dispatcher: agents were advertised with a blank node and a
 * {@code local} transport, and a REQUEST to the other node timed out without an error.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-spring-boot-starter}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Starter — two nodes over Valkey with a JDBC directory")
class StarterTwoNodeRedisJdbcIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String REQUESTER_ID = "two-node-requester";
    private static final String RESPONDER_ID = "two-node-responder";

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private ApplicationContextRunner node(Class<?> agents) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
                .withUserConfiguration(agents)
                .withPropertyValues(
                        "agenor.messaging.provider=redis",
                        "agenor.messaging.redis.uri=redis://" + valkey.getHost() + ":"
                                + valkey.getMappedPort(6379),
                        "agenor.messaging.redis.consumer-group-prefix=agenor-starter-it",
                        "agenor.directory.provider=jdbc",
                        "agenor.directory.jdbc.url=jdbc:h2:mem:starter_two_node"
                                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }

    @Test
    @DisplayName("a REQUEST crosses to the other node and its INFORM comes back")
    void requestCrossesNodes() {
        node(ResponderNode.class).run(nodeB -> node(RequesterNode.class).run(nodeA -> {
            assertThat(nodeA).hasNotFailed();
            assertThat(nodeB).hasNotFailed();

            // Node A must find node B's agent advertised under B's node, over redis
            var endpoint = awaitEndpoint(nodeA.getBean(AgenorRuntime.class), RESPONDER_ID);
            assertThat(endpoint.transportType()).isEqualTo("redis");

            // And the message must actually make the trip
            DialogueMessage reply = nodeA.getBean(Requester.class).ask("ping")
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(reply.performative()).isEqualTo(Performative.INFORM);
            assertThat(reply.senderId()).isEqualTo(RESPONDER_ID);
            assertThat(reply.contentAs(String.class)).isEqualTo("pong");
        }));
    }

    /**
     * Waits until {@code agentId} resolves with a non-blank node id.
     *
     * @param runtime the runtime reading the shared directory
     * @param agentId the agent owned by the other node
     * @return its endpoint
     */
    private static AgentEndpoint awaitEndpoint(AgenorRuntime runtime, String agentId)
            throws InterruptedException {
        var deadline = Instant.now().plus(TIMEOUT);
        AgentEndpoint last = null;
        while (Instant.now().isBefore(deadline)) {
            var found = runtime.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent()) {
                last = found.get();
                if (!last.nodeId().isBlank()) {
                    return last;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Agent '" + agentId + "' never resolved with a node id; last "
                + "endpoint seen: " + last);
    }

    @Configuration(proxyBeanMethods = false)
    static class RequesterNode {
        @Bean
        Requester requester(AgenorRuntime runtime) {
            var agent = new Requester();
            runtime.registerAgent(agent);
            return agent;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ResponderNode {
        @Bean
        Responder responder(AgenorRuntime runtime) {
            var agent = new Responder();
            runtime.registerAgent(agent);
            return agent;
        }
    }

    static class Requester extends BaseAgent {
        private final DialogueCapability dialogue = new DialogueCapability(this);

        Requester() {
            super(REQUESTER_ID, "Two-node Requester");
        }

        CompletableFuture<DialogueMessage> ask(String content) {
            return dialogue.request(RESPONDER_ID, content, TIMEOUT);
        }
    }

    static class Responder extends BaseAgent {
        private final DialogueCapability dialogue = new DialogueCapability(this);

        Responder() {
            super(RESPONDER_ID, "Two-node Responder");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void onRequest(DialogueMessage msg) {
            dialogue.inform(msg, "pong");
        }
    }
}
