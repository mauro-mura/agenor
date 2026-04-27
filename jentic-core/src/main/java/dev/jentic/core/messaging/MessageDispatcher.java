package dev.jentic.core.messaging;

/**
 * Primary messaging interface for agents — hides routing details.
 *
 * <p>{@code MessageDispatcher} combines the four core messaging capabilities:
 * <ul>
 *   <li>{@link TopicPublisher} — publish to a named topic (one-to-many)</li>
 *   <li>{@link TopicSubscriber} — subscribe to a named topic</li>
 *   <li>{@link DirectMessenger} — send directly to an agent by ID (one-to-one)</li>
 *   <li>{@link DirectReceiver} — receive direct messages addressed to a local agent</li>
 * </ul>
 *
 * <p>All distributed backends implement this interface. Backends that additionally support
 * in-process predicate filtering implement {@link FilterableSubscriber}, but agents that
 * depend on it directly will fail at startup wiring when a remote backend is configured.
 *
 * <p>The default implementation is {@code InMemoryMessageDispatcher} in
 * {@code jentic-runtime}.
 *
 * @since 0.20.0
 * @see dev.jentic.core.MessageService
 * @see FilterableSubscriber
 */
public interface MessageDispatcher
        extends TopicPublisher, TopicSubscriber, DirectMessenger, DirectReceiver {
}
