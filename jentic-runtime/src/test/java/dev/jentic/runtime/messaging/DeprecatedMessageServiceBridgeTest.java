package dev.jentic.runtime.messaging;

import dev.jentic.core.Message;
import dev.jentic.core.MessageService;
import dev.jentic.core.messaging.Subscription;
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

/**
 * Verifies that the default bridge methods declared in {@link MessageService} produce
 * the same observable behaviour as the equivalent new API methods on
 * {@link InMemoryMessageDispatcher}.
 *
 * <p>The bridge ensures that code written against the 0.19 {@code MessageService} API
 * continues to compile and run at 0.20 with only deprecation warnings.
 *
 * <p><strong>Known semantic difference (documented):</strong> the bridge implementations
 * of {@code publish(topic, msg)} and {@code sendTo(agentId, msg)} both delegate to
 * {@code send(msg)}, which routes on the <em>message fields</em> ({@code msg.topic()}
 * and {@code msg.receiverId()}) rather than on the passed-in {@code topic} /
 * {@code agentId} parameters. This is intentional — the legacy contract placed routing
 * data inside the message; callers must set the relevant field before calling the bridge.
 *
 * @since 0.20.0
 */
@SuppressWarnings("deprecation")
@DisplayName("MessageService bridge — legacy API via default bridge methods")
class DeprecatedMessageServiceBridgeTest {

