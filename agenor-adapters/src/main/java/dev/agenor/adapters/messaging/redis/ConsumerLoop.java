package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.MessageHandler;
import dev.agenor.core.deadletter.DeadLetter;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.SpanStatus;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
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
 * via the injected {@link AgenorTelemetry}. The span carries:
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
    private final AgenorTelemetry telemetry;
    private final RedisDeadLetterQueue deadLetters;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loopThread;

    // Local delivery-attempt tracking (stream entry ID → attempt count).
    // A process restart resets this map; in that case Redis PEL retains the pending entries
    // and they are redelivered naturally.
    private final Map<String, AtomicInteger> deliveryAttempts = new ConcurrentHashMap<>();

    // Why the most recent attempt failed, per entry. Read once when the entry is exhausted:
    // without it a dead letter can only say that delivery failed, not what failed.
    private final Map<String, String> lastFailure = new ConcurrentHashMap<>();

    ConsumerLoop(String streamKey, String consumerGroup, String consumerName,
                 MessageHandler handler, RedisStreamClient client, RedisMessagingConfig config) {
        this(streamKey, consumerGroup, consumerName, handler, client, config, AgenorTelemetry.noop());
    }

    ConsumerLoop(String streamKey, String consumerGroup, String consumerName,
                 MessageHandler handler, RedisStreamClient client, RedisMessagingConfig config,
                 AgenorTelemetry telemetry) {
        this(streamKey, consumerGroup, consumerName, handler, client, config, telemetry,
                new RedisDeadLetterQueue(client, config));
    }

    ConsumerLoop(String streamKey, String consumerGroup, String consumerName,
                 MessageHandler handler, RedisStreamClient client, RedisMessagingConfig config,
                 AgenorTelemetry telemetry, RedisDeadLetterQueue deadLetters) {
        this.streamKey     = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumer      = Consumer.from(consumerGroup, consumerName);
        this.handler       = handler;
        this.client        = client;
        this.config        = config;
        this.telemetry     = telemetry != null ? telemetry : AgenorTelemetry.noop();
        this.deadLetters   = deadLetters;
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

        long lastClaimAt = System.currentTimeMillis();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                var messages = cmds.xreadgroup(consumer, readArgs, offset);
                if (messages != null) {
                    for (var m : messages) processMessage(m);
                }

                // XREADGROUP with lastConsumed only ever returns entries nobody has read yet.
                // Anything left unacknowledged sits in the Pending Entries List and would stay
                // there forever without this pass, which is what made redelivery and the DLQ
                // unreachable no matter what a handler did.
                long now = System.currentTimeMillis();
                if (now - lastClaimAt >= config.pendingEntriesTimeoutMs()) {
                    lastClaimAt = now;
                    reclaimPending(cmds);
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

    /**
     * Reclaims entries this group left unacknowledged for longer than
     * {@code pendingEntriesTimeoutMs} and puts them back through {@link #processMessage}, which
     * is where the delivery-attempt count and the dead-letter hop live.
     *
     * <p>Reclaiming from {@code 0-0} each pass covers entries abandoned by a consumer that died
     * as well as ones this consumer failed to handle, so a restarted node picks up its own
     * pending work.
     */
    private void reclaimPending(io.lettuce.core.api.sync.RedisCommands<String, String> cmds) {
        try {
            var args = new XAutoClaimArgs<String>()
                    .consumer(consumer)
                    .minIdleTime(Duration.ofMillis(config.pendingEntriesTimeoutMs()))
                    .startId("0-0")
                    .count(10);
            var claimed = cmds.xautoclaim(streamKey, args);
            if (claimed == null || claimed.getMessages().isEmpty()) {
                return;
            }
            log.debug("Reclaimed {} pending entr(ies) on '{}' idle longer than {}ms",
                    claimed.getMessages().size(), streamKey, config.pendingEntriesTimeoutMs());
            for (var m : claimed.getMessages()) {
                processMessage(m);
            }
        } catch (Exception e) {
            log.warn("Pending-entry reclaim failed for '{}': {}", streamKey, e.getMessage());
        }
    }

    /**
     * Sends an exhausted entry to the dead-letter queue and acknowledges it off the source
     * stream, in that order: acknowledging first would drop the entry if the write failed.
     *
     * <p>The reason recorded is the failure of the <em>last</em> attempt. An entry that could
     * not even be decoded has no message to carry, so it is acknowledged with an error rather
     * than dead-lettered — there is nothing to reconstruct on the other side.
     */
    private void deadLetter(StreamMessage<String, String> streamMsg, String entryId) {
        var failure = lastFailure.remove(entryId);
        var reason  = failure != null
                ? failure
                : "delivery failed " + config.maxDeliveryAttempts() + " time(s)";
        try {
            var msg = MessageCodec.decode(streamMsg.getBody());
            deadLetters.record(streamKey, entryId,
                    new DeadLetter(msg, reason, msg.receiverId(),
                            config.maxDeliveryAttempts(), Instant.now()));
        } catch (Exception e) {
            log.error("Stream entry {} on '{}' could not be decoded and cannot be dead-lettered: {}",
                    entryId, streamKey, e.getMessage(), e);
        }
        client.xack(streamKey, consumerGroup, entryId);
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
            deadLetter(streamMsg, entryId);
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
            lastFailure.remove(entryId);
        } catch (Exception e) {
            var cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            var detail = cause.getMessage();
            lastFailure.put(entryId, cause.getClass().getSimpleName()
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
            log.warn("Handler failed for entry {} on '{}' (attempt {}/{}): {}",
                    entryId, streamKey, attempts, config.maxDeliveryAttempts(), e.getMessage());
            // No XACK — entry stays in PEL and will be redelivered after pendingEntriesTimeoutMs
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
