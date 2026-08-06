package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Filter messages by header values
 */
public class HeaderFilter implements MessageFilter {

    private final String key;
    private final Predicate<String> valuePredicate;

    private HeaderFilter(String key, Predicate<String> valuePredicate) {
        this.key = key;
        this.valuePredicate = valuePredicate;
    }

    @Override
    public boolean test(Message message) {
        String value = message.headers().get(key);
        return value != null && valuePredicate.test(value);
    }

    /**
     * Header must exist
     */
    public static HeaderFilter exists(String key) {
        return new HeaderFilter(key, v -> true);
    }

    /**
     * Header must equal value
     */
    public static HeaderFilter equals(String key, String value) {
        return new HeaderFilter(key, v -> value.equals(v));
    }

    /**
     * Header must match regex
     */
    public static HeaderFilter matches(String key, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return new HeaderFilter(key, v -> pattern.matcher(v).matches());
    }

    /**
     * Header must be in set of values
     */
    public static HeaderFilter in(String key, String... values) {
        List<String> allowed = Arrays.asList(values);
        return new HeaderFilter(key, allowed::contains);
    }

    /**
     * Header must start with prefix
     */
    public static HeaderFilter startsWith(String key, String prefix) {
        return new HeaderFilter(key, v -> v.startsWith(prefix));
    }
}
