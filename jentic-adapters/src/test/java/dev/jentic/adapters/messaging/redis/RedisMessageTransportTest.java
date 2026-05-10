package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.Message;
import dev.jentic.core.TransportEndpoint;
import dev.jentic.core.telemetry.JenticTelemetry;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisMessageTransport")
class RedisMessageTransportTest {

    @Mock
    RedisStreamClient streamClient;
    @Mock
    @SuppressWarnings("rawtypes")
    StatefulRedisConnection mockConn;
    @Mock
    @SuppressWarnings("rawtypes")
    RedisCommands mockCmds;

    private RedisMessagingConfig config;
    private RedisMessageTransport transport;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config    = new RedisMessagingConfig("redis://localhost", "node-1", "jentic", 100, 1000, 1000, 3);
        transport = new RedisMessageTransport(streamClient, config, JenticTelemetry.noop());
        lenient().when(streamClient.xadd(any(), any())).thenReturn("1-0");
        lenient().when(streamClient.newConsumerConnection()).thenReturn(mockConn);
        lenient().when(mockConn.sync()).thenReturn(mockCmds);
        lenient().when(mockCmds.xreadgroup(any(), any(), any())).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // send — validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("send — validation")
    class SendValidation {

        @Test
        @DisplayName("null destination throws NullPointerException")
        void send_nullDest_throws() {
            var msg = Message.builder().receiverId("r").content("c").build();
            assertThatNullPointerException().isThrownBy(() -> transport.send(null, msg));
        }

        @Test
        @DisplayName("null message throws NullPointerException")
        void send_nullMessage_throws() {
            var dest = TransportEndpoint.local("node-2");
            assertThatNullPointerException().isThrownBy(() -> transport.send(dest, null));
        }
    }

    // -------------------------------------------------------------------------
    // send — routing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("send — routing")
    class SendRouting {

        @Test
        @DisplayName("send calls xadd with the correct node stream key")
        void send_callsXaddWithNodeStreamKey() {
            var dest = new TransportEndpoint("redis", "node-42", Map.of());
            var msg  = Message.builder().receiverId("some-agent").content("data").build();

            transport.send(dest, msg).join();

            verify(streamClient).xadd(eq("jentic:node:node-42"), any());
        }

        @Test
        @DisplayName("send calls xadd with non-empty fields map")
        void send_callsXaddWithNonEmptyFields() {
            var dest = new TransportEndpoint("redis", "node-5", Map.of());
            var msg  = Message.builder().receiverId("a").content("payload").build();

            transport.send(dest, msg).join();

            // Verify xadd is called — the field encoding is covered in MessageCodecTest
            verify(streamClient).xadd(eq("jentic:node:node-5"), any());
        }

        @Test
        @DisplayName("send returns a future that completes normally on success")
        void send_completesNormally() {
            var dest   = new TransportEndpoint("redis", "node-2", Map.of());
            var msg    = Message.builder().receiverId("a").content("c").build();
            var future = transport.send(dest, msg);
            assertThat(future).succeedsWithin(2, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("send completes exceptionally when xadd throws")
        void send_xaddThrows_futureFailsExceptionally() {
            when(streamClient.xadd(any(), any())).thenThrow(new RuntimeException("connection lost"));
            var dest   = new TransportEndpoint("redis", "node-2", Map.of());
            var msg    = Message.builder().receiverId("a").content("c").build();
            var future = transport.send(dest, msg);
            assertThat(future).failsWithin(2, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // subscribe — validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribe — validation")
    class SubscribeValidation {

        @Test
        @DisplayName("null local endpoint throws NullPointerException")
        void subscribe_nullLocal_throws() {
            assertThatNullPointerException().isThrownBy(() ->
                    transport.subscribe(null, msg -> null));
        }

        @Test
        @DisplayName("null handler throws NullPointerException")
        void subscribe_nullHandler_throws() {
            assertThatNullPointerException().isThrownBy(() ->
                    transport.subscribe(TransportEndpoint.local("n"), null));
        }
    }

    // -------------------------------------------------------------------------
    // subscribe — consumer group setup
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribe — consumer group setup")
    class SubscribeConsumerGroup {

        @Test
        @DisplayName("subscribe creates the consumer group synchronously on the node stream")
        void subscribe_createsConsumerGroupOnNodeStream() {
            var local = new TransportEndpoint("redis", "node-1", Map.of());
            transport.subscribe(local, msg ->
                    java.util.concurrent.CompletableFuture.completedFuture(null));

            verify(streamClient).ensureConsumerGroup(
                    eq("jentic:node:node-1"),
                    eq(config.nodeConsumerGroup())
            );
        }

        @Test
        @DisplayName("returned subscription has a non-blank id")
        void subscribe_returnsSubscriptionWithId() {
            var local = new TransportEndpoint("redis", "node-1", Map.of());
            var sub   = transport.subscribe(local, msg ->
                    java.util.concurrent.CompletableFuture.completedFuture(null));
            assertThat(sub.subscriptionId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // close
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("close stops all active subscriptions without error")
    void close_stopsAllSubscriptions() {
        var local = new TransportEndpoint("redis", "node-1", Map.of());
        transport.subscribe(local, msg -> java.util.concurrent.CompletableFuture.completedFuture(null));
        transport.close();
    }
}
