package dev.agenor.runtime.messaging;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.exceptions.AgentNotFoundException;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryMessageDispatcher}.
 */
class InMemoryMessageDispatcherTest {

    private InMemoryAgentDirectory directory;
    private InMemoryMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory("test-node");
        dispatcher = new InMemoryMessageDispatcher(directory);
    }

    // -------------------------------------------------------------------------
    // TopicPublisher + TopicSubscriber
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Topic publish/subscribe")
    class TopicPubSub {

        @Test
        @DisplayName("Subscriber receives published message")
        void subscriberReceivesMessage() throws Exception {
            var latch = new CountDownLatch(1);
            var received = new AtomicReference<Message>();

            dispatcher.subscribeTopic("order.created", msg -> {
                received.set(msg);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            var msg = Message.builder().topic("order.created").content("test").build();
            dispatcher.publish(msg).join();

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().topic()).isEqualTo("order.created");
        }

        @Test
        @DisplayName("Multiple subscribers all receive the message")
        void multipleSubscribersAllReceive() throws Exception {
            var counter = new AtomicInteger(0);
            var latch = new CountDownLatch(3);

            MessageHandler handler = msg -> {
                counter.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            };

            dispatcher.subscribeTopic("events", handler);
            dispatcher.subscribeTopic("events", handler);
            dispatcher.subscribeTopic("events", handler);

            var msg = Message.builder().topic("events").content("payload").build();
            dispatcher.publish(msg).join();

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(counter.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("No exception when no subscribers exist for a topic")
        void noSubscribersIsNoOp() {
            var msg = Message.builder().topic("unknown.topic").content("x").build();
            assertThat(dispatcher.publish(msg))
                    .succeedsWithin(2, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("Message for different topic is NOT delivered")
        void differentTopicNotDelivered() throws Exception {
            var counter = new AtomicInteger(0);
            dispatcher.subscribeTopic("order.created", msg -> {
                counter.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            var msg = Message.builder().topic("order.updated").content("x").build();
            dispatcher.publish(msg).join();
            Thread.sleep(200);

            assertThat(counter.get()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Subscription lifecycle
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Subscription lifecycle")
    class SubscriptionLifecycle {

        @Test
        @DisplayName("subscriptionId is non-null and non-blank")
        void subscriptionIdNonBlank() {
            Subscription sub = dispatcher.subscribeTopic("any.topic", msg ->
                    CompletableFuture.completedFuture(null));

            assertThat(sub.subscriptionId()).isNotBlank();
        }

        @Test
        @DisplayName("Unsubscribed handler no longer receives messages")
        void unsubscribeStopsDelivery() throws Exception {
            var counter = new AtomicInteger(0);

            Subscription sub = dispatcher.subscribeTopic("news", msg -> {
                counter.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            // Receive one message
            dispatcher.publish(Message.builder().topic("news").content("1").build()).join();
            Thread.sleep(200);

            sub.unsubscribe();

            // Should not receive this one
            dispatcher.publish(Message.builder().topic("news").content("2").build()).join();
            Thread.sleep(200);

            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("subscribeRecipient returns a valid Subscription")
        void recipientSubscriptionHasId() {
            Subscription sub = dispatcher.subscribeRecipient("agent-1", msg ->
                    CompletableFuture.completedFuture(null));

            assertThat(sub.subscriptionId()).isNotBlank();
        }

        @Test
        @DisplayName("subscribeFiltered returns a valid Subscription")
        void filteredSubscriptionHasId() {
            Subscription sub = dispatcher.subscribeFiltered(
                    msg -> msg.topic().startsWith("order."),
                    msg -> CompletableFuture.completedFuture(null));

            assertThat(sub.subscriptionId()).isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // DirectMessenger + DirectReceiver
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Direct messaging")
    class DirectMessaging {

        @Test
        @DisplayName("sendTo delivers to registered local agent")
        void sendToRegisteredAgent() throws Exception {
            // Register agent in directory
            var descriptor = dev.agenor.core.AgentDescriptor.builder("agent-x")
                    .agentName("Agent X")
                    .build();
            directory.register(descriptor).join();

            var latch = new CountDownLatch(1);
            var received = new AtomicReference<Message>();

            dispatcher.subscribeRecipient("agent-x", msg -> {
                received.set(msg);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            var msg = Message.builder().receiverId("agent-x").topic("direct").content("hello").build();
            dispatcher.sendTo(msg).join();

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().content()).isEqualTo("hello");
        }

        @Test
        @DisplayName("sendTo unknown agent completes exceptionally with AgentNotFoundException")
        void sendToUnknownAgentThrows() {
            var msg = Message.builder().receiverId("no-such-agent").topic("direct").content("hello").build();
            var future = dispatcher.sendTo(msg);

            assertThatThrownBy(future::join)
                    .hasCauseInstanceOf(AgentNotFoundException.class);
        }

        @Test
        @DisplayName("sendTo delivers to ephemeral subscribeRecipient even if not in agent directory")
        void sendToEphemeralReceiverWithoutDirectoryEntry() throws Exception {
            // "client-agent" is NOT registered in the directory (mirrors AgenorA2AAdapter.sendInternal
            // which registers a temporary subscriber to collect the reply without being a real agent).
            var latch = new CountDownLatch(1);
            var received = new AtomicReference<Message>();

            dispatcher.subscribeRecipient("client-agent", msg -> {
                received.set(msg);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            var msg = Message.builder()
                    .receiverId("client-agent")
                    .topic("direct")
                    .content("reply-payload")
                    .build();

            dispatcher.sendTo(msg).join();

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().content()).isEqualTo("reply-payload");
        }

        @Test
        @DisplayName("Unsubscribed recipient does not receive further messages")
        void unsubscribedRecipientNoDelivery() throws Exception {
            var descriptor = dev.agenor.core.AgentDescriptor.builder("agent-y")
                    .agentName("Agent Y")
                    .build();
            directory.register(descriptor).join();

            var counter = new AtomicInteger(0);
            Subscription sub = dispatcher.subscribeRecipient("agent-y", msg -> {
                counter.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            var msg = Message.builder().receiverId("agent-y").topic("direct").content("1").build();
            dispatcher.sendTo(msg).join();
            Thread.sleep(200);

            sub.unsubscribe();

            dispatcher.sendTo(msg).join();
            Thread.sleep(200);

            assertThat(counter.get()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // FilterableSubscriber
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Predicate filtering")
    class PredicateFiltering {

        @Test
        @DisplayName("subscribeFiltered only delivers matching messages")
        void onlyMatchingMessagesDelivered() throws Exception {
            var counter = new AtomicInteger(0);
            var latch = new CountDownLatch(2); // expect 2 matching

            dispatcher.subscribeFiltered(
                    msg -> msg.topic().startsWith("order."),
                    msg -> {
                        counter.incrementAndGet();
                        latch.countDown();
                        return CompletableFuture.completedFuture(null);
                    });

            dispatcher.publish(
                    Message.builder().topic("order.created").content("A").build()).join();
            dispatcher.publish(
                    Message.builder().topic("order.shipped").content("B").build()).join();
            dispatcher.publish(
                    Message.builder().topic("product.created").content("C").build()).join(); // no match

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200); // wait in case "product.created" fires late
            assertThat(counter.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Unsubscribed predicate handler no longer receives messages")
        void unsubscribedPredicateNoDelivery() throws Exception {
            var counter = new AtomicInteger(0);

            Subscription sub = dispatcher.subscribeFiltered(
                    msg -> true,
                    msg -> {
                        counter.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });

            dispatcher.publish(Message.builder().topic("x").content("1").build()).join();
            Thread.sleep(200);

            sub.unsubscribe();

            dispatcher.publish(Message.builder().topic("x").content("2").build()).join();
            Thread.sleep(200);

            assertThat(counter.get()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @Test
        @DisplayName("getStatistics returns counts for all subscription types")
        void statisticsReflectSubscriptions() {
            dispatcher.subscribeTopic("t1", msg -> CompletableFuture.completedFuture(null));
            dispatcher.subscribeRecipient("r1", msg -> CompletableFuture.completedFuture(null));
            dispatcher.subscribeFiltered(msg -> true, msg -> CompletableFuture.completedFuture(null));

            var stats = dispatcher.getStatistics();

            assertThat((int) stats.get("topicSubscriptions")).isGreaterThanOrEqualTo(1);
            assertThat((int) stats.get("receiverSubscriptions")).isGreaterThanOrEqualTo(1);
            assertThat((int) stats.get("predicateSubscriptions")).isGreaterThanOrEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Null checks
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Null-safety")
    class NullSafety {

        @Test
        @DisplayName("publish with null topic in message throws IllegalArgumentException")
        void publishNullTopicThrows() {
            var msg = Message.builder().content("c").build();
            assertThatThrownBy(() -> dispatcher.publish(msg))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("publish with null message throws NullPointerException")
        void publishNullMsgThrows() {
            assertThatThrownBy(() -> dispatcher.publish(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("subscribeTopic with null topic throws NullPointerException")
        void subscribeNullTopicThrows() {
            assertThatThrownBy(() -> dispatcher.subscribeTopic(null,
                    msg -> CompletableFuture.completedFuture(null)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("sendTo with null receiverId in message throws IllegalArgumentException")
        void sendToNullAgentThrows() {
            var msg = Message.builder().topic("t").content("c").build();
            assertThatThrownBy(() -> dispatcher.sendTo(msg))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
