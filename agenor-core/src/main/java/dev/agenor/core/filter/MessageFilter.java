package dev.agenor.core.filter;

import dev.agenor.core.Message;
import dev.agenor.core.messaging.FilterableSubscriber;

import java.util.function.Predicate;

/**
 * Defines criteria for filtering messages during routing and subscription.
 *
 * <p>Message filters provide a powerful and flexible way to specify which messages
 * should be delivered to specific handlers. Filters can match based on message
 * topics, headers, content, or custom predicates. Multiple filter criteria
 * can be combined using logical operators.
 *
 * <p>Filters are evaluated efficiently during message routing to minimize
 * overhead. Simple filters (topic, header matching) use optimized implementations,
 * while complex predicate-based filters provide maximum flexibility.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple topic filter
 * MessageFilter orderFilter = MessageFilter.builder()
 *     .topicStartsWith("order.")
 *     .build();
 *
 * // Complex filter with multiple criteria
 * MessageFilter priorityFilter = MessageFilter.builder()
 *     .topicMatches("order\\..*")
 *     .headerEquals("priority", "HIGH")
 *     .headerExists("customer-id")
 *     .contentPredicate(content -> content instanceof OrderData order && order.amount() > 1000)
 *     .build();
 *
 * // Combine filters
 * MessageFilter combined = orderFilter.and(priorityFilter);
 * }</pre>
 *
 * <p>Performance Considerations:
 * <ul>
 * <li>Topic-based filters are fastest (O(1) lookup)</li>
 * <li>Header filters are fast (O(1) map lookup)</li>
 * <li>Content predicates are slower but most flexible</li>
 * <li>Composite filters short-circuit on the first false condition</li>
 * </ul>
 *
 * @since 0.2.0
 * @see FilterableSubscriber
 * @see MessageFilterBuilder
 * @see Message
 */
@FunctionalInterface
public interface MessageFilter extends Predicate<Message> {

    /**
     * Test if the message matches this filter
     *
     * @param message the message to test
     * @return true if the message matches the filter criteria
     */
    @Override
    boolean test(Message message);

    /**
     * Create a filter that matches all messages
     *
     * @return filter that always returns true
     */
    static MessageFilter acceptAll() {
        return message -> true;
    }

    /**
     * Create a filter that rejects all messages
     *
     * @return filter that always returns false
     */
    static MessageFilter rejectAll() {
        return message -> false;
    }

    /**
     * Create a filter from a predicate
     *
     * @param predicate the predicate to use
     * @return message filter wrapping the predicate
     */
    static MessageFilter of(Predicate<Message> predicate) {
        return predicate::test;
    }

    /**
     * Combine this filter with another using AND logic
     *
     * @param other the other filter
     * @return combined filter (this AND other)
     */
    default MessageFilter and(MessageFilter other) {
        return message -> this.test(message) && other.test(message);
    }

    /**
     * Combine this filter with another using OR logic
     *
     * @param other the other filter
     * @return combined filter (this OR other)
     */
    default MessageFilter or(MessageFilter other) {
        return message -> this.test(message) || other.test(message);
    }

    /**
     * Negate this filter
     *
     * @return negated filter (NOT this)
     */
    default MessageFilter negate() {
        return message -> !this.test(message);
    }

    /**
     * Create a filter builder for fluent API
     *
     * @return new message filter builder
     */
    static MessageFilterBuilder builder() {
        return new MessageFilterBuilder();
    }
}
