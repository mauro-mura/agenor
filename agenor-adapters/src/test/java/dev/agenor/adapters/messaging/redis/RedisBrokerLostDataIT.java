package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.TransportEndpoint;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A broker that loses its data takes the consumer groups with it, while sending keeps working,
 * because {@code XADD} recreates a stream. A loop that did not recreate its group failed with
 * {@code NOGROUP} once a second for ever, at WARN, with the agents RUNNING and health UP.
 *
 * <p>{@code DEBUG RESTART} is the case as it happens: the connections drop and the server comes
 * back empty, on the same port, which a restarted container would not guarantee a test.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Redis consumer loops after the broker lost its data (Valkey)")
class RedisBrokerLostDataIT {

    private static final long AFTER_FLUSH_SECONDS = 20;
    /** Lettuce's reconnect backoff reaches 30 s, and a failed attempt costs one more round of it. */
    private static final long AFTER_RESTART_SECONDS = 120;

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            // An absolute path, or DEBUG RESTART looks for the binary in the working directory and refuses.
            .withCommand("/usr/local/bin/valkey-server", "--enable-debug-command", "yes",
                    "--save", "", "--appendonly", "no")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private String uri;
    private RedisMessagingFactory factory;

    @BeforeEach
    void setUp() {
        uri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        factory = RedisMessagingFactory.builder().uri(uri).build();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("a node reads its stream again after FLUSHALL, including what was sent before the repair")
    void node_readsAgain_afterFlush() throws Exception {
        var transport = factory.messageTransport();
        var endpoint = new TransportEndpoint("redis", factory.config().nodeId(), Map.of());
        var received = new CountDownLatch(1);
        transport.subscribe(endpoint, msg -> {
            received.countDown();
            return CompletableFuture.completedFuture(null);
        });

        flushBroker();
        // One send, right away: a node's group restarts at 0, so this one must not be skipped.
        transport.send(endpoint, Message.builder().receiverId("agent").content("after").build()).join();

        assertThat(received.await(AFTER_FLUSH_SECONDS, TimeUnit.SECONDS))
                .as("message delivered after the broker lost its groups").isTrue();
    }

    @Test
    @DisplayName("a topic subscriber receives again after FLUSHALL")
    void topic_readsAgain_afterFlush() throws Exception {
        var publisher = factory.topicPublisher();
        var received = new CountDownLatch(1);
        publisher.subscribeTopic("lost.data", msg -> {
            received.countDown();
            return CompletableFuture.completedFuture(null);
        });

        flushBroker();

        // A topic's group restarts at $, so what is published before the repair is not delivered.
        assertThat(publishUntil(publisher, "lost.data", received, AFTER_FLUSH_SECONDS))
                .as("publication delivered after the broker lost its groups").isTrue();
    }

    @Test
    @DisplayName("nodes and subscribers read again after the broker restarted empty")
    void bothRead_afterBrokerRestartedEmpty() throws Exception {
        var transport = factory.messageTransport();
        var publisher = factory.topicPublisher();
        var endpoint = new TransportEndpoint("redis", factory.config().nodeId(), Map.of());
        var nodeReceived = new CountDownLatch(1);
        var topicReceived = new CountDownLatch(1);
        transport.subscribe(endpoint, msg -> {
            nodeReceived.countDown();
            return CompletableFuture.completedFuture(null);
        });
        publisher.subscribeTopic("restarted", msg -> {
            topicReceived.countDown();
            return CompletableFuture.completedFuture(null);
        });

        String before = runId();
        // A key no loop recreates: the streams themselves may be back before this can look.
        valkey.execInContainer("valkey-cli", "set", "marker", "1");
        valkey.execInContainer("valkey-cli", "debug", "restart");
        // DEBUG RESTART answers with an error and carries on if it cannot restart, and a server that
        // never went away would pass everything below without showing anything.
        assertThat(runId()).as("the server really restarted").isNotEqualTo(before);
        assertThat(valkey.execInContainer("valkey-cli", "exists", "marker").getStdout().trim())
                .as("the server came back without its data").isEqualTo("0");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AFTER_RESTART_SECONDS);
        while (System.nanoTime() < deadline
                && (nodeReceived.getCount() > 0 || topicReceived.getCount() > 0)) {
            try {
                transport.send(endpoint, Message.builder().receiverId("agent").content("x").build())
                        .get(5, TimeUnit.SECONDS);
                publisher.publish(Message.builder().topic("restarted").content("x").build())
                        .get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // Still reconnecting: the point is what happens once it has.
            }
            Thread.sleep(250);
        }

        assertThat(nodeReceived.getCount()).as("node stream read again").isZero();
        assertThat(topicReceived.getCount()).as("topic read again").isZero();
    }

    private boolean publishUntil(RedisTopicPublisher publisher, String topic, CountDownLatch received,
                                 long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            publisher.publish(Message.builder().topic(topic).content("after").build()).join();
            if (received.await(250, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    private static String runId() throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            var info = valkey.execInContainer("valkey-cli", "info", "server").getStdout();
            var line = info.lines().filter(l -> l.startsWith("run_id:")).findFirst();
            if (line.isPresent()) {
                return line.get().trim();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the server did not answer INFO");
    }

    private void flushBroker() {
        var client = RedisClient.create(uri);
        try (var connection = client.connect()) {
            connection.sync().flushall();
        } finally {
            client.shutdown();
        }
    }
}
