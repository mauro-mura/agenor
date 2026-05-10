package dev.jentic.examples.redis;

import dev.jentic.adapters.messaging.redis.RedisMessagingFactory;
import dev.jentic.core.Message;
import dev.jentic.core.TransportEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Messaging Example — demonstrates the Redis Streams adapter (ADR-021).
 *
 * <p>Shows both messaging primitives provided by the adapter:
 * <ol>
 *   <li><b>Topic pub/sub</b> via {@code RedisTopicPublisher} — multiple subscribers
 *       on the same topic each receive a copy (fan-out via per-subscription consumer groups).</li>
 *   <li><b>Point-to-point transport</b> via {@code RedisMessageTransport} — messages
 *       addressed to a specific node land in that node's dedicated stream.</li>
 * </ol>
 *
 * <p>Prerequisites — start a Valkey (or Redis-compatible) server:
 * <pre>
 *   docker run -d -p 6379:6379 valkey/valkey:8
 *
 *   # or with docker compose (valkey service defined in compose.yml):
 *   docker compose up valkey
 * </pre>
 *
 * <p>Then run this example:
 * <pre>
 *   mvn exec:java -pl jentic-examples \
 *       -Dexec.mainClass="dev.jentic.examples.redis.RedisMessagingExample"
 *
 *   # override Redis URI:
 *   REDIS_URI=redis://my-host:6379 mvn exec:java ...
 * </pre>
 *
 * <p>For a true multi-node scenario (two JVMs communicating over Redis), run two
 * instances of this example with different node IDs and send point-to-point messages
 * between them by specifying the remote node ID as the {@code TransportEndpoint} address.
 *
 * @since 0.21.0
 */
public class RedisMessagingExample {

    private static final Logger log = LoggerFactory.getLogger(RedisMessagingExample.class);

    public static void main(String[] args) throws InterruptedException {

        String redisUri = System.getenv().getOrDefault("REDIS_URI", "redis://localhost:6379");
        log.info("=== Redis Messaging Example ===");
        log.info("Connecting to {}", redisUri);

        // ------------------------------------------------------------------
        // 1. Build the factory — connects to Redis immediately.
        //    A single factory manages a shared Lettuce connection; both the
        //    topic publisher and the message transport reuse it.
        // ------------------------------------------------------------------
        try (var factory = RedisMessagingFactory.builder()
                .uri(redisUri)
                .consumerGroupPrefix("jentic-example")
                .build()) {

            var publisher  = factory.topicPublisher();
            var transport  = factory.messageTransport();
            var nodeId     = factory.config().nodeId();

            log.info("Node ID: {}", nodeId);

            // ------------------------------------------------------------------
            // 2. Topic pub/sub — "orders.created"
            //    Two independent subscribers; each will receive the message.
            // ------------------------------------------------------------------
            log.info("\n--- Topic pub/sub demo ---");

            var topicLatch   = new CountDownLatch(2);
            var topicCounter = new AtomicInteger(0);

            publisher.subscribeTopic("orders.created", msg -> {
                int n = topicCounter.incrementAndGet();
                log.info("[Subscriber A] received message #{}: topic={} content={}",
                        n, msg.topic(), msg.content());
                topicLatch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            publisher.subscribeTopic("orders.created", msg -> {
                int n = topicCounter.incrementAndGet();
                log.info("[Subscriber B] received message #{}: topic={} content={}",
                        n, msg.topic(), msg.content());
                topicLatch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Brief pause so both consumer loops are running before we publish
            Thread.sleep(200);

            var order = Message.builder()
                    .topic("orders.created")
                    .senderId("checkout-service")
                    .content("{\"orderId\":\"ORD-001\",\"amount\":99.95}")
                    .header("region", "EU")
                    .build();

            log.info("Publishing to topic 'orders.created'...");
            publisher.publish(order).join();

            boolean bothReceived = topicLatch.await(5, TimeUnit.SECONDS);
            if (bothReceived) {
                log.info("Both subscribers received the message — fan-out confirmed.");
            } else {
                log.warn("Timeout waiting for topic delivery (received {}/2)", topicCounter.get());
            }

            // ------------------------------------------------------------------
            // 3. Point-to-point transport — local node
            //    Subscribe on the local node stream, then send a direct message.
            // ------------------------------------------------------------------
            log.info("\n--- Point-to-point transport demo ---");

            var transportLatch  = new CountDownLatch(1);
            var localEndpoint   = new TransportEndpoint("redis", nodeId, Map.of());

            transport.subscribe(localEndpoint, msg -> {
                log.info("[Transport] received direct message: senderId={} content={}",
                        msg.senderId(), msg.content());
                transportLatch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            Thread.sleep(200);

            var directMsg = Message.builder()
                    .senderId("orchestrator")
                    .receiverId("worker-agent")
                    .correlationId("req-42")
                    .content("process-batch-001")
                    .build();

            log.info("Sending point-to-point message to node '{}'...", nodeId);
            transport.send(localEndpoint, directMsg).join();

            boolean directReceived = transportLatch.await(5, TimeUnit.SECONDS);
            if (directReceived) {
                log.info("Direct message delivered — point-to-point confirmed.");
            } else {
                log.warn("Timeout waiting for direct message delivery.");
            }

            // ------------------------------------------------------------------
            // 4. Drain existing messages (brief wait for consumer loop to finish)
            // ------------------------------------------------------------------
            Thread.sleep(300);

        } // factory.close() stops all consumer loops and closes the Lettuce connection

        log.info("\n=== Redis Messaging Example completed ===");
    }
}
