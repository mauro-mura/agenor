package dev.jentic.core.messaging;

import dev.jentic.core.Message;

import java.util.concurrent.CompletableFuture;

/**
 * Capability for publishing messages to named topics (one-to-many).
 *
 * <p>All subscribers registered via {@link TopicSubscriber#subscribeTopic} for the same
 * topic will receive each published message.
 *
 * @since 0.20.0
 * @see TopicSubscriber
 * @see MessageDispatcher
 */
public interface TopicPublisher {

    /**
     * Publishes a message to all subscribers of the given topic.
     *
     * @param topic the topic name, must not be null or empty
     * @param msg   the message to publish, must not be null
     * @return a future that completes when the message has been dispatched
     * @throws NullPointerException     if topic or msg is null
     * @throws IllegalArgumentException if topic is empty
     */
    CompletableFuture<Void> publish(String topic, Message msg);
}
