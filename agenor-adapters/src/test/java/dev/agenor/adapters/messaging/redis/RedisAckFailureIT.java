package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A handler that succeeded runs once, even when the broker does not answer its {@code XACK}.
 *
 * <p>The handler itself cuts the client's connections and freezes Valkey on its way out, so the
 * ack that follows it is lost: a network partition that outlasts the command timeout. A frozen
 * broker on a live connection does not lose it — the ack already written runs when the broker
 * comes back. The {@code ?timeout=2s} in the URI is Lettuce's command timeout, otherwise a
 * minute, which is also how long a partition has to last in production for this to happen.
 * Until 0.41.0 the failed ack read as a failed handler: the entry was reclaimed after
 * {@code pendingEntriesTimeoutMs} and the handler ran a second time.
 *
 * <p>{@link ConsumerLoopAckFailureTest} pins the same rule against a mock; this is the broker
 * actually going silent, with the reconnection and the {@code XAUTOCLAIM} that follow it.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Redis — a failed XACK after a successful handler (Valkey)")
class RedisAckFailureIT {

    private static final String TOPIC = "ack.failure";
    private static final long PENDING_MS = 3_000;

    @Test
    @DisplayName("the handler runs once, and the entry is acknowledged once the broker answers again")
    void aHandledMessageWhoseAckTimedOutIsNotHandledAgain() throws Exception {
        try (var valkey = new GenericContainer<>("valkey/valkey:8")
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60))) {
            valkey.start();
            var docker = valkey.getDockerClient();
            var uri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379) + "?timeout=2s";

            try (var factory = RedisMessagingFactory.builder()
                    .uri(uri)
                    .readBlockTimeoutMs(500)
                    .pendingEntriesTimeoutMs(PENDING_MS)
                    .build()) {
                var runs = new AtomicInteger();
                factory.topicPublisher().subscribeTopic(TOPIC, msg -> {
                    if (runs.incrementAndGet() == 1) {
                        // Cut the client's connections, then freeze the broker for longer than
                        // the command timeout, between this handler and its XACK. A pause alone
                        // is not enough: the ack is already on the socket and runs on unpause.
                        // With the connection gone it waits in Lettuce's reconnect buffer, times
                        // out, and is dropped — the ack is lost, as in a network partition.
                        try {
                            valkey.execInContainer("valkey-cli", "CLIENT", "KILL", "TYPE", "normal");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        docker.pauseContainerCmd(valkey.getContainerId()).exec();
                        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() ->
                                docker.unpauseContainerCmd(valkey.getContainerId()).exec());
                    }
                    return CompletableFuture.completedFuture(null);
                });

                factory.topicPublisher()
                        .publish(Message.builder().topic(TOPIC).content("work").build()).join();

                var streamKey = factory.config().topicStreamKey(TOPIC);
                awaitNothingPending(valkey, streamKey, runs);

                // Past one more reclaim interval, nothing has run it again either.
                Thread.sleep(PENDING_MS * 2);
                assertThat(runs).as("handler runs").hasValue(1);
            }
        }
    }

    /** Waits until the handler has run and the stream's consumer group has no entry pending. */
    private static void awaitNothingPending(GenericContainer<?> valkey, String streamKey,
                                            AtomicInteger runs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String last = "";
        while (System.nanoTime() < deadline) {
            if (runs.get() > 0) {
                try {
                    last = valkey.execInContainer("valkey-cli", "XINFO", "GROUPS", streamKey)
                            .getStdout();
                } catch (Exception e) {
                    last = "paused: " + e.getMessage();   // exec into a paused container fails
                    Thread.sleep(250);
                    continue;
                }
                var lines = last.lines().map(String::trim).toList();
                int i = lines.indexOf("pending");
                if (i >= 0 && i + 1 < lines.size() && lines.get(i + 1).equals("0")) {
                    return;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("The entry was never acknowledged (handler runs: " + runs.get()
                + ") — XINFO GROUPS: " + last);
    }
}
