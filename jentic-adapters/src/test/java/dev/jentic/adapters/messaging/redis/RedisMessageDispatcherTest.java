package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.AgentEndpoint;
import dev.jentic.core.Message;
import dev.jentic.core.TransportEndpoint;
import dev.jentic.core.directory.AgentResolver;
import dev.jentic.core.exceptions.AgentNotFoundException;
import dev.jentic.core.messaging.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisMessageDispatcher")
class RedisMessageDispatcherTest {

    @Mock RedisTopicPublisher  topicPublisher;
    @Mock RedisMessageTransport messageTransport;
    @Mock AgentResolver         agentResolver;

    private RedisMessagingConfig config;
    private RedisMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        config     = new RedisMessagingConfig("redis://localhost", "node-1", "jentic", 100, 1000, 1000, 3);
        dispatcher = new RedisMessageDispatcher(topicPublisher, messageTransport, () -> agentResolver, config);

        // Default: transport subscribe is a no-op (starts consumer loop)
        lenient().when(messageTransport.subscribe(any(), any()))
                .thenReturn(Subscription.of("node-sub", () -> {}));
    }

    // -------------------------------------------------------------------------
    // publish — delegates to topicPublisher
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("delegates to RedisTopicPublisher")
        void publish_delegatesToTopicPublisher() {
            var msg = Message.builder().topic("orders").content("data").build();
            when(topicPublisher.publish(msg)).thenReturn(CompletableFuture.completedFuture(null));

            dispatcher.publish(msg).join();

            verify(topicPublisher).publish(msg);
        }

        @Test
        @DisplayName("null message propagates to topicPublisher (validation is its responsibility)")
        void publish_nullPropagates() {
            when(topicPublisher.publish(null)).thenThrow(new NullPointerException("msg"));

            assertThatNullPointerException().isThrownBy(() -> dispatcher.publish(null));
        }
    }

    // -------------------------------------------------------------------------
    // subscribeTopic — delegates to topicPublisher
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeTopic")
    class SubscribeTopic {

        @Test
        @DisplayName("delegates to RedisTopicPublisher")
        void subscribeTopic_delegatesToPublisher() {
            var sub = Subscription.of("sub-1", () -> {});
            when(topicPublisher.subscribeTopic(eq("orders"), any())).thenReturn(sub);

            var result = dispatcher.subscribeTopic("orders", msg -> CompletableFuture.completedFuture(null));

            assertThat(result).isSameAs(sub);
            verify(topicPublisher).subscribeTopic(eq("orders"), any());
        }
    }

    // -------------------------------------------------------------------------
    // sendTo — validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("sendTo — validation")
    class SendToValidation {

        @Test
        @DisplayName("null message throws NullPointerException")
        void sendTo_nullMsg_throws() {
            assertThatNullPointerException().isThrownBy(() -> dispatcher.sendTo(null));
        }

        @Test
        @DisplayName("null receiverId throws IllegalArgumentException")
        void sendTo_nullReceiverId_throws() {
            var msg = Message.builder().senderId("s").content("c").build();
            assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.sendTo(msg));
        }

        @Test
        @DisplayName("blank receiverId throws IllegalArgumentException")
        void sendTo_blankReceiverId_throws() {
            var msg = Message.builder().receiverId("  ").senderId("s").content("c").build();
            assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.sendTo(msg));
        }
    }

    // -------------------------------------------------------------------------
    // sendTo — local fast-path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("sendTo — local fast-path")
    class SendToLocal {

        @Test
        @DisplayName("delivers directly to local handler when recipient is registered")
        void sendTo_local_deliversDirectly() {
            var received = new AtomicReference<Message>();
            dispatcher.subscribeRecipient("agent-b", msg -> {
                received.set(msg);
                return CompletableFuture.completedFuture(null);
            });

            var direct = Message.builder()
                    .senderId("agent-a").receiverId("agent-b").content("hello").build();
            dispatcher.sendTo(direct).join();

            assertThat(received.get()).isSameAs(direct);
            verify(messageTransport, never()).send(any(), any());
            verify(agentResolver, never()).resolveEndpoint(any());
        }

        @Test
        @DisplayName("does not use AgentResolver for local recipients")
        void sendTo_local_skipsResolver() {
            dispatcher.subscribeRecipient("local-agent",
                    msg -> CompletableFuture.completedFuture(null));

            var msg = Message.builder().receiverId("local-agent").content("x").build();
            dispatcher.sendTo(msg).join();

            verify(agentResolver, never()).resolveEndpoint(any());
        }
    }

    // -------------------------------------------------------------------------
    // sendTo — remote path via AgentResolver
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("sendTo — remote path")
    class SendToRemote {

        @Test
        @DisplayName("resolves endpoint and delegates to messageTransport")
        void sendTo_remote_usesTransport() {
            var endpoint = new AgentEndpoint("node-2", "redis", Map.of());
            when(agentResolver.resolveEndpoint("remote-agent"))
                    .thenReturn(CompletableFuture.completedFuture(Optional.of(endpoint)));
            when(messageTransport.send(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            var msg = Message.builder().receiverId("remote-agent").content("data").build();
            dispatcher.sendTo(msg).join();

            var captor = ArgumentCaptor.forClass(TransportEndpoint.class);
            verify(messageTransport).send(captor.capture(), eq(msg));

            var dest = captor.getValue();
            assertThat(dest.transportType()).isEqualTo("redis");
            assertThat(dest.address()).isEqualTo("node-2");
        }

        @Test
        @DisplayName("fails with AgentNotFoundException when resolver returns empty")
        void sendTo_remote_resolverEmpty_fails() {
            when(agentResolver.resolveEndpoint("unknown"))
                    .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

            var msg = Message.builder().receiverId("unknown").content("x").build();
            var future = dispatcher.sendTo(msg);

            assertThat(future).failsWithin(2, TimeUnit.SECONDS)
                    .withThrowableOfType(Exception.class)
                    .havingCause()
                    .isInstanceOf(AgentNotFoundException.class);
        }

        @Test
        @DisplayName("fails with AgentNotFoundException when no resolver is configured")
        void sendTo_noResolver_fails() {
            var noResolverDispatcher = new RedisMessageDispatcher(
                    topicPublisher, messageTransport, null, config);

            var msg = Message.builder().receiverId("remote").content("x").build();
            var future = noResolverDispatcher.sendTo(msg);

            assertThat(future).failsWithin(2, TimeUnit.SECONDS)
                    .withThrowableOfType(Exception.class)
                    .havingCause()
                    .isInstanceOf(AgentNotFoundException.class);
        }
    }

    // -------------------------------------------------------------------------
    // subscribeRecipient
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeRecipient")
    class SubscribeRecipient {

        @Test
        @DisplayName("null agentId throws NullPointerException")
        void subscribeRecipient_nullId_throws() {
            assertThatNullPointerException().isThrownBy(() ->
                    dispatcher.subscribeRecipient(null, msg -> CompletableFuture.completedFuture(null)));
        }

        @Test
        @DisplayName("blank agentId throws IllegalArgumentException")
        void subscribeRecipient_blankId_throws() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    dispatcher.subscribeRecipient("  ", msg -> CompletableFuture.completedFuture(null)));
        }

        @Test
        @DisplayName("null handler throws NullPointerException")
        void subscribeRecipient_nullHandler_throws() {
            assertThatNullPointerException().isThrownBy(() ->
                    dispatcher.subscribeRecipient("agent-x", null));
        }

        @Test
        @DisplayName("starts the node-stream consumer on first call")
        void subscribeRecipient_startsNodeStream() {
            dispatcher.subscribeRecipient("agent-a", msg -> CompletableFuture.completedFuture(null));

            var captor = ArgumentCaptor.forClass(TransportEndpoint.class);
            verify(messageTransport).subscribe(captor.capture(), any());

            var endpoint = captor.getValue();
            assertThat(endpoint.transportType()).isEqualTo("redis");
            assertThat(endpoint.address()).isEqualTo(config.nodeId());
        }

        @Test
        @DisplayName("starts node-stream consumer only once for multiple registrations")
        void subscribeRecipient_nodeStreamStartedOnce() {
            dispatcher.subscribeRecipient("agent-a", msg -> CompletableFuture.completedFuture(null));
            dispatcher.subscribeRecipient("agent-b", msg -> CompletableFuture.completedFuture(null));

            verify(messageTransport).subscribe(any(), any()); // exactly once
        }

        @Test
        @DisplayName("returned subscription has non-blank id")
        void subscribeRecipient_returnsSubscriptionWithId() {
            var sub = dispatcher.subscribeRecipient("agent-x",
                    msg -> CompletableFuture.completedFuture(null));

            assertThat(sub.subscriptionId()).isNotBlank();
        }

        @Test
        @DisplayName("unsubscribe removes handler — subsequent sendTo fails with AgentNotFoundException")
        void subscribeRecipient_unsubscribe_removesHandler() {
            when(agentResolver.resolveEndpoint("agent-a"))
                    .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

            var sub = dispatcher.subscribeRecipient("agent-a",
                    msg -> CompletableFuture.completedFuture(null));
            sub.unsubscribe();

            var msg = Message.builder().receiverId("agent-a").content("x").build();
            var future = dispatcher.sendTo(msg);

            // After unsubscribe, local path is gone; resolver returns empty → AgentNotFoundException
            assertThat(future).failsWithin(2, TimeUnit.SECONDS)
                    .withThrowableOfType(Exception.class)
                    .havingCause()
                    .isInstanceOf(AgentNotFoundException.class);
        }
    }
}
