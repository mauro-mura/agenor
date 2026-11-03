package dev.agenor.adapters.messaging.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisMessagingFactory#isBrokerReachable()} answers a health check, so what it has to get
 * right is the time it takes as much as the answer. Lettuce's own timeout is a minute, and it
 * queues a command for as long as it is disconnected.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Redis broker reachability (Valkey)")
class RedisBrokerReachabilityIT {

    /** The bound the method promises, with room for a loaded machine. */
    private static final long ONE_CHECK_MS = 3_000;
    /** Lettuce's reconnect backoff reaches 30 s, and a failed attempt costs one more round of it. */
    private static final long AFTER_RESTART_SECONDS = 120;

    private static GenericContainer<?> valkey() {
        return new GenericContainer<>("valkey/valkey:8")
                // An absolute path, or DEBUG RESTART looks for the binary in the working directory.
                .withCommand("/usr/local/bin/valkey-server", "--enable-debug-command", "yes",
                        "--save", "", "--appendonly", "no")
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
    }

    private static RedisMessagingFactory factoryFor(GenericContainer<?> valkey) {
        return RedisMessagingFactory.builder()
                .uri("redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379))
                .build();
    }

    @Test
    @DisplayName("a broker that has gone away reads as unreachable, on every check and promptly")
    void unreachable_whenBrokerGoesAway() {
        try (var valkey = valkey()) {
            valkey.start();
            try (var factory = factoryFor(valkey)) {
                assertThat(factory.isBrokerReachable()).isTrue();

                valkey.stop();

                for (int check = 0; check < 5; check++) {
                    long start = System.nanoTime();
                    boolean reachable = factory.isBrokerReachable();
                    assertThat(reachable).as("check %d", check).isFalse();
                    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                            .as("check %d took", check).isLessThan(ONE_CHECK_MS);
                }
            }
        }
    }

    @Test
    @DisplayName("a broker that restarts reads as reachable again once the client has reconnected")
    void reachableAgain_afterRestart() throws Exception {
        try (var valkey = valkey()) {
            valkey.start();
            try (var factory = factoryFor(valkey)) {
                assertThat(factory.isBrokerReachable()).isTrue();

                String before = runId(valkey);
                valkey.execInContainer("valkey-cli", "debug", "restart");
                assertThat(runId(valkey)).as("the server really restarted").isNotEqualTo(before);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AFTER_RESTART_SECONDS);
                boolean reachable = false;
                while (!reachable && System.nanoTime() < deadline) {
                    reachable = factory.isBrokerReachable();
                    Thread.sleep(250);
                }
                assertThat(reachable).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a broker that stops answering on an open connection reads as unreachable within the bound")
    void unreachable_whenBrokerStopsAnswering() throws Exception {
        try (var valkey = valkey()) {
            valkey.start();
            try (var factory = factoryFor(valkey)) {
                assertThat(factory.isBrokerReachable()).isTrue();

                var docker = valkey.getDockerClient();
                docker.pauseContainerCmd(valkey.getContainerId()).exec();
                try {
                    long start = System.nanoTime();
                    assertThat(factory.isBrokerReachable()).isFalse();
                    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                            .as("the check waited on the broker, and then gave up")
                            .isBetween(900L, ONE_CHECK_MS);
                } finally {
                    docker.unpauseContainerCmd(valkey.getContainerId()).exec();
                }

                assertThat(factory.isBrokerReachable()).as("after the pause").isTrue();
            }
        }
    }

    private static String runId(GenericContainer<?> valkey) throws Exception {
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
}
