package dev.jentic.core.messaging;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;

import java.util.function.Predicate;

/**
 * Optional capability for subscribing with an arbitrary Java predicate.
 *
 * <p><strong>In-memory only.</strong> Remote backends (Redis, Kafka, JDBC) do not implement
 * this interface because a Java {@link Predicate} cannot be serialised server-side. Code
 * that injects this interface will fail at startup wiring when a remote backend is configured,
 * making the incompatibility explicit rather than a silent runtime degradation.
 *
 * <p>Prefer topic-based subscriptions via {@link TopicSubscriber} whenever possible.
 * Use this interface only for in-process filtering that cannot be expressed as a topic name.
 *
 * @since 0.20.0
 * @see TopicSubscriber
 * @see MessageDispatcher
 */
public interface FilterableSubscriber {

    /**
     * Registers a handler for all messages for which the predicate returns {@code true}.
     *
     * <p>The predicate is evaluated for every dispatched message and should be fast and
     * side-effect-free. Heavy logic should be deferred to the handler body.
     *
     * @param filter  the predicate to evaluate against each message, must not be null
     * @param handler the handler to invoke when the filter matches, must not be null
     * @return a {@link Subscription} that can be used to cancel the subscription
     * @throws NullPointerException if filter or handler is null
     */
    Subscription subscribeFiltered(Predicate<Message> filter, MessageHandler handler);
}
