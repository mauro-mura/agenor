package dev.agenor.adapters.messaging.redis;

import com.github.dockerjava.api.command.InspectContainerResponse;
import dev.agenor.core.Message;
import dev.agenor.core.TransportEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A broker replaced at a new address, under the same name, as a recreated pod or a podman
 * container is. Lettuce's default resolver caches the address it looked up, so a reconnect went on
 * trying the old one, for good as far as anyone measured, while the name already resolved to the
 * new one.
 *
 * <p>The client runs in a container of its own because only there does the broker's name go through
 * DNS. Run from the test JVM, a name that resolves at all would resolve to the same mapped port.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@EnabledIfDockerAvailable
@DisplayName("Redis client after the broker moved to a new address (Valkey)")
class RedisBrokerNewAddressIT {

    private static final String BROKER_NAME = "broker";
    /** The JDK caches a name for 30 s, and Lettuce's reconnect backoff reaches 30 s on top of it. */
    private static final long AFTER_RETURN_SECONDS = 120;

    private static GenericContainer<?> broker(Network network) {
        return new GenericContainer<>("valkey/valkey:8")
                .withCommand("valkey-server", "--save", "", "--appendonly", "no")
                .withNetwork(network)
                .withNetworkAliases(BROKER_NAME)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
    }

    private static String address(GenericContainer<?> container) {
        InspectContainerResponse info = container.getContainerInfo();
        return info.getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
    }

    @Test
    @DisplayName("a node delivers again once its broker is back at a new address")
    void deliversAgain_atNewAddress() throws Exception {
        try (var network = Network.newNetwork();
             var first = broker(network);
             var follower = follower(network);
             // Takes the address the first broker leaves, so the second one cannot get it back.
             var placeholder = new GenericContainer<>("valkey/valkey:8").withNetwork(network);
             var second = broker(network)) {
            first.start();
            follower.start();
            String before = address(first);

            first.stop();
            placeholder.start();
            second.start();
            assertThat(address(second)).as("the broker really moved").isNotEqualTo(before);

            int seen = follower.getLogs().length();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AFTER_RETURN_SECONDS);
            boolean delivered = false;
            while (!delivered && System.nanoTime() < deadline) {
                Thread.sleep(1_000);
                delivered = follower.getLogs().substring(seen).contains(Follower.DELIVERED);
            }

            assertThat(delivered)
                    .as("delivered after the move; the client's log: %s", tail(follower.getLogs()))
                    .isTrue();
        }
    }

    private static String tail(String logs) {
        return logs.substring(Math.max(0, logs.length() - 2_000));
    }

    /** A container running {@link Follower} with this test's own classpath. */
    private static GenericContainer<?> follower(Network network) {
        var container = new GenericContainer<>("eclipse-temurin:21-jre").withNetwork(network);
        var classpath = new ArrayList<String>();
        classpath.add("/cp/*");
        int index = 0;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            var path = new File(entry).toPath();
            if (Files.isDirectory(path)) {
                String target = "/cp/dir" + index++;
                classpath.add(target);
                container.withFileSystemBind(entry, target, BindMode.READ_ONLY);
            } else if (Files.isRegularFile(path)) {
                container.withFileSystemBind(entry, "/cp/" + index++ + ".jar", BindMode.READ_ONLY);
            }
        }
        return container
                .withCommand("java", "-cp", String.join(":", classpath), Follower.class.getName(),
                        "redis://" + BROKER_NAME + ":6379")
                .waitingFor(Wait.forLogMessage(".*" + Follower.DELIVERED + ".*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
    }

    /** A node that keeps sending to itself, and says so each time a message comes back. */
    public static final class Follower {

        static final String DELIVERED = "delivered-to-self";

        public static void main(String[] args) throws Exception {
            var factory = RedisMessagingFactory.builder().uri(args[0]).build();
            var transport = factory.messageTransport();
            var endpoint = new TransportEndpoint("redis", factory.config().nodeId(), Map.of());
            transport.subscribe(endpoint, message -> {
                System.out.println(DELIVERED);
                return CompletableFuture.completedFuture(null);
            });
            while (true) {
                if (factory.isBrokerReachable()) {
                    try {
                        transport.send(endpoint,
                                        Message.builder().receiverId("agent").content("x").build())
                                .get(5, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException
                             | java.util.concurrent.TimeoutException e) {
                        System.out.println("send failed: " + e);
                    }
                }
                Thread.sleep(1_000);
            }
        }
    }
}