    private InMemoryMessageService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryMessageService();
    }

    // -------------------------------------------------------------------------
    // publish(topic, msg) bridges to send(msg) for topic delivery
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("publish bridge")
    class PublishBridge {

        @Test
        @DisplayName("publish(topic, msg) delivers to a topic subscriber")
        void publishBridge_deliversToTopicSubscriber() throws Exception {
            var latch = new CountDownLatch(1);
            var received = new AtomicReference<Message>();

            service.subscribe("order.created", msg -> {
                received.set(msg);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            var msg = Message.builder().topic("order.created").content("event").build();
            service.publish("order.created", msg).join();

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().content()).isEqualTo("event");
        }

        @Test
        @DisplayName("publish bridge result is the same CompletableFuture as send(msg)")
        void publishBridge_returnsCompletedFuture() {
            var msg = Message.builder().topic("t").content("c").build();
            assertThat(service.publish("t", msg)).succeedsWithin(2, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // sendTo(agentId, msg) bridges to send(msg) for receiver delivery
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("sendTo bridge")
    class SendToBridge {

        @Test
        @DisplayName("sendTo(agentId, msg) delivers to a receiver subscriber when msg.receiverId matches")
        void sendToBridge_deliversToReceiverSubscriber() throws Exception {
            var latch = new CountDownLatch(1);
            var received = new AtomicReference<Message>();

            service.subscribeToReceiver("agent-a", msg -> {
                received.set(msg);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Bridge delegates to send(msg); routing uses msg.receiverId(), not the parameter.
            var msg = Message.builder().topic("direct").receiverId("agent-a").content("hi").build();
            service.sendTo("agent-a", msg).join();

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().content()).isEqualTo("hi");
        }
    }

    // -------------------------------------------------------------------------
    // subscribeTopic bridges to subscribe(topic, handler)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeTopic bridge")
    class SubscribeTopicBridge {

        @Test
        @DisplayName("subscribeTopic returns a non-null Subscription with a non-blank id")
        void subscribeTopicBridge_returnsSubscription() {
            Subscription sub = service.subscribeTopic("news", msg ->
                    CompletableFuture.completedFuture(null));

            assertThat(sub).isNotNull();
            assertThat(sub.subscriptionId()).isNotBlank();
        }

        @Test
        @DisplayName("subscribeTopic delivers messages identically to subscribe(topic, handler)")
        void subscribeTopicBridge_sameDeliveryAsLegacy() throws Exception {
            var legacyCount = new AtomicInteger(0);
            var bridgeCount = new AtomicInteger(0);
            var latch = new CountDownLatch(2);

            service.subscribe("ping", msg -> {
                legacyCount.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
            service.subscribeTopic("ping", msg -> {
                bridgeCount.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            service.send(Message.builder().topic("ping").content("p").build()).join();
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(legacyCount.get()).isEqualTo(1);
            assertThat(bridgeCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("subscribeTopic sub.unsubscribe() stops delivery")
        void subscribeTopicBridge_unsubscribeStopsDelivery() throws Exception {
            var counter = new AtomicInteger(0);

            Subscription sub = service.subscribeTopic("events", msg -> {
                counter.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            service.send(Message.builder().topic("events").content("1").build()).join();
            Thread.sleep(200);
            sub.unsubscribe();
            service.send(Message.builder().topic("events").content("2").build()).join();
            Thread.sleep(200);

            assertThat(counter.get()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // subscribeRecipient bridges to subscribeToReceiver(id, handler)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeRecipient bridge")
    class SubscribeRecipientBridge {

        @Test
        @DisplayName("subscribeRecipient returns a non-null Subscription")
        void subscribeRecipientBridge_returnsSubscription() {
            Subscription sub = service.subscribeRecipient("agent-r", msg ->
                    CompletableFuture.completedFuture(null));

            assertThat(sub).isNotNull();
            assertThat(sub.subscriptionId()).isNotBlank();
        }

        @Test
        @DisplayName("subscribeRecipient delivers direct messages identically to subscribeToReceiver")
        void subscribeRecipientBridge_sameDeliveryAsLegacy() throws Exception {
            var legacyCount = new AtomicInteger(0);
            var bridgeCount = new AtomicInteger(0);
            var latch = new CountDownLatch(2);

            service.subscribeToReceiver("agent-r", msg -> {
                legacyCount.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
            service.subscribeRecipient("agent-r", msg -> {
                bridgeCount.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            service.send(Message.builder().topic("t").receiverId("agent-r").content("x").build()).join();
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(legacyCount.get()).isEqualTo(1);
            assertThat(bridgeCount.get()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // subscribeFiltered bridges to subscribe(Predicate, handler)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("subscribeFiltered bridge")
    class SubscribeFilteredBridge {

        @Test
        @DisplayName("subscribeFiltered returns a non-null Subscription")
        void subscribeFilteredBridge_returnsSubscription() {
            Subscription sub = service.subscribeFiltered(
                    msg -> true,
                    msg -> CompletableFuture.completedFuture(null));

            assertThat(sub).isNotNull();
            assertThat(sub.subscriptionId()).isNotBlank();
        }

        @Test
        @DisplayName("subscribeFiltered delivers matching messages identically to subscribe(Predicate, h)")
        void subscribeFilteredBridge_sameDeliveryAsLegacy() throws Exception {
            var legacyCount = new AtomicInteger(0);
            var bridgeCount = new AtomicInteger(0);
            var latch = new CountDownLatch(2);

            service.subscribe(
                    msg -> msg.topic().startsWith("order."),
                    msg -> {
                        legacyCount.incrementAndGet();
                        latch.countDown();
                        return CompletableFuture.completedFuture(null);
                    });
            service.subscribeFiltered(
                    msg -> msg.topic().startsWith("order."),
                    msg -> {
                        bridgeCount.incrementAndGet();
                        latch.countDown();
                        return CompletableFuture.completedFuture(null);
                    });

            service.send(Message.builder().topic("order.created").content("o").build()).join();
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(legacyCount.get()).isEqualTo(1);
            assertThat(bridgeCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("subscribeFiltered sub.unsubscribe() stops delivery")
        void subscribeFilteredBridge_unsubscribeStopsDelivery() throws Exception {
            var counter = new AtomicInteger(0);

            Subscription sub = service.subscribeFiltered(
                    msg -> true,
                    msg -> {
                        counter.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    });

            service.send(Message.builder().topic("t").content("1").build()).join();
            Thread.sleep(200);
            sub.unsubscribe();
            service.send(Message.builder().topic("t").content("2").build()).join();
            Thread.sleep(200);

            assertThat(counter.get()).isEqualTo(1);
        }
    }
}
