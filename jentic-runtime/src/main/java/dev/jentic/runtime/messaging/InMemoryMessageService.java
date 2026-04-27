package dev.jentic.runtime.messaging;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * In-memory implementation of the deprecated {@link dev.jentic.core.MessageService}.
 *
 * @deprecated since 0.20.0. Use {@link InMemoryMessageDispatcher} instead.
 */
@Deprecated(since = "0.20.0", forRemoval = true)
@SuppressWarnings("deprecation")
public class InMemoryMessageService implements MessageService {
    
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageService.class);
    
    private final Map<String, List<MessageHandler>> topicSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, List<MessageHandler>> receiverSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, PredicateSubscription> predicateSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionInfo> subscriptionRegistry = new ConcurrentHashMap<>();

    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String subscribeToReceiver(String receiverId, MessageHandler handler) {
        if (receiverId == null || receiverId.trim().isEmpty()) {
            throw new IllegalArgumentException("receiverId cannot be null or empty");
        }

        String subscriptionId = "receiver-" + receiverId + "-" + UUID.randomUUID();

        receiverSubscriptions.computeIfAbsent(receiverId, k -> new CopyOnWriteArrayList<>())
                .add(handler);

        subscriptionRegistry.put(subscriptionId, new SubscriptionInfo(
                SubscriptionType.RECEIVER, receiverId, handler
        ));

        log.debug("Subscribed receiver '{}' for direct messages (subscription: {})",
                receiverId, subscriptionId);

        return subscriptionId;
    }

    @Override
    public CompletableFuture<Void> send(Message message) {
        return CompletableFuture.runAsync(() -> {
            log.debug("Sending message: {} to topic: {}", message.id(), message.topic());
            
            // Handle reply messages (with correlation ID)
            if (message.correlationId() != null) {
                CompletableFuture<Message> pendingRequest = pendingRequests.remove(message.correlationId());
                if (pendingRequest != null) {
                    pendingRequest.complete(message);
                    return;
                }
            }

            // Deliver to direct receiver if specified
            if (message.receiverId() != null && !message.receiverId().trim().isEmpty()) {
                deliverToReceiver(message);
            }
            
            // Deliver to topic subscribers
            if (message.topic() != null) {
                deliverToTopicSubscribers(message);
            }
            
            // Deliver to predicate subscribers
            deliverToPredicateSubscribers(message);
            
        }, VIRTUAL_EXECUTOR);
    }
    
    @Override
    public String subscribe(String topic, MessageHandler handler) {
        String subscriptionId = UUID.randomUUID().toString();
        
        topicSubscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                          .add(handler);

        subscriptionRegistry.put(subscriptionId, new SubscriptionInfo(
                SubscriptionType.TOPIC, topic, handler
        ));

        log.debug("Subscribed to topic: {} with subscription ID: {}", topic, subscriptionId);
        return subscriptionId;
    }
    
    @Override
    public String subscribe(Predicate<Message> filter, MessageHandler handler) {
        String subscriptionId = UUID.randomUUID().toString();
        
        predicateSubscriptions.put(subscriptionId, new PredicateSubscription(filter, handler));
        
        log.debug("Subscribed with predicate filter, subscription ID: {}", subscriptionId);
        return subscriptionId;
    }
    
    @Override
    public void unsubscribe(String subscriptionId) {
        if (subscriptionId == null) {
            return;
        }

        // Check registry first
        SubscriptionInfo info = subscriptionRegistry.remove(subscriptionId);
        if (info != null) {
            switch (info.type) {
                case TOPIC -> {
                    List<MessageHandler> handlers = topicSubscriptions.get(info.key);
                    if (handlers != null) {
                        handlers.remove(info.handler);
                        if (handlers.isEmpty()) {
                            topicSubscriptions.remove(info.key);
                        }
                    }
                }
                case RECEIVER -> {
                    List<MessageHandler> handlers = receiverSubscriptions.get(info.key);
                    if (handlers != null) {
                        handlers.remove(info.handler);
                        if (handlers.isEmpty()) {
                            receiverSubscriptions.remove(info.key);
                        }
                    }
                }
            }
            log.debug("Unsubscribed: {} (type: {}, key: {})",
                    subscriptionId, info.type, info.key);
            return;
        }

        // Fallback: try predicate subscriptions
        if (predicateSubscriptions.remove(subscriptionId) != null) {
            log.debug("Unsubscribed from predicate: {}", subscriptionId);
            return;
        }

        log.trace("Subscription not found: {}", subscriptionId);
    }
    
    @Override
    public CompletableFuture<Message> sendAndWait(Message message, long timeout) {
        CompletableFuture<Message> replyFuture = new CompletableFuture<>();
        
        // Store the pending request
        pendingRequests.put(message.id(), replyFuture);
        
        // Send the message
        send(message);
        
        // Set up timeout
        CompletableFuture.delayedExecutor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> {
                if (pendingRequests.remove(message.id()) != null) {
                    replyFuture.completeExceptionally(
                        new TimeoutException("No reply received within " + timeout + "ms"));
                }
            });
        
        return replyFuture;
    }

    /**
     * Deliver message directly to the specified receiver.
     * This is the key method for point-to-point communication.
     */
    private void deliverToReceiver(Message message) {
        String receiverId = message.receiverId();
        List<MessageHandler> handlers = receiverSubscriptions.get(receiverId);

        if (handlers != null && !handlers.isEmpty()) {
            log.trace("Delivering to receiver '{}' ({} handlers)", receiverId, handlers.size());

            for (MessageHandler handler : handlers) {
                // Use virtual thread for each handler (same as original)
                Thread.startVirtualThread(() -> {
                    try {
                        handler.handle(message).join();
                    } catch (Exception e) {
                        log.error("Error handling direct message for receiver '{}': {}",
                                receiverId, e.getMessage(), e);
                    }
                });
            }
        } else {
            log.trace("No handlers found for receiver: {}", receiverId);
        }
    }

    private void deliverToTopicSubscribers(Message message) {
        List<MessageHandler> handlers = topicSubscriptions.get(message.topic());
        if (handlers != null && !handlers.isEmpty()) {
            for (MessageHandler handler : handlers) {
                // Use virtual thread for each handler
                Thread.startVirtualThread(() -> {
                    try {
                        handler.handle(message).join();
                    } catch (Exception e) {
                        log.error("Error handling message: {} by handler: {}", message.id(), handler, e);
                    }
                });
            }
        }
    }
    
    private void deliverToPredicateSubscribers(Message message) {
        for (PredicateSubscription subscription : predicateSubscriptions.values()) {
            if (subscription.filter().test(message)) {
                // Use virtual thread for each handler
                Thread.startVirtualThread(() -> {
                    try {
                        subscription.handler().handle(message).join();
                    } catch (Exception e) {
                        log.error("Error handling message: {} by predicate handler", message.id(), e);
                    }
                });
            }
        }
    }

    /**
     * Get statistics about subscriptions (useful for debugging and testing)
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "topicSubscriptions", topicSubscriptions.size(),
                "receiverSubscriptions", receiverSubscriptions.size(),
                "predicateSubscriptions", predicateSubscriptions.size(),
                "pendingRequests", pendingRequests.size()
        );
    }

    private record PredicateSubscription(Predicate<Message> filter, MessageHandler handler) {}

    /**
     * Subscription type enum
     */
    private enum SubscriptionType {
        TOPIC,
        RECEIVER
    }

    /**
     * Subscription information for tracking and unsubscribe
     */
    private record SubscriptionInfo(
            SubscriptionType type,
            String key,  // topic name or receiverId
            MessageHandler handler
    ) {}
}