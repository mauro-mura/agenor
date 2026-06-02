package dev.agenor.core.filter;

import dev.agenor.core.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Builder for creating complex message filters with fluent API.
 *
 * <p>The filter builder provides a convenient fluent interface for constructing
 * message filters with multiple criteria. Filters built this way are optimized
 * for performance and support efficient evaluation during message routing.
 *
 * <p>Builder instances are not thread-safe and should not be shared between threads.
 * However, the filters they build are thread-safe and can be used concurrently.
 *
 * <p>Example usage:
 * <pre>{@code
 * MessageFilter filter = MessageFilter.builder()
 *     .topicStartsWith("order.")
 *     .headerEquals("priority", "HIGH")
 *     .headerMatches("region", "us-.*")
 *     .headerExists("customer-id")
 *     .contentType(OrderData.class)
 *     .contentPredicate(order -> order.amount() > 1000)
 *     .build();
 * }</pre>
 *
 * <p>The builder supports method chaining and creates efficient filter implementations
 * based on the specified criteria. Simple criteria (topic, header matching) use
 * optimized implementations, while complex predicates provide maximum flexibility.
 *
 * @since 0.2.0
 * @see MessageFilter
 * @see Message
 */
public class MessageFilterBuilder {

    private final List<MessageFilter> filters = new ArrayList<>();
    private FilterOperator operator = FilterOperator.AND;

    /**
     * Set the logical operator for combining filters
     *
     * @param operator AND or OR
     * @return this builder
     */
    public MessageFilterBuilder operator(FilterOperator operator) {
        this.operator = operator;
        return this;
    }

    /**
     * Filter by exact topic match
     *
     * @param topic the topic to match
     * @return this builder
     */
    public MessageFilterBuilder topic(String topic) {
        filters.add(msg -> topic.equals(msg.topic()));
        return this;
    }

    /**
     * Filter by topic prefix
     *
     * @param prefix the topic prefix
     * @return this builder
     */
    public MessageFilterBuilder topicStartsWith(String prefix) {
        filters.add(msg -> msg.topic() != null && msg.topic().startsWith(prefix));
        return this;
    }

    /**
     * Filter by topic regex pattern
     *
     * @param pattern regex pattern to match
     * @return this builder
     */
    public MessageFilterBuilder topicMatches(String pattern) {
        Pattern compiled = Pattern.compile(pattern);
        filters.add(msg -> msg.topic() != null && compiled.matcher(msg.topic()).matches());
        return this;
    }

    /**
     * Filter by sender ID
     *
     * @param senderId the sender ID to match
     * @return this builder
     */
    public MessageFilterBuilder senderId(String senderId) {
        filters.add(msg -> senderId.equals(msg.senderId()));
        return this;
    }

    /**
     * Filter by receiver ID
     *
     * @param receiverId the receiver ID to match
     * @return this builder
     */
    public MessageFilterBuilder receiverId(String receiverId) {
        filters.add(msg -> receiverId.equals(msg.receiverId()));
        return this;
    }

    /**
     * Filter by correlation ID
     *
     * @param correlationId the correlation ID to match
     * @return this builder
     */
    public MessageFilterBuilder correlationId(String correlationId) {
        filters.add(msg -> correlationId.equals(msg.correlationId()));
        return this;
    }

    /**
     * Filter by header exact match
     *
     * @param key header key
     * @param value header value
     * @return this builder
     */
    public MessageFilterBuilder headerEquals(String key, String value) {
        filters.add(msg -> value.equals(msg.headers().get(key)));
        return this;
    }

    /**
     * Filter by header existence
     *
     * @param key header key that must exist
     * @return this builder
     */
    public MessageFilterBuilder headerExists(String key) {
        filters.add(msg -> msg.headers().containsKey(key));
        return this;
    }

    /**
     * Filter by header regex match
     *
     * @param key header key
     * @param pattern regex pattern for value
     * @return this builder
     */
    public MessageFilterBuilder headerMatches(String key, String pattern) {
        Pattern compiled = Pattern.compile(pattern);
        filters.add(msg -> {
            String value = msg.headers().get(key);
            return value != null && compiled.matcher(value).matches();
        });
        return this;
    }

    /**
     * Filter by header value in set
     *
     * @param key header key
     * @param values allowed values
     * @return this builder
     */
    public MessageFilterBuilder headerIn(String key, String... values) {
        List<String> allowedValues = Arrays.asList(values);
        filters.add(msg -> {
            String value = msg.headers().get(key);
            return value != null && allowedValues.contains(value);
        });
        return this;
    }

    /**
     * Filter by content type
     *
     * @param type expected content class
     * @return this builder
     */
    public MessageFilterBuilder contentType(Class<?> type) {
        filters.add(msg -> msg.content() != null && type.isInstance(msg.content()));
        return this;
    }

    /**
     * Filter by custom content predicate
     *
     * @param predicate predicate to test content
     * @return this builder
     */
    public MessageFilterBuilder contentPredicate(Predicate<Object> predicate) {
        filters.add(msg -> msg.content() != null && predicate.test(msg.content()));
        return this;
    }

    /**
     * Filter by custom message predicate
     *
     * @param predicate predicate to test entire message
     * @return this builder
     */
    public MessageFilterBuilder customPredicate(Predicate<Message> predicate) {
        filters.add(MessageFilter.of(predicate));
        return this;
    }

    /**
     * Add a pre-built filter
     *
     * @param filter the filter to add
     * @return this builder
     */
    public MessageFilterBuilder addFilter(MessageFilter filter) {
        filters.add(filter);
        return this;
    }

    /**
     * Build the composite filter
     *
     * @return combined message filter
     */
    public MessageFilter build() {
        if (filters.isEmpty()) {
            return MessageFilter.acceptAll();
        }

        if (filters.size() == 1) {
            return filters.get(0);
        }

        return operator == FilterOperator.AND ?
            combineWithAnd() : combineWithOr();
    }

    private MessageFilter combineWithAnd() {
        return message -> {
            for (MessageFilter filter : filters) {
                if (!filter.test(message)) {
                    return false;
                }
            }
            return true;
        };
    }

    private MessageFilter combineWithOr() {
        return message -> {
            for (MessageFilter filter : filters) {
                if (filter.test(message)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Logical operator for combining filters
     */
    public enum FilterOperator {
        AND, OR
    }
}
