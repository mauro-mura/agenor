package dev.agenor.core.messaging;

import dev.agenor.core.MessageHandler;

/**
 * Capability for subscribing to messages on a named topic.
 *
 * @since 0.20.0
 * @see TopicPublisher
 * @see MessageDispatcher
 */
public interface TopicSubscriber {

    /**
     * Registers a handler for all messages published to the given topic.
     *
     * @param topic   the topic to subscribe to, must not be null or empty
     * @param handler the handler to invoke on each message, must not be null
     * @return a {@link Subscription} that can be used to cancel the subscription
     * @throws NullPointerException     if topic or handler is null
     * @throws IllegalArgumentException if topic is empty
     */
    Subscription subscribeTopic(String topic, MessageHandler handler);
}
