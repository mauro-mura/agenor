package dev.jentic.core.messaging;

import dev.jentic.core.Message;

import java.util.concurrent.CompletableFuture;

/**
 * Capability for publishing messages to named topics (one-to-many).
 *
 * <p>All subscribers registered via {@link TopicSubscriber#subscribeTopic} for the same
 * topic will receive each published message. The routing topic is taken from
 * {@link dev.jentic.core.Message#topic()}.
 *
 * @since 0.20.0
 * @see TopicSubscriber
 * @see MessageDispatcher
 */
public interface TopicPublisher {

    /**
     * Publishes a message to all subscribers of {@link Message#topic()}.
     *
     * @param msg the message to publish; {@link Message#topic()} must not be null or empty
     * @return a future that completes when the message has been dispatched
     * @throws NullPointerException     if msg is null
     * @throws IllegalArgumentException if {@link Message#topic()} is null or empty
     */
    CompletableFuture<Void> publish(Message msg);
}
