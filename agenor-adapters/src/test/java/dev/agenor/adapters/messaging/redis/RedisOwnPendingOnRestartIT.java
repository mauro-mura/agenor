package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.TransportEndpoint;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node that restarts under the same {@code nodeId} gets back the entries it had read and not
 * acknowledged at once, not after {@code pendingEntriesTimeoutMs}.
 *
 * <p>Such an entry is in the consumer group's PEL under this node's consumer name, so
 * {@code XREADGROUP ... >} never returns it again. Until 0.41.0 only the reclaim pass reached it,
 * and that pass waits for an entry to be idle for {@code pendingEntriesTimeoutMs} (30 s by
 * default) and first runs that long after the loop starts. An application restarting after a
 * crash had to guess how long to wait before acting on state those entries would change.
 *
 * <p>The reclaim timeout here is a minute, so anything delivered within seconds came from
 * reading the node's own pending entries, not from the reclaim.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Redis — a restarted node reads its own pending entries at once (Valkey)")
class RedisOwnPendingOnRestartIT {

    private static final String NODE = "restarting-node";
    private static final String PREFIX = "agenor-own-pending";
    private static final long PENDING_MS = 60_000;

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory factory() {
        return RedisMessagingFactory.builder()
                .uri("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379))
                .nodeId(NODE).consumerGroupPrefix(PREFIX)
                .readBlockTimeoutMs(500).pendingEntriesTimeoutMs(PENDING_MS)
                .build();
    }

    @Test
    @DisplayName("an entry read and not acknowledged before the restart is delivered on start")
    void ownPendingEntryIsDeliveredOnRestart() throws Exception {
        var local = new TransportEndpoint("redis", NODE, Map.of());
        var sent = Message.builder().receiverId("agent").content("left pending").build();

        // The node reads the entry and does not acknowledge it: its handler fails, as one whose
        // process died mid-handling would leave it. Then the node goes away.
        var readOnce = new CompletableFuture<Void>();
        try (var before = factory()) {
            before.messageTransport().subscribe(local, msg -> {
                readOnce.complete(null);
                return CompletableFuture.failedFuture(new IllegalStateException("crash"));
            });
            before.messageTransport().send(local, sent).join();
            readOnce.get(10, TimeUnit.SECONDS);
        }

        // The node comes back under the same nodeId, so the same consumer name.
        var received = new CopyOnWriteArrayList<String>();
        try (var after = factory()) {
            after.messageTransport().subscribe(local, msg -> {
                received.add(msg.id());
                return CompletableFuture.completedFuture(null);
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (received.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }

        assertThat(received).as("entries delivered within 5 s of the restart, with a %d s "
                + "reclaim timeout", PENDING_MS / 1000).containsExactly(sent.id());
    }
}
