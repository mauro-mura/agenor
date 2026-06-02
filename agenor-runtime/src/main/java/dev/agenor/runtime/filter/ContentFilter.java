package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;

import java.util.function.Predicate;

/**
 * Filter messages by content
 */
public class ContentFilter implements MessageFilter {

    private final Predicate<Object> predicate;

    private ContentFilter(Predicate<Object> predicate) {
        this.predicate = predicate;
    }

    @Override
    public boolean test(Message message) {
        return message.content() != null && predicate.test(message.content());
    }

    /**
     * Content must be of specific type
     */
    public static ContentFilter ofType(Class<?> type) {
        return new ContentFilter(type::isInstance);
    }

    /**
     * Content must satisfy predicate
     */
    public static ContentFilter matching(Predicate<Object> predicate) {
        return new ContentFilter(predicate);
    }

    /**
     * Content must be non-null
     */
    public static ContentFilter notNull() {
        return new ContentFilter(obj -> true);
    }
}
