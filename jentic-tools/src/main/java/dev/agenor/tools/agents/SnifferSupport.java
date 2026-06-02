package dev.agenor.tools.agents;

import dev.agenor.core.filter.MessageFilter;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.tools.history.MessageHistoryService;
import dev.agenor.tools.history.MessageHistoryService.StoredMessage;

import java.util.List;
import java.util.Optional;

/**
 * Utility class for accessing MessageSnifferAgent from runtime.
 *
 * <p>Provides convenient static methods for common sniffer operations.
 *
 * <p>Usage:
 * <pre>{@code
 * // Register sniffer
 * SnifferSupport.register(runtime);
 *
 * // Or with custom filter
 * SnifferSupport.register(runtime, TopicFilter.startsWith("order."), 500);
 *
 * // Query messages
 * List<StoredMessage> recent = SnifferSupport.getRecent(runtime, 50);
 * List<StoredMessage> orders = SnifferSupport.findByTopic(runtime, "order.*");
 * }</pre>
 *
 * @since 0.4.0
 */
public final class SnifferSupport {

    private SnifferSupport() {
        // Utility class
    }

    /**
     * Registers a default sniffer agent with the runtime.
     *
     * @param runtime the runtime to register with
     * @return the registered sniffer
     */
    public static MessageSnifferAgent register(JenticRuntime runtime) {
        return register(runtime, MessageFilter.acceptAll(),
                       MessageSnifferAgent.DEFAULT_HISTORY_SIZE);
    }

    /**
     * Registers a sniffer with custom configuration.
     *
     * @param runtime the runtime
     * @param filter message filter
     * @param historySize max messages to retain
     * @return the registered sniffer
     */
    public static MessageSnifferAgent register(JenticRuntime runtime,
                                                MessageFilter filter,
                                                int historySize) {
        MessageSnifferAgent sniffer = new MessageSnifferAgent(filter, historySize);
        runtime.registerAgent(sniffer);
        return sniffer;
    }

    /**
     * Registers a sniffer with shared history (for WebConsole integration).
     *
     * @param runtime the runtime
     * @param history shared history service
     * @return the registered sniffer
     */
    public static MessageSnifferAgent register(JenticRuntime runtime,
                                                MessageHistoryService history) {
        return register(runtime, MessageFilter.acceptAll(), history);
    }

    /**
     * Registers a sniffer with filter and shared history.
     *
     * @param runtime the runtime
     * @param filter message filter
     * @param history shared history service
     * @return the registered sniffer
     */
    public static MessageSnifferAgent register(JenticRuntime runtime,
                                                MessageFilter filter,
                                                MessageHistoryService history) {
        MessageSnifferAgent sniffer = new MessageSnifferAgent(filter, history);
        runtime.registerAgent(sniffer);
        return sniffer;
    }

    /**
     * Gets the sniffer agent from runtime.
     *
     * @param runtime the runtime
     * @return optional sniffer if registered
     */
    public static Optional<MessageSnifferAgent> get(JenticRuntime runtime) {
        return runtime.getAgent(MessageSnifferAgent.AGENT_ID)
            .filter(a -> a instanceof MessageSnifferAgent)
            .map(a -> (MessageSnifferAgent) a);
    }

    /**
     * Gets recent messages from sniffer.
     *
     * @param runtime the runtime
     * @param count max messages
     * @return messages or empty list if no sniffer
     */
    public static List<StoredMessage> getRecent(JenticRuntime runtime, int count) {
        return get(runtime)
            .map(s -> s.getRecent(count))
            .orElse(List.of());
    }

    /**
     * Finds messages by topic pattern.
     *
     * @param runtime the runtime
     * @param topicPattern wildcard pattern
     * @return matching messages
     */
    public static List<StoredMessage> findByTopic(JenticRuntime runtime, String topicPattern) {
        return get(runtime)
            .map(s -> s.findByTopic(topicPattern))
            .orElse(List.of());
    }

    /**
     * Finds messages by sender.
     *
     * @param runtime the runtime
     * @param senderId sender agent ID
     * @return messages from sender
     */
    public static List<StoredMessage> findBySender(JenticRuntime runtime, String senderId) {
        return get(runtime)
            .map(s -> s.findBySender(senderId))
            .orElse(List.of());
    }

    /**
     * Gets message count from sniffer.
     *
     * @param runtime the runtime
     * @return message count or 0 if no sniffer
     */
    public static int getMessageCount(JenticRuntime runtime) {
        return get(runtime)
            .map(MessageSnifferAgent::getMessageCount)
            .orElse(0);
    }

    /**
     * Clears sniffer history.
     *
     * @param runtime the runtime
     */
    public static void clear(JenticRuntime runtime) {
        get(runtime).ifPresent(MessageSnifferAgent::clear);
    }

    /**
     * Checks if sniffer is registered and capturing.
     *
     * @param runtime the runtime
     * @return true if sniffer is active
     */
    public static boolean isActive(JenticRuntime runtime) {
        return get(runtime)
            .map(MessageSnifferAgent::isCapturing)
            .orElse(false);
    }
}
