package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A handler that succeeded and an {@code XACK} that failed are two different outcomes.
 *
 * <p>The ack used to sit in the same {@code try} as the handler, so a broker that did not answer
 * the ack — a network blip, a frozen Valkey — read as a failed handler. The entry stayed pending,
 * was reclaimed after {@code pendingEntriesTimeoutMs}, and the handler ran a second time on a
 * message it had already handled; past {@code maxDeliveryAttempts} such a message was even
 * dead-lettered. Every handler on Redis had to be idempotent against that, including a dialogue
 * handler, which an application then guarded with its own seen-set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsumerLoop — a failed XACK after a successful handler")
class ConsumerLoopAckFailureTest {

    private static final String STREAM = "agenor:node:n";
    private static final String GROUP  = "cg";

    @Mock
    private RedisStreamClient streamClient;

    private RedisMessagingConfig config;

    @BeforeEach
    void setUp() {
        config = new RedisMessagingConfig("redis://localhost", "node-1", "agenor",
                100, 1000, 30_000, 3);
    }

    private ConsumerLoop loop(dev.agenor.core.MessageHandler handler) {
        return new ConsumerLoop(STREAM, GROUP, "consumer-1", handler, () -> { },
                streamClient, config);
    }

    private static StreamMessage<String, String> entry(String entryId) {
        var body = MessageCodec.encode(Message.builder()
                .id("msg-" + entryId).senderId("sender").receiverId("receiver")
                .content("payload").build());
        return new StreamMessage<>(STREAM, entryId, body);
    }

    private static RedisCommandTimeoutException brokerSilent() {
        return new RedisCommandTimeoutException("Command timed out after 1 minute(s)");
    }

    @Test
    @DisplayName("the redelivery is acknowledged without running the handler again")
    void redeliveryAfterAFailedAckDoesNotRunTheHandlerAgain() {
        var runs = new AtomicInteger();
        var loop = loop(msg -> {
            runs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        doThrow(brokerSilent()).doNothing().when(streamClient).xack(STREAM, GROUP, "1-0");

        loop.processMessage(entry("1-0"));   // handled; the ack is lost
        loop.processMessage(entry("1-0"));   // reclaimed from the PEL

        assertThat(runs).hasValue(1);
        verify(streamClient, times(2)).xack(STREAM, GROUP, "1-0");
    }

    @Test
    @DisplayName("a handled message is not dead-lettered however long the ack keeps failing")
    void aHandledMessageIsNeverDeadLetteredForAnAckThatKeepsFailing() {
        var runs = new AtomicInteger();
        var loop = loop(msg -> {
            runs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        doThrow(brokerSilent()).doThrow(brokerSilent()).doThrow(brokerSilent())
                .doThrow(brokerSilent()).doNothing()
                .when(streamClient).xack(STREAM, GROUP, "1-0");

        // One more redelivery than maxDeliveryAttempts allows
        for (int i = 0; i < config.maxDeliveryAttempts() + 2; i++) {
            loop.processMessage(entry("1-0"));
        }

        assertThat(runs).hasValue(1);
        verify(streamClient, never()).xaddDlq(anyString(), any());
    }

    @Test
    @DisplayName("a failed handler still runs again on redelivery")
    void aFailedHandlerStillRunsAgain() {
        var runs = new AtomicInteger();
        var loop = loop(msg -> runs.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("first attempt fails"))
                : CompletableFuture.completedFuture(null));
        doNothing().when(streamClient).xack(STREAM, GROUP, "1-0");

        loop.processMessage(entry("1-0"));
        loop.processMessage(entry("1-0"));

        assertThat(runs).hasValue(2);
        verify(streamClient, times(1)).xack(STREAM, GROUP, "1-0");
    }
}
