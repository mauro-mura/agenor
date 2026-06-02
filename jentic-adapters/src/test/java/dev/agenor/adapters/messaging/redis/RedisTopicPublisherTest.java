package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import dev.agenor.core.telemetry.JenticTelemetry;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisTopicPublisher")
class RedisTopicPublisherTest {

    @Mock
    RedisStreamClient streamClient;
    @Mock
    @SuppressWarnings("rawtypes")
    StatefulRedisConnection mockConn;
    @Mock
    @SuppressWarnings("rawtypes")
    RedisCommands mockCmds;

    private RedisMessagingConfig config;
    private RedisTopicPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config    = new RedisMessagingConfig("redis://localhost", "node-1", "agenor", 100, 1000, 1000, 3);
        publisher = new RedisTopicPublisher(streamClient, config, JenticTelemetry.noop());
        // lenient: not all tests call publish() or start a consumer loop
        lenient().when(streamClient.xadd(any(), any())).thenReturn("1-0");
        lenient().when(streamClient.newConsumerConnection()).thenReturn(mockConn);
        lenient().when(mockConn.sync()).thenReturn(mockCmds);
        lenient().when(mockCmds.xreadgroup(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        publisher.close();
    }

    // -------------------------------------------------------------------------
    // publish — validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("publish — validation")
    class PublishValidation {

        @Test
        @DisplayName("null message throws NullPointerException")
        void publish_nullMessage_throws() {
            assertThatNullPointerException().isThrownBy(() -> publisher.publish(null));
        }

        @Test
        @DisplayName("message with null topic throws IllegalArgumentException")
        void publish_nullTopic_throws() {
            var msg = Message.builder().content("x").build();
            assertThatIllegalArgumentException().isThrownBy(() -> publisher.publish(msg));
        }

        @Test
        @DisplayName("message with blank topic throws IllegalArgumentException")
        void publish_blankTopic_throws() {
            var msg = Message.builder().topic("  ").content("x").build();
            assertThatIllegalArgumentException().isThrownBy(() -> publisher.publish(msg));
        }
    }

    // -------------------------------------------------------------------------
    // publish — routing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("publish — routing")
    class PublishRouting {

        @Test
        @DisplayName("publish calls xadd with the correct topic stream key")
        void publish_callsXaddWithTopicStreamKey() {
            var msg = Message.builder().topic("orders.created").content("data").build();

            publisher.publish(msg).join();

            verify(streamClient).xadd(eq("agenor:topic:orders.created"), any());
        }

        @Test
        @DisplayName("publish calls xadd with the correct stream key for the topic")
        void publish_callsXaddWithTopicStreamKeyForEvents() {
            var msg = Message.builder().topic("events").content("payload").build();

            publisher.publish(msg).join();

            // Field encoding correctness is covered in MessageCodecTest
            verify(streamClient).xadd(eq("agenor:topic:events"), any());
        }

        @Test
        @DisplayName("publish returns a future that completes normally on success")
        void publish_completesNormally() {
            var msg    = Message.builder().topic("t").content("c").build();
            var future = publisher.publish(msg);
            assertThat(future).succeedsWithin(2, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("publish completes exceptionally when xadd throws")
        void publish_xaddThrows_futureFailsExceptionally() {
            when(streamClient.xadd(any(), any())).thenThrow(new RuntimeException("Redis down"));
            var msg    = Message.builder().topic("t").content("c").build();
            var future = publisher.publish(msg);
            assertThat(future).failsWithin(2, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // subscribeTopic — validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeTopic — validation")
    class SubscribeValidation {

        @Test
        @DisplayName("null topic throws NullPointerException")
        void subscribeTopic_nullTopic_throws() {
            assertThatNullPointerException().isThrownBy(() ->
                    publisher.subscribeTopic(null, msg -> null));
        }

        @Test
        @DisplayName("blank topic throws IllegalArgumentException")
        void subscribeTopic_blankTopic_throws() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    publisher.subscribeTopic("", msg -> null));
        }

        @Test
        @DisplayName("null handler throws NullPointerException")
        void subscribeTopic_nullHandler_throws() {
            assertThatNullPointerException().isThrownBy(() ->
                    publisher.subscribeTopic("t", null));
        }
    }

    // -------------------------------------------------------------------------
    // subscribeTopic — consumer group setup
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeTopic — consumer group setup")
    class SubscribeConsumerGroup {

        @Test
        @DisplayName("subscribeTopic creates the consumer group synchronously on the topic stream")
        void subscribeTopic_createsConsumerGroupOnTopicStream() {
            publisher.subscribeTopic("payments", msg ->
                    java.util.concurrent.CompletableFuture.completedFuture(null));

            verify(streamClient).ensureConsumerGroup(
                    eq("agenor:topic:payments"),
                    any(String.class)
            );
        }

        @Test
        @DisplayName("returned subscription has a non-blank id")
        void subscribeTopic_returnsSubscriptionWithId() {
            var sub = publisher.subscribeTopic("t", msg ->
                    java.util.concurrent.CompletableFuture.completedFuture(null));
            assertThat(sub.subscriptionId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // close
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("close stops all active consumer loops without error")
    void close_stopsAllLoops() {
        publisher.subscribeTopic("a", msg -> java.util.concurrent.CompletableFuture.completedFuture(null));
        publisher.subscribeTopic("b", msg -> java.util.concurrent.CompletableFuture.completedFuture(null));
        publisher.close(); // must not throw
    }
}
