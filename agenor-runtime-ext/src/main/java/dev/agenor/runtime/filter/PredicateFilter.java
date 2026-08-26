package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;

import java.util.function.Predicate;

/**
 * Generic filter from predicate
 *
 * @deprecated since 0.28.0, for removal in 0.30.0. Nothing in the tree names this class,
 *             and {@code MessageFilter.of(Predicate)} in {@code agenor-core} already builds a
 *             filter from a predicate. Use that: it is the one users actually name.
 */
@Deprecated(since = "0.28.0", forRemoval = true)
public class PredicateFilter implements MessageFilter {

    private final Predicate<Message> predicate;
    private final String description;

    public PredicateFilter(Predicate<Message> predicate) {
        this(predicate, "custom-predicate");
    }

    public PredicateFilter(Predicate<Message> predicate, String description) {
        this.predicate = predicate;
        this.description = description;
    }

    @Override
    public boolean test(Message message) {
        return predicate.test(message);
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "PredicateFilter[" + description + "]";
    }
}
