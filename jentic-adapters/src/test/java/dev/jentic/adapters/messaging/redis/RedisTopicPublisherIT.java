package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@DisplayName("RedisTopicPublisher — integration tests (Valkey)")
class RedisTopicPublisherIT {

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    private RedisMessagingFactory factory;
    private RedisTopicPublisher publisher;

    @BeforeEach
    void setUp() {
        var uri   = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
        factory   = RedisMessagingFactory.builder().uri(uri).build();
        publisher = factory.topicPublisher();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("subscriber receives a published topic message")
    void publish_deliversToSubscriber() throws Exception {
        var latch    = new CountDownLatch(1);
        var received = new AtomicReference<Message>();

        publisher.subscribeTopic("test.orders", msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var msg = Message.builder().topic("test.orders").content("order-data").build();
        publisher.publish(msg).join();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().content()).isEqualTo("order-data");
    }

    @Test
    @DisplayName("two independent subscribers on the same topic both receive the message")
    void publish_multipleSubscribers_allReceive() throws Exception {
        var latch   = new CountDownLatch(2);
        var counter = new AtomicInteger(0);

        publisher.subscribeTopic("test.broadcast", msg -> {
            counter.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        publisher.subscribeTopic("test.broadcast", msg -> {
            counter.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        publisher.publish(Message.builder().topic("test.broadcast").content("event").build()).join();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("publish with no subscribers completes normally")
    void publish_noSubscribers_completesNormally() {
        var msg = Message.builder().topic("test.empty").content("x").build();
        assertThat(publisher.publish(msg)).succeedsWithin(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("unsubscribing stops message delivery")
    void subscribeTopic_afterUnsubscribe_stopsDelivery() throws Exception {
        var counter = new AtomicInteger(0);
        var latch   = new CountDownLatch(1);

        var sub = publisher.subscribeTopic("test.unsub", msg -> {
            counter.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        publisher.publish(Message.builder().topic("test.unsub").content("first").build()).join();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        sub.unsubscribe();
        Thread.sleep(200); // let the loop observe the stop

        publisher.publish(Message.builder().topic("test.unsub").content("second").build()).join();
        Thread.sleep(500); // give time for (unwanted) delivery

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("message content and headers are preserved through serialisation")
    void publish_preservesContentAndHeaders() throws Exception {
        var latch    = new CountDownLatch(1);
        var received = new AtomicReference<Message>();

        publisher.subscribeTopic("test.fidelity", msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var sent = Message.builder()
                .topic("test.fidelity")
                .senderId("agent-a")
                .correlationId("corr-99")
                .content("rich-payload")
                .header("priority", "HIGH")
                .header("region", "EU")
                .build();
        publisher.publish(sent).join();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        var r = received.get();
        assertThat(r.senderId()).isEqualTo("agent-a");
        assertThat(r.correlationId()).isEqualTo("corr-99");
        assertThat(r.content()).isEqualTo("rich-payload");
        assertThat(r.headers()).containsEntry("priority", "HIGH").containsEntry("region", "EU");
    }
}
