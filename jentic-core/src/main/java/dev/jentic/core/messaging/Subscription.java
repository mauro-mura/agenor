package dev.jentic.core.messaging;

/**
 * Handle representing an active message subscription.
 *
 * <p>Returned by all subscribe methods on {@link TopicSubscriber}, {@link DirectReceiver},
 * and {@link FilterableSubscriber}. Callers must invoke {@link #unsubscribe()} when the
 * handler is no longer needed to prevent resource leaks.
 *
 * <p>Implementations must make {@link #unsubscribe()} idempotent — calling it multiple
 * times must be safe.
 *
 * @since 0.20.0
 */
public interface Subscription {

    /**
     * Returns the unique identifier assigned to this subscription.
     *
     * @return non-null subscription identifier
     */
    String subscriptionId();

    /**
     * Cancels this subscription.
     *
     * <p>After this call the associated handler will no longer receive messages.
     * Calling this method more than once is safe.
     */
    void unsubscribe();

    /**
     * Creates a {@code Subscription} from a pre-existing ID and an unsubscribe action.
     *
     * <p>Useful for bridging legacy String-returning subscribe methods to this interface.
     *
     * @param id                unique subscription identifier
     * @param unsubscribeAction action to invoke on {@link #unsubscribe()}; must be idempotent
     * @return a new subscription
     */
    static Subscription of(String id, Runnable unsubscribeAction) {
        return new Subscription() {
            @Override
            public String subscriptionId() { return id; }

            @Override
            public void unsubscribe() { unsubscribeAction.run(); }
        };
    }
}
