package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.examples.distributed.CrossRuntimeExample.PricingServiceAgent;
import dev.agenor.examples.distributed.CrossRuntimeExample.Quote;
import dev.agenor.examples.distributed.CrossRuntimeExample.QuoteRequest;
import dev.agenor.examples.distributed.CrossRuntimeExample.QuoteRequesterAgent;
import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for cross-runtime dialogue: two isolated {@link AgenorRuntime}
 * instances, a shared PostgreSQL directory and a shared Valkey transport.
 *
 * <p>This is the test the suite was missing. Every dialogue test before it used the in-memory
 * dispatcher, where {@code sendTo} finds the recipient in a local map and the message never
 * leaves the JVM — so nothing exercised the path where the directory has to tell one node how
 * to reach another. That gap is why a registration writing a blank {@code node_id} passed every
 * build while losing every cross-node message in production.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 *
 * @since 0.26.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("Cross-runtime dialogue — integration tests (PostgreSQL + Valkey)")
class CrossRuntimeDialogueIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Container
    static GenericContainer<?> postgres = new GenericContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "agenor_test")
            .withEnv("POSTGRES_USER", "agenor")
            .withEnv("POSTGRES_PASSWORD", "agenor_test")
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
    private QuoteRequesterAgent requester;

    @BeforeEach
    void setUp() {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        var jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/agenor_test";
        var config = JdbcDirectoryConfig.of(jdbcUrl, "agenor", "agenor_test");

        // Two of everything, exactly as CrossRuntimeExample wires it: the runtimes share the two
        // backends and nothing else. Separate directories mean separate connection pools, so
        // runtime 1 genuinely has to read runtime 2's registration back out of Postgres.
        factory1 = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("node-1").consumerGroupPrefix("agenor-it").build();
        factory2 = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("node-2").consumerGroupPrefix("agenor-it").build();
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

        requester = new QuoteRequesterAgent();
        runtime1.registerAgent(requester);
        runtime2.registerAgent(new PricingServiceAgent());

        runtime1.start().join();
        runtime2.start().join();
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

    @Test
    @DisplayName("runtime 1 resolves an agent owned by runtime 2 to node-2 over redis")
    void resolvesRemoteAgentToItsOwningNode() {
        var endpoint = awaitEndpoint("pricing-service");

        // The assertion that would have caught the original defect: before the dispatcher
        // advertised its endpoint, this was a blank node id with transport "local".
        assertThat(endpoint.nodeId()).isEqualTo("node-2");
        assertThat(endpoint.transportType()).isEqualTo("redis");
    }

    @Test
    @DisplayName("a typed REQUEST crosses to the other runtime and its INFORM comes back")
    void deliversTypedRequestAcrossRuntimes() throws Exception {
        awaitEndpoint("pricing-service");

        DialogueMessage reply = requester
                .requestQuote(new QuoteRequest("SKU-42", 3, "EUR"))
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.senderId()).isEqualTo("pricing-service");

        var quote = reply.contentAs(Quote.class);
        assertThat(quote.sku()).isEqualTo("SKU-42");
        assertThat(quote.quantity()).isEqualTo(3);
        assertThat(quote.currency()).isEqualTo("EUR");
        assertThat(quote.total()).isEqualTo(59.97);
    }

    @Test
    @DisplayName("the payload really crosses a serialising transport")
    void payloadArrivesAsAMapNotAsTheRecord() throws Exception {
        awaitEndpoint("pricing-service");

        DialogueMessage reply = requester
                .requestQuote(new QuoteRequest("SKU-1", 1, "EUR"))
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Pins down that contentAs() is doing real work. If the content arrived as a Quote, the
        // two runtimes would be sharing objects in-process and the test would prove nothing
        // about distribution — which is exactly how the payload-typing bug stayed hidden.
        assertThat(reply.content()).isNotInstanceOf(Quote.class);
        assertThat(reply.contentAs(Quote.class).sku()).isEqualTo("SKU-1");
    }

    /**
     * Waits for {@code agentId} to be resolvable from runtime 1 with a non-blank node id.
     *
     * @param agentId the agent registered by the other runtime
     * @return its endpoint
     */
    private AgentEndpoint awaitEndpoint(String agentId) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = runtime1.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return found.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + agentId, e);
            }
        }
        throw new AssertionError("Agent '" + agentId + "' never became resolvable with a non-blank "
                + "node id — the owning runtime advertised no endpoint");
    }
}
