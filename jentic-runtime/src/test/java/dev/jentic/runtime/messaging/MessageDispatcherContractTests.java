package dev.jentic.runtime.messaging;

import dev.jentic.core.Message;
import dev.jentic.core.exceptions.AgentNotFoundException;
import dev.jentic.core.messaging.MessageDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusable contract tests for {@link MessageDispatcher}.
 *
 * <p>Implement this interface in any test class that exercises a {@code MessageDispatcher}
 * backend. The concrete class provides the dispatcher via {@link #createDispatcher()} and
 * a way to pre-register an agent via {@link #registerAgent(String)}.
 *
 * @since 0.20.0
 */
public interface MessageDispatcherContractTests {

    /** Creates a fresh dispatcher backed by the implementation under test. */
    MessageDispatcher createDispatcher();

    /**
     * Registers an agent in the backing directory so that {@code sendTo} can resolve it.
     * Called from within individual test methods after {@link #createDispatcher()}.
     */
    void registerAgent(String agentId);

    // -------------------------------------------------------------------------
    // TopicPublisher + TopicSubscriber
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Dispatcher] subscriber receives a published topic message")
    default void publish_deliversToTopicSubscriber() throws Exception {
        var dispatcher = createDispatcher();
        var latch = new CountDownLatch(1);
        var received = new AtomicReference<Message>();

        dispatcher.subscribeTopic("test.topic", msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var msg = Message.builder().topic("test.topic").content("payload").build();
        dispatcher.publish("test.topic", msg).join();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().content()).isEqualTo("payload");
    }

    @Test
    @DisplayName("[Dispatcher] publish to a topic with no subscribers completes normally")
    default void publish_noSubscriber_noOp() {
        var dispatcher = createDispatcher();
        var msg = Message.builder().topic("empty").content("x").build();
        assertThat(dispatcher.publish("empty", msg)).succeedsWithin(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("[Dispatcher] unsubscribing a topic handler stops delivery")
    default void subscribeTopic_unsubscribe_stopsDelivery() throws Exception {
        var dispatcher = createDispatcher();
        var counter = new AtomicInteger(0);

        var sub = dispatcher.subscribeTopic("news", msg -> {
            counter.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        dispatcher.publish("news", Message.builder().topic("news").content("1").build()).join();
        Thread.sleep(200);
        sub.unsubscribe();
        dispatcher.publish("news", Message.builder().topic("news").content("2").build()).join();
        Thread.sleep(200);

        assertThat(counter.get()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // DirectMessenger + DirectReceiver
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Dispatcher] sendTo delivers to the registered agent's receiver subscription")
    default void sendTo_registeredAgent_delivers() throws Exception {
        var dispatcher = createDispatcher();
        registerAgent("contract-agent");

        var latch = new CountDownLatch(1);
        var received = new AtomicReference<Message>();

        dispatcher.subscribeRecipient("contract-agent", msg -> {
            received.set(msg);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        dispatcher.sendTo("contract-agent",
                Message.builder().topic("direct").content("hello").build()).join();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().content()).isEqualTo("hello");
    }

    @Test
    @DisplayName("[Dispatcher] sendTo unknown agent completes exceptionally with AgentNotFoundException")
    default void sendTo_unknownAgent_agentNotFoundException() {
        var dispatcher = createDispatcher();
        var future = dispatcher.sendTo("unknown-contract-agent",
                Message.builder().topic("direct").content("x").build());

        assertThatThrownBy(future::join).hasCauseInstanceOf(AgentNotFoundException.class);
    }
}
