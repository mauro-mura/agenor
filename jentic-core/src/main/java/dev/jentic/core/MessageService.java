package dev.jentic.core;

import dev.jentic.core.messaging.FilterableSubscriber;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.messaging.Subscription;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Unified messaging façade — <strong>deprecated</strong> since 0.20.0.
 *
 * <p>Use {@link dev.jentic.core.messaging.MessageDispatcher} instead. The split
 * interface design unlocks distributed backends (Redis, Kafka) without semantic
 * compromise; see ADR-020 for the full rationale.
 *
 * <p><strong>Migration guide</strong>:
 * <table border="1">
 *   <caption>MessageService migration</caption>
 *   <tr><th>Old call</th><th>New call</th></tr>
 *   <tr><td>{@code send(msg)} (topic)</td>
 *       <td>{@code publish(msg.topic(), msg)}</td></tr>
 *   <tr><td>{@code send(msg)} (direct)</td>
 *       <td>{@code sendTo(msg.receiverId(), msg)}</td></tr>
 *   <tr><td>{@code subscribe(topic, h)}</td>
 *       <td>{@code subscribeTopic(topic, h)}</td></tr>
 *   <tr><td>{@code subscribeToReceiver(id, h)}</td>
 *       <td>{@code subscribeRecipient(id, h)}</td></tr>
 *   <tr><td>{@code subscribe(predicate, h)}</td>
 *       <td>{@code subscribeFiltered(predicate, h)} (in-memory only)</td></tr>
 *   <tr><td>{@code unsubscribe(id)}</td>
 *       <td>{@code subscription.unsubscribe()}</td></tr>
 * </table>
 *
 * @since 0.1.0
 * @deprecated since 0.20.0, for removal at 0.22.0. Use
 *     {@link dev.jentic.core.messaging.MessageDispatcher}.
 */
@Deprecated(since = "0.20.0", forRemoval = true)
public interface MessageService extends MessageDispatcher, FilterableSubscriber {

    // -------------------------------------------------------------------------
    // Deprecated legacy abstract methods
    // -------------------------------------------------------------------------

    /**
     * Sends a message by routing on its {@code topic} or {@code receiverId}.
     *
     * @deprecated Use {@link #publish(Message)} for topic messages or
     *     {@link #sendTo(Message)} for direct messages.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    CompletableFuture<Void> send(Message message);

    /**
     * Subscribes to direct messages for a specific receiver ID.
     *
     * @deprecated Use {@link #subscribeRecipient(String, MessageHandler)}.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    String subscribeToReceiver(String receiverId, MessageHandler handler);

    /**
     * Subscribes to all messages on the given topic.
     *
     * @deprecated Use {@link #subscribeTopic(String, MessageHandler)}.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    String subscribe(String topic, MessageHandler handler);

    /**
     * Subscribes to messages matching a Java predicate.
     *
     * @deprecated Use {@link #subscribeFiltered(Predicate, MessageHandler)}.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    String subscribe(Predicate<Message> filter, MessageHandler handler);

    /**
     * Cancels a subscription by its string ID.
     *
     * @deprecated Use {@link dev.jentic.core.messaging.Subscription#unsubscribe()} on
     *     the object returned by the subscribe methods.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    void unsubscribe(String subscriptionId);

    /**
     * Sends a message and waits for a correlated reply with a timeout.
     *
     * @deprecated Will be removed at 0.22.0 alongside the rest of this interface.
     */
    @Deprecated(since = "0.20.0", forRemoval = true)
    CompletableFuture<Message> sendAndWait(Message message, long timeout);

    // -------------------------------------------------------------------------
    // MessageDispatcher default bridges → delegate to legacy abstract methods
    // -------------------------------------------------------------------------

    /** Delegates to {@link #send(Message)}. */
    @Override
    default CompletableFuture<Void> publish(Message msg) {
        return send(msg);
    }

    /** Delegates to {@link #send(Message)}. */
    @Override
    default CompletableFuture<Void> sendTo(Message msg) {
        return send(msg);
    }

    /** Delegates to {@link #subscribe(String, MessageHandler)}, wrapping the ID. */
    @Override
    default Subscription subscribeTopic(String topic, MessageHandler handler) {
        var id = subscribe(topic, handler);
        return Subscription.of(id, () -> unsubscribe(id));
    }

    /** Delegates to {@link #subscribeToReceiver(String, MessageHandler)}, wrapping the ID. */
    @Override
    default Subscription subscribeRecipient(String localAgentId, MessageHandler handler) {
        var id = subscribeToReceiver(localAgentId, handler);
        return Subscription.of(id, () -> unsubscribe(id));
    }

    /** Delegates to {@link #subscribe(Predicate, MessageHandler)}, wrapping the ID. */
    @Override
    default Subscription subscribeFiltered(Predicate<Message> filter, MessageHandler handler) {
        var id = subscribe(filter, handler);
        return Subscription.of(id, () -> unsubscribe(id));
    }
}
