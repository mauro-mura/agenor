package dev.agenor.examples.redis;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Redis Messaging Example — two agents communicating via Redis Streams (ADR-021).
 *
 * <p>{@link RedisMessagingFactory#messageDispatcher()} returns a
 * {@link dev.agenor.adapters.messaging.redis.RedisMessageDispatcher} that backs all
 * agent messaging with Redis Streams. Passing it to
 * {@code JenticRuntime.Builder.messageDispatcher(...)} replaces the default in-memory
 * bus without any changes to the runtime or to the agent code.
 *
 * <p><b>What this example demonstrates:</b>
 * <ol>
 *   <li><b>Topic pub/sub over Redis</b>: {@code OrderAgent} publishes order-created
 *       events to the {@code orders.created} stream every 4 seconds;
 *       {@code FulfillmentAgent} subscribes via {@code @AgenorMessageHandler} and
 *       receives each event through a dedicated consumer group (fan-out).</li>
 *   <li><b>Direct reply over Redis</b>: {@code FulfillmentAgent} replies directly to
 *       {@code OrderAgent} via {@code MessageDispatcher.sendTo}. Because both agents
 *       run in the same JVM, the dispatcher uses its local fast-path (no extra Redis
 *       hop). In a multi-JVM setup the dispatcher would resolve the target node via
 *       {@code AgentResolver} and write to its node stream.</li>
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
 * <p>Then run:
 * <pre>
 *   mvn exec:java -pl agenor-examples \
 *       -Dexec.mainClass="dev.agenor.examples.redis.RedisMessagingExample"
 *
 *   # override Redis URI:
 *   REDIS_URI=redis://my-host:6379 mvn exec:java ...
 * </pre>
 *
 * @since 0.21.0
 */
public class RedisMessagingExample {

    private static final Logger log = LoggerFactory.getLogger(RedisMessagingExample.class);

    public static void main(String[] args) throws InterruptedException {

        String redisUri = System.getenv().getOrDefault("REDIS_URI", "redis://localhost:6379");
        log.info("=== Redis Agent Messaging Example ===");
        log.info("Connecting to {}", redisUri);

        try (var factory = RedisMessagingFactory.builder()
                .uri(redisUri)
                .consumerGroupPrefix("agenor-example")
                .build()) {

            JenticRuntime runtime = JenticRuntime.builder()
                    .messageDispatcher(factory.messageDispatcher())
                    .build();

            runtime.registerAgent(new OrderAgent());
            runtime.registerAgent(new FulfillmentAgent());

            runtime.start().join();
            log.info("Runtime started — {} agent(s) running", runtime.getAgents().size());

            Thread.sleep(20_000);

            log.info("Stopping runtime...");
            runtime.stop().join();

        } // factory.close() stops all consumer loops and closes the Lettuce connection

        log.info("=== Example completed ===");
    }

    // -------------------------------------------------------------------------
    // Agents
    // -------------------------------------------------------------------------

    /**
     * Publishes a new order to the {@code orders.created} topic every 4 seconds
     * and logs the ACK that {@code FulfillmentAgent} sends back directly.
     */
    @Agent(value = "order-agent",
                 type = "example",
                 capabilities = {"order-publish"},
                 autoStart = true)
    public static class OrderAgent extends BaseAgent {

        private int orderSeq = 0;

        public OrderAgent() {
            super("order-agent", "Order Agent");
        }

        @Behavior(type = CYCLIC, interval = "4s", autoStart = true)
        public void publishOrder() {
            orderSeq++;
            var order = Message.builder()
                    .topic("orders.created")
                    .senderId(getAgentId())
                    .content("{\"orderId\":\"ORD-" + orderSeq + "\",\"amount\":99.95}")
                    .header("seq", String.valueOf(orderSeq))
                    .build();
            log.info("[OrderAgent] Publishing order #{}", orderSeq);
            getMessageDispatcher().publish(order);
        }

        // BaseAgent.autoSubscribeDirectMessages() registers subscribeRecipient("order-agent", ...)
        // at startup; onDirectMessage() receives the fulfillment ACKs.
        @Override
        protected void onDirectMessage(Message msg) {
            log.info("[OrderAgent] Fulfillment ACK — correlationId={} content={}",
                    msg.correlationId(), msg.content());
        }

        @Override
        protected void onStop() {
            log.info("[OrderAgent] Stopped after {} orders published", orderSeq);
        }
    }

    /**
     * Subscribes to {@code orders.created} and replies directly to the sender.
     */
    @Agent(value = "fulfillment-agent",
                 type = "example",
                 capabilities = {"fulfillment"},
                 autoStart = true)
    public static class FulfillmentAgent extends BaseAgent {

        private int processed = 0;

        public FulfillmentAgent() {
            super("fulfillment-agent", "Fulfillment Agent");
        }

        @AgenorMessageHandler("orders.created")
        public void handleOrder(Message msg) {
            processed++;
            log.info("[FulfillmentAgent] Processing order: {} (seq={})",
                    msg.content(), msg.headers().get("seq"));

            var ack = msg.reply("fulfillment queued by " + getAgentName())
                    .senderId(getAgentId())
                    .build();
            getMessageDispatcher().sendTo(ack);
        }

        @Override
        protected void onStop() {
            log.info("[FulfillmentAgent] Stopped after {} orders processed", processed);
        }
    }
}
