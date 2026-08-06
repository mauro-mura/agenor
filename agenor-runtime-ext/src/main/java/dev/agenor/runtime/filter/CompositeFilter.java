package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;

import java.util.Arrays;
import java.util.List;

/**
 * Combine multiple filters with AND/OR/NOT logic
 */
public class CompositeFilter implements MessageFilter {

    private final List<MessageFilter> filters;
    private final CombineOperator operator;

    public enum CombineOperator {
        AND, OR, NOT
    }

    private CompositeFilter(List<MessageFilter> filters, CombineOperator operator) {
        this.filters = List.copyOf(filters);
        this.operator = operator;
    }

    @Override
    public boolean test(Message message) {
        return switch (operator) {
            case AND -> testAnd(message);
            case OR -> testOr(message);
            case NOT -> testNot(message);
        };
    }

    private boolean testAnd(Message message) {
        for (MessageFilter filter : filters) {
            if (!filter.test(message)) {
                return false;
            }
        }
        return true;
    }

    private boolean testOr(Message message) {
        for (MessageFilter filter : filters) {
            if (filter.test(message)) {
                return true;
            }
        }
        return false;
    }

    private boolean testNot(Message message) {
        // NOT of first filter only
        return filters.isEmpty() || !filters.get(0).test(message);
    }

    /**
     * Create AND composite
     */
    public static CompositeFilter and(MessageFilter... filters) {
        return new CompositeFilter(Arrays.asList(filters), CombineOperator.AND);
    }

    /**
     * Create OR composite
     */
    public static CompositeFilter or(MessageFilter... filters) {
        return new CompositeFilter(Arrays.asList(filters), CombineOperator.OR);
    }

    /**
     * Create NOT composite
     */
    public static CompositeFilter not(MessageFilter filter) {
        return new CompositeFilter(List.of(filter), CombineOperator.NOT);
    }
}
