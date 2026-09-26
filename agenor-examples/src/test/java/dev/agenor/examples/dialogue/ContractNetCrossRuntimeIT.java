package dev.agenor.examples.dialogue;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.dialogue.Commitment;
import dev.agenor.core.dialogue.CommitmentState;
import dev.agenor.examples.dialogue.ContractNetExample.Manager;
import dev.agenor.examples.dialogue.ContractNetExample.Task;
import dev.agenor.examples.dialogue.ContractNetExample.Worker;
import dev.agenor.runtime.AgenorRuntime;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract Net across two runtimes: the agents of {@link ContractNetExample}, unchanged, over a
 * shared Valkey transport and a shared PostgreSQL directory.
 *
 * <p>{@link dev.agenor.examples.distributed.CrossRuntimeDialogueIT} already covers a
 * REQUEST/INFORM pair this way. Contract Net was not covered by it or by anything else:
 * {@code callForProposals} appeared only in {@code DefaultConversationManagerTest}, on the
 * in-memory dispatcher. So the ADR-004 promise — that going distributed needs no change to
 * agent code — was documented for the multi-party protocol and never executed for it.
 *
 * <p>A one-runtime test would prove nothing here. {@code sendTo} addressed to an agent in the
 * same JVM takes the dispatcher's local fast path and never reaches Redis
 * (`docs/adapters/redis.md`), so the manager and the workers have to sit on different nodes for
 * a single byte to be serialised.
 *
 * <p>What this pins down beyond the round trip: a CFP fanned out to three agents on another node
 * collects all three proposals, the winning bid is readable after crossing the wire, and the
 * commitment the `AGREE` creates settles to {@code FULFILLED} when the worker's `INFORM` comes
 * back. Its direction comes from {@code ContractNetProtocol}, not from the performative, and
 * that override is only exercised once the message has actually travelled.
 *
 * <p>Enable with: {@code mvn verify -Dintegration.tests.enabled=true -pl agenor-examples}
 *
 * @since 0.36.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Contract Net across two runtimes — integration tests (PostgreSQL + Valkey)")
class ContractNetCrossRuntimeIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final List<String> WORKER_IDS = List.of("worker-1", "worker-2", "worker-3");

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

    private RedisMessagingFactory managerFactory;
    private RedisMessagingFactory workerFactory;
    private JdbcAgentDirectory managerDirectory;
    private JdbcAgentDirectory workerDirectory;
    private AgenorRuntime managerRuntime;
    private AgenorRuntime workerRuntime;
    private Manager manager;

    @BeforeEach
    void setUp() {
        var redisUri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        var jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/agenor_test";
        var config = JdbcDirectoryConfig.of(jdbcUrl, "agenor", "agenor_test");

        // Separate factories and separate directories, so the manager's node genuinely has to
        // read the workers' registrations back out of Postgres to address them.
        managerFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("cn-node-1").consumerGroupPrefix("agenor-cn-it").build();
        workerFactory = RedisMessagingFactory.builder()
                .uri(redisUri).nodeId("cn-node-2").consumerGroupPrefix("agenor-cn-it").build();
        managerDirectory = JdbcAgentDirectory.create(config);
        workerDirectory = JdbcAgentDirectory.create(config);

        managerRuntime = AgenorRuntime.builder()
                .messageDispatcher(managerFactory.messageDispatcher(managerDirectory::resolver))
                .agentRegistry(managerDirectory.registry())
                .agentDiscovery(managerDirectory.discovery())
                .agentResolver(managerDirectory.resolver())
                .build();
        workerRuntime = AgenorRuntime.builder()
                .messageDispatcher(workerFactory.messageDispatcher(workerDirectory::resolver))
                .agentRegistry(workerDirectory.registry())
                .agentDiscovery(workerDirectory.discovery())
                .agentResolver(workerDirectory.resolver())
                .build();

        // The example's own agents, with the example's own efficiencies: worker-2 is the most
        // efficient, so it is the one the lowest-cost comparator has to pick.
        manager = new Manager();
        managerRuntime.registerAgent(manager);
        workerRuntime.registerAgent(new Worker("worker-1", 0.6));
        workerRuntime.registerAgent(new Worker("worker-2", 0.9));
        workerRuntime.registerAgent(new Worker("worker-3", 0.4));

        managerRuntime.start().join();
        workerRuntime.start().join();
    }

    @AfterEach
    void tearDown() {
        if (managerRuntime != null && managerRuntime.isRunning()) managerRuntime.stop().join();
        if (workerRuntime != null && workerRuntime.isRunning()) workerRuntime.stop().join();
        if (managerFactory != null) managerFactory.close();
        if (workerFactory != null) workerFactory.close();
        if (managerDirectory != null) managerDirectory.close();
        if (workerDirectory != null) workerDirectory.close();
    }

    @Test
    @DisplayName("every worker resolves to the node that owns it")
    void resolvesEveryWorkerToItsOwningNode() {
        for (String workerId : WORKER_IDS) {
            var endpoint = awaitEndpoint(workerId);
            assertThat(endpoint.nodeId()).as(workerId).isEqualTo("cn-node-2");
            assertThat(endpoint.transportType()).as(workerId).isEqualTo("redis");
        }
    }

    @Test
    @DisplayName("a CFP fanned out across the wire collects every proposal and picks the cheapest")
    void awardsTheTaskAcrossRuntimes() throws Exception {
        awaitAllWorkers();

        String winner = manager.allocateTask(new Task("data-processing", 100), WORKER_IDS)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Bid costs carry a random factor, but the three efficiencies are fixed, so the ranking
        // is not: worker-2 is always the cheapest. A dropped proposal would show up here as a
        // different winner rather than as a timeout.
        assertThat(winner).isEqualTo("worker-2");
    }

    @Test
    @DisplayName("the commitment the AGREE creates settles once the worker reports back")
    void commitmentReachesFulfilledAfterTheInform() throws Exception {
        awaitAllWorkers();

        manager.allocateTask(new Task("data-processing", 100), WORKER_IDS)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        var commitment = awaitCommitmentState(CommitmentState.FULFILLED);

        // Direction is the point: under Contract Net the AGREE binds its receiver, so the
        // performer is the worker that bid, not the manager that sent it.
        assertThat(commitment.getPerformer()).isEqualTo("worker-2");
        assertThat(commitment.getRequester()).isEqualTo("manager");
    }

    /**
     * Waits until every worker is resolvable from the manager's runtime.
     */
    private void awaitAllWorkers() {
        WORKER_IDS.forEach(this::awaitEndpoint);
    }

    /**
     * Waits for {@code agentId} to be resolvable from the manager's runtime with a non-blank
     * node id.
     *
     * @param agentId an agent registered by the worker runtime
     * @return its endpoint
     */
    private AgentEndpoint awaitEndpoint(String agentId) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = managerRuntime.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return found.get();
            }
            sleep();
        }
        throw new AssertionError("Agent '" + agentId + "' never became resolvable with a non-blank "
                + "node id — the owning runtime advertised no endpoint");
    }

    /**
     * Waits for the manager's single commitment to reach {@code expected}. The worker replies
     * with its INFORM after a short delay of its own, so the state is not settled when
     * {@code allocateTask} completes.
     *
     * @param expected the state the commitment should reach
     * @return the commitment, in that state
     */
    private Commitment awaitCommitmentState(CommitmentState expected) {
        var deadline = Instant.now().plus(TIMEOUT);
        Commitment last = null;
        while (Instant.now().isBefore(deadline)) {
            last = manager.awaitedCommitment().orElse(null);
            if (last != null && last.getState() == expected) {
                return last;
            }
            sleep();
        }
        throw new AssertionError("Commitment never reached " + expected + " — last seen: "
                + (last == null ? "none recorded" : last.getState()));
    }

    private void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }
}
