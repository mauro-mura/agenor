package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.MessageHandler;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.SpanStatus;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a virtual-thread-based {@code XREADGROUP BLOCK} loop for a single stream.
 *
 * <p>On handler success the stream entry is acknowledged ({@code XACK}). On handler
 * failure the entry stays in the PEL for redelivery. After {@code maxDeliveryAttempts}
 * failures the entry is moved to the dead-letter stream and acknowledged.
 *
 * <p>Each successfully decoded message dispatch emits a {@code message.receive} span
 * via the injected {@link JenticTelemetry}. The span carries:
 * <ul>
 *   <li>{@code message.id} — message identifier</li>
 *   <li>{@code message.topic} — topic, or empty string for point-to-point messages</li>
 *   <li>{@code agent.sender} — sender agent ID</li>
 *   <li>{@code message.correlation_id} — correlation ID (links receive span to publish span)</li>
 *   <li>{@code transport.type} — always {@code "redis"}</li>
 * </ul>
 * The span status is {@code OK} on handler success and {@code ERROR} when the handler
 * throws; the span is always ended, even when the entry is sent to the DLQ.
 */
final class ConsumerLoop {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLoop.class);

    private final String streamKey;
    private final String consumerGroup;
    private final Consumer<String> consumer;
    private final MessageHandler handler;
    private final RedisStreamClient client;
    private final RedisMessagingConfig config;
    private final JenticTelemetry telemetry;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loopThread;

    // Local delivery-attempt tracking (stream entry ID → attempt count).
    // A process restart resets this map; in that case Redis PEL retains the pending entries
    // and they are redelivered naturally.
    private final Map<String, AtomicInteger> deliveryAttempts = new ConcurrentHashMap<>();

    ConsumerLoop(String streamKey, String consumerGroup, String consumerName,
                 MessageHandler handler, RedisStreamClient client, RedisMessagingConfig config) {
        this(streamKey, consumerGroup, consumerName, handler, client, config, JenticTelemetry.noop());
    }

    ConsumerLoop(String streamKey, String consumerGroup, String consumerName,
                 MessageHandler handler, RedisStreamClient client, RedisMessagingConfig config,
                 JenticTelemetry telemetry) {
        this.streamKey     = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumer      = Consumer.from(consumerGroup, consumerName);
        this.handler       = handler;
        this.client        = client;
        this.config        = config;
        this.telemetry     = telemetry != null ? telemetry : JenticTelemetry.noop();
    }

    /**
     * Creates the consumer group synchronously (in the calling thread), then starts
     * the blocking read loop on a virtual thread. By the time this method returns the
     * group is guaranteed to exist, so messages published afterwards will be captured.
     */
    void start() {
        if (!running.compareAndSet(false, true)) return;
        client.ensureConsumerGroup(streamKey, consumerGroup);
        loopThread = Thread.startVirtualThread(this::run);
    }

    void stop() {
        running.set(false);
        var t = loopThread;
        if (t != null) t.interrupt();
    }

    private void run() {
        var conn = client.newConsumerConnection();
        try {
            loop(conn);
        } finally {
            conn.close();
        }
    }

    @SuppressWarnings("unchecked")
	private void loop(StatefulRedisConnection<String, String> conn) {
        var readArgs = XReadArgs.Builder.count(10).block(Duration.ofMillis(config.readBlockTimeoutMs()));
        var offset   = XReadArgs.StreamOffset.<String>lastConsumed(streamKey);
        var cmds     = conn.sync();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                var messages = cmds.xreadgroup(consumer, readArgs, offset);
                if (messages != null) {
                    for (var m : messages) processMessage(m);
                }
            } catch (Exception e) {
                if (!running.get() || Thread.currentThread().isInterrupted()) break;
                log.warn("Transient error in consumer loop for '{}': {}", streamKey, e.getMessage());
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.debug("Consumer loop stopped for stream '{}'", streamKey);
    }

    // package-private for testing
    void processMessage(StreamMessage<String, String> streamMsg) {
        var entryId  = streamMsg.getId();
        int attempts = deliveryAttempts
                .computeIfAbsent(entryId, k -> new AtomicInteger(0))
                .incrementAndGet();

        if (attempts > config.maxDeliveryAttempts()) {
            log.error("Stream entry {} exceeded {} delivery attempts; moving to DLQ",
                    entryId, config.maxDeliveryAttempts());
            client.moveToDlq(streamKey, consumerGroup, streamMsg);
            deliveryAttempts.remove(entryId);
            return;
        }

        try {
            var msg = MessageCodec.decode(streamMsg.getBody());

            var span = telemetry.spanBuilder("message.receive")
                    .setAttribute("message.id",             orEmpty(msg.id()))
                    .setAttribute("message.topic",          orEmpty(msg.topic()))
                    .setAttribute("agent.sender",           orEmpty(msg.senderId()))
                    .setAttribute("message.correlation_id", orEmpty(msg.correlationId()))
                    .setAttribute("transport.type",         "redis")
                    .startSpan();
            try (var scope = span.makeCurrent()) {
                handler.handle(msg).join();
                span.setStatus(SpanStatus.OK);
            } catch (Exception e) {
                span.recordException(e).setStatus(SpanStatus.ERROR);
                throw e;
            } finally {
                span.end();
            }

            client.xack(streamKey, consumerGroup, entryId);
            deliveryAttempts.remove(entryId);
        } catch (Exception e) {
            log.warn("Handler failed for entry {} on '{}' (attempt {}/{}): {}",
                    entryId, streamKey, attempts, config.maxDeliveryAttempts(), e.getMessage());
            // No XACK — entry stays in PEL and will be redelivered after pendingEntriesTimeoutMs
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}