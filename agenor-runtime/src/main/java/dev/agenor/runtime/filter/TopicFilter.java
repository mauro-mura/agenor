package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;

import java.util.regex.Pattern;

/**
 * Filter messages by topic patterns
 */
public class TopicFilter implements MessageFilter {

    private final Pattern pattern;

    private TopicFilter(Pattern pattern) {
        this.pattern = pattern;
    }

    @Override
    public boolean test(Message message) {
        return message.topic() != null && pattern.matcher(message.topic()).matches();
    }

    /**
     * Match exact topic
     */
    public static TopicFilter exact(String topic) {
        return new TopicFilter(Pattern.compile(Pattern.quote(topic)));
    }

    /**
     * Match topic prefix
     */
    public static TopicFilter startsWith(String prefix) {
        return new TopicFilter(Pattern.compile("^" + Pattern.quote(prefix) + ".*"));
    }

    /**
     * Match topic suffix
     */
    public static TopicFilter endsWith(String suffix) {
        return new TopicFilter(Pattern.compile(".*" + Pattern.quote(suffix) + "$"));
    }

    /**
     * Match topic with wildcard (* = any characters)
     */
    public static TopicFilter wildcard(String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return new TopicFilter(Pattern.compile(regex));
    }

    /**
     * Match topic with regex
     */
    public static TopicFilter regex(String regex) {
        return new TopicFilter(Pattern.compile(regex));
    }
}
