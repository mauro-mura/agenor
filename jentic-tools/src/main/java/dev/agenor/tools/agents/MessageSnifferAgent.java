package dev.agenor.tools.agents;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.console.ConsoleEventListener;
import dev.agenor.core.filter.MessageFilter;
import dev.agenor.core.messaging.FilterableSubscriber;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.tools.history.MessageHistoryService;
import dev.agenor.tools.history.MessageHistoryService.StoredMessage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * System agent that captures and stores messages for monitoring purposes.
 *
 * <p>The sniffer subscribes to messages matching a configurable filter and
 * stores them in a ring buffer for later retrieval via CLI or WebConsole.
 *
 * <p>For live streaming via WebSocket, set a {@link ConsoleEventListener}:
 * <pre>{@code
 * sniffer.setEventListener(console.getWebSocketHandler());
 * }</pre>
 *
 * <p>Usage:
 * <pre>{@code
 * // Capture all messages
 * var sniffer = new MessageSnifferAgent();
 * runtime.registerAgent(sniffer);
 *
 * // Capture only order-related messages
 * var filter = TopicFilter.startsWith("order.");
 * var sniffer = new MessageSnifferAgent(filter, 1000);
 * runtime.registerAgent(sniffer);
 *
 * // Query captured messages
 * List<StoredMessage> recent = sniffer.getRecent(50);
 * List<StoredMessage> orders = sniffer.findByTopic("order.*");
 * }</pre>
 *
 * @since 0.4.0
 */
@JenticAgent(
    value = "message-sniffer",
    type = "system",
    capabilities = {"monitoring", "message-history", "debugging"},
    autoStart = false
)
public class MessageSnifferAgent extends BaseAgent {

    public static final String AGENT_ID = "message-sniffer";
    public static final String AGENT_NAME = "Message Sniffer";
    public static final int DEFAULT_HISTORY_SIZE = 1000;

    private final MessageHistoryService history;
    private final MessageFilter filter;
    private volatile Subscription subscription;
    private volatile ConsoleEventListener eventListener;

    /**
     * Creates a sniffer that captures all messages with default history size.
     */
    public MessageSnifferAgent() {
        this(MessageFilter.acceptAll(), DEFAULT_HISTORY_SIZE);
    }

    /**
     * Creates a sniffer with custom history size, capturing all messages.
     *
     * @param historySize max messages to retain
     */
    public MessageSnifferAgent(int historySize) {
        this(MessageFilter.acceptAll(), historySize);
    }

    /**
     * Creates a sniffer with custom filter and default history size.
     *
     * @param filter message filter to apply
     */
    public MessageSnifferAgent(MessageFilter filter) {
        this(filter, DEFAULT_HISTORY_SIZE);
    }

    /**
     * Creates a sniffer with custom filter and history size.
     *
     * @param filter message filter to apply
     * @param historySize max messages to retain
     */
    public MessageSnifferAgent(MessageFilter filter, int historySize) {
        super(AGENT_ID, AGENT_NAME);
        this.filter = Objects.requireNonNull(filter, "filter cannot be null");
        this.history = new MessageHistoryService(historySize);
    }

    /**
     * Creates a sniffer with external history service.
     *
     * <p>Use this when sharing history with WebConsole.
     *
     * @param filter message filter to apply
     * @param history external history service
     */
    public MessageSnifferAgent(MessageFilter filter, MessageHistoryService history) {
        super(AGENT_ID, AGENT_NAME);
        this.filter = Objects.requireNonNull(filter, "filter cannot be null");
        this.history = Objects.requireNonNull(history, "history cannot be null");
    }

    @Override
    protected void onStart() {
        if (getMessageDispatcher() instanceof FilterableSubscriber fs) {
            subscription = fs.subscribeFiltered(filter, this::captureMessage);
        } else {
            log.warn("MessageDispatcher does not support predicate filtering — sniffer disabled");
        }
        log.info("Sniffer started - filter: {}, capacity: {}",
                filter.getClass().getSimpleName(), history.getMaxSize());
    }

    @Override
    protected void onStop() {
        Subscription sub = this.subscription;
        if (sub != null) {
            sub.unsubscribe();
            this.subscription = null;
        }
        log.info("Sniffer stopped - captured {} messages", history.size());
    }

    private CompletableFuture<Void> captureMessage(Message message) {
        history.store(message);

        // Notify WebSocket listener for live streaming
        ConsoleEventListener listener = this.eventListener;
        if (listener != null) {
            try {
                listener.onMessageSent(
                    message.id(),
                    message.topic(),
                    message.senderId()
                );
            } catch (Exception e) {
                log.warn("Failed to notify event listener: {}", e.getMessage());
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Sets the event listener for WebSocket notifications.
     *
     * <p>When set, the sniffer will notify the listener for each captured
     * message, enabling live streaming via WebSocket.
     *
     * @param eventListener the listener, or null to disable notifications
     */
    public void setEventListener(ConsoleEventListener eventListener) {
        this.eventListener = eventListener;
        log.debug("Event listener set: {}",
                eventListener != null ? eventListener.getClass().getSimpleName() : "null");
    }

    /**
     * Gets the current event listener.
     */
    public ConsoleEventListener getEventListener() {
        return eventListener;
    }

    // =========================================================================
    // Query API
    // =========================================================================

    /**
     * Gets the underlying history service.
     */
    public MessageHistoryService getHistory() {
        return history;
    }

    /**
     * Gets the most recent messages.
     *
     * @param count max messages to return
     * @return list of stored messages, newest first
     */
    public List<StoredMessage> getRecent(int count) {
        return history.getRecent(count);
    }

    /**
     * Finds messages by topic pattern.
     *
     * @param topicPattern wildcard pattern (e.g., "order.*")
     * @return matching messages
     */
    public List<StoredMessage> findByTopic(String topicPattern) {
        return history.findByTopicPattern(topicPattern);
    }

    /**
     * Finds messages by sender.
     *
     * @param senderId the sender agent ID
     * @return messages from this sender
     */
    public List<StoredMessage> findBySender(String senderId) {
        return history.findBySender(senderId);
    }

    /**
     * Gets the current message count.
     */
    public int getMessageCount() {
        return history.size();
    }

    /**
     * Clears all stored messages.
     */
    public void clear() {
        history.clear();
        log.debug("Sniffer history cleared");
    }

    /**
     * Gets the active filter.
     */
    public MessageFilter getFilter() {
        return filter;
    }

    /**
     * Checks if sniffer is actively capturing.
     */
    public boolean isCapturing() {
        return subscription != null && isRunning();
    }
}
