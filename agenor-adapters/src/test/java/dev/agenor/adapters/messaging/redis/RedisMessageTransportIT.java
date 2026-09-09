package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.TransportEndpoint;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("RedisMessageTransport — integration tests (Valkey)")
class RedisMessageTransportIT {

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory factory;
    private RedisMessageTransport transport;
    private String nodeId;

    @BeforeEach
    void setUp() {
        var uri   = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        factory   = RedisMessagingFactory.builder().uri(uri).build();
        transport = factory.messageTransport();
        nodeId    = factory.config().nodeId();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("send delivers a message to the local node subscriber")
    void send_deliversToLocalNodeSubscriber() throws Exception {
        var latch    = new CountDownLatch(1);
        var received = new AtomicReference<Message>();

        var localEndpoint = new TransportEndpoint("redis", nodeId, Map.of());
        transport.subscribe(localEndpoint, msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var dest = new TransportEndpoint("redis", nodeId, Map.of());
        var msg  = Message.builder().receiverId("target-agent").content("direct-payload").build();
        transport.send(dest, msg).join();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().content()).isEqualTo("direct-payload");
    }

    @Test
    @DisplayName("message envelope fields are preserved through transport serialisation")
    void send_preservesEnvelopeFields() throws Exception {
        var latch    = new CountDownLatch(1);
        var received = new AtomicReference<Message>();

        var local = new TransportEndpoint("redis", nodeId, Map.of());
        transport.subscribe(local, msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var sent = Message.builder()
                .senderId("agent-sender")
                .receiverId("agent-receiver")
                .correlationId("corr-abc")
                .content("transport-data")
                .header("x-trace", "trace-001")
                .build();
        transport.send(new TransportEndpoint("redis", nodeId, Map.of()), sent).join();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        var r = received.get();
        assertThat(r.senderId()).isEqualTo("agent-sender");
        assertThat(r.receiverId()).isEqualTo("agent-receiver");
        assertThat(r.correlationId()).isEqualTo("corr-abc");
        assertThat(r.headers()).containsEntry("x-trace", "trace-001");
    }

    @Test
    @DisplayName("unsubscribing stops message delivery to the node stream")
    void subscribe_afterUnsubscribe_stopsDelivery() throws Exception {
        var counter = new AtomicInteger(0);
        var latch   = new CountDownLatch(1);

        var local = new TransportEndpoint("redis", nodeId, Map.of());
        var sub   = transport.subscribe(local, msg -> {
            counter.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var dest = new TransportEndpoint("redis", nodeId, Map.of());
        transport.send(dest, Message.builder().receiverId("a").content("first").build()).join();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        sub.unsubscribe();
        Thread.sleep(200);

        transport.send(dest, Message.builder().receiverId("a").content("second").build()).join();
        Thread.sleep(500);

        assertThat(counter.get()).isEqualTo(1);
    }

    /**
     * Pins the fix for the case this test used to pin the loss of. A node's inbound group is
     * created at {@code 0} when the transport is constructed, so an entry written before any
     * agent subscribed is still in the group's reach and arrives when one does.
     *
     * <p>"A direct message to a registered but unsubscribed agent" is still not a case in
     * {@code MessageDispatcherContractTests}, for the opposite reason to the original one: in
     * memory that message is dead-lettered, because that transport hands off synchronously and
     * has nowhere to park it; here it is delivered. Two honest answers, so there is nothing for
     * a shared contract to assert.
     */
    @Test
    @DisplayName("a message sent before anything subscribes is delivered once one arrives")
    void send_beforeAnySubscriber_isDeliveredOnSubscribe() throws Exception {
        var dest = new TransportEndpoint("redis", nodeId, Map.of());
        transport.send(dest, Message.builder().receiverId("late-agent").content("early").build())
                .join();

        var latch    = new CountDownLatch(1);
        var received = new AtomicReference<Message>();
        transport.subscribe(new TransportEndpoint("redis", nodeId, Map.of()), msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("the node group exists from transport construction, so nothing is skipped")
                .isTrue();
        assertThat(received.get().content()).isEqualTo("early");
    }

    @Test
    @DisplayName("a message written to a node that has never run is delivered when it starts")
    void send_toNodeThatNeverRan_isDeliveredWhenItStarts() throws Exception {
        var coldNodeId = "cold-node-" + java.util.UUID.randomUUID();

        // Nothing for this node exists yet: no transport, no stream, no group.
        transport.send(new TransportEndpoint("redis", coldNodeId, Map.of()),
                        Message.builder().receiverId("agent-on-cold-node").content("waiting").build())
                .join();

        var coldFactory = RedisMessagingFactory.builder()
                .uri("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379))
                .nodeId(coldNodeId)
                .build();
        try {
            var latch    = new CountDownLatch(1);
            var received = new AtomicReference<Message>();
            coldFactory.messageTransport().subscribe(
                    new TransportEndpoint("redis", coldNodeId, Map.of()), msg -> {
                        received.set(msg);
                        latch.countDown();
                        return CompletableFuture.completedFuture(null);
                    });

            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("the group is created at 0, so the backlog addressed to this node arrives")
                    .isTrue();
            assertThat(received.get().content()).isEqualTo("waiting");
        } finally {
            coldFactory.close();
        }
    }

    @Test
    @DisplayName("send to a different node writes to that node's stream without local delivery")
    void send_toDifferentNode_writesToRemoteStream() {
        // No subscriber on "other-node" stream — the future should still complete normally
        // (fire-and-forget to the remote stream; delivery is the remote node's responsibility)
        var dest   = new TransportEndpoint("redis", "other-node", Map.of());
        var msg    = Message.builder().receiverId("remote-agent").content("remote-data").build();
        var future = transport.send(dest, msg);

        assertThat(future).succeedsWithin(2, TimeUnit.SECONDS);
    }
}
