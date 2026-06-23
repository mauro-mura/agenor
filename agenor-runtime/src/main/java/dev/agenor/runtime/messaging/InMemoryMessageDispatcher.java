package dev.agenor.runtime.messaging;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.exceptions.AgentNotFoundException;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.core.messaging.FilterableSubscriber;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * In-memory implementation of {@link MessageDispatcher} and {@link FilterableSubscriber}.
 *
 * <p>Uses virtual threads for message delivery. Point-to-point routing via
 * {@link #sendTo} resolves the recipient through an injected {@link AgentResolver};
 * for {@code "local"} endpoints, delivery is direct to the subscriber map with no
 * network hop. If the agent is not registered, the future completes exceptionally
 * with {@link AgentNotFoundException}.
 *
 * <p>This is the primary messaging implementation for single-JVM deployments.
 *
 * @since 0.20.0
 */
public class InMemoryMessageDispatcher implements MessageDispatcher, FilterableSubscriber {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageDispatcher.class);

    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final AgentResolver agentResolver;
    private volatile AgenorTelemetry telemetry;

    // Key: topic name
    private final Map<String, List<MessageHandler>> topicSubscriptions = new ConcurrentHashMap<>();
    // Key: localAgentId
    private final Map<String, List<MessageHandler>> receiverSubscriptions = new ConcurrentHashMap<>();
    // Key: subscriptionId
    private final Map<String, PredicateEntry> predicateSubscriptions = new ConcurrentHashMap<>();
    // Tracks subscription type + key for clean unsubscribe
    private final Map<String, SubscriptionEntry> subscriptionRegistry = new ConcurrentHashMap<>();

    /**
     * Creates a dispatcher backed by the given agent resolver with noop telemetry.
     *
     * @param agentResolver resolver used to translate agent IDs to transport endpoints; must not be null
     */
    public InMemoryMessageDispatcher(AgentResolver agentResolver) {
        this(agentResolver, AgenorTelemetry.noop());
    }

    /**
     * Creates a dispatcher backed by the given agent resolver and telemetry instance.
     *
     * @param agentResolver resolver used to translate agent IDs to transport endpoints; must not be null
     * @param telemetry     telemetry instance for {@code message.send} spans; null treated as noop
     */
    public InMemoryMessageDispatcher(AgentResolver agentResolver, AgenorTelemetry telemetry) {
        this.agentResolver = Objects.requireNonNull(agentResolver, "agentResolver");
        this.telemetry = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    /**
     * Updates the telemetry instance after construction.
     *
     * @param telemetry the telemetry instance; null treated as noop
     */
    public void setTelemetry(AgenorTelemetry telemetry) {
        this.telemetry = telemetry != null ? telemetry : AgenorTelemetry.noop();
    }

    // -------------------------------------------------------------------------
    // TopicPublisher
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> publish(Message msg) {
        Objects.requireNonNull(msg, "msg");
        var topic = msg.topic();
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("msg.topic must not be null or empty");
        return CompletableFuture.runAsync(() -> {
            Span span = telemetry.spanBuilder("message.send")
                    .setAttribute("message.topic", topic)
                    .setAttribute("message.id", msg.id() != null ? msg.id() : "")
                    .setAttribute("agent.sender", msg.senderId() != null ? msg.senderId() : "")
                    .startSpan();
            try {
                log.debug("Publishing to topic '{}': message {}", topic, msg.id());
                deliverToTopic(topic, msg);
                deliverToPredicates(msg);
                span.setStatus(SpanStatus.OK);
            } catch (Exception e) {
                span.recordException(e).setStatus(SpanStatus.ERROR);
                throw e;
            } finally {
                span.end();
            }
        }, VIRTUAL_EXECUTOR);
    }

    // -------------------------------------------------------------------------
    // TopicSubscriber
    // -------------------------------------------------------------------------

    @Override
    public Subscription subscribeTopic(String topic, MessageHandler handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        if (topic.isBlank()) throw new IllegalArgumentException("topic must not be empty");

        var id = "topic-" + UUID.randomUUID();
        topicSubscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        subscriptionRegistry.put(id, new SubscriptionEntry(SubscriptionKind.TOPIC, topic, handler));

        log.debug("Subscribed to topic '{}' (subscription: {})", topic, id);
        return Subscription.of(id, () -> cancelSubscription(id));
    }

    // -------------------------------------------------------------------------
    // DirectMessenger
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> sendTo(Message msg) {
        Objects.requireNonNull(msg, "msg");
        var recipientAgentId = msg.receiverId();
        if (recipientAgentId == null || recipientAgentId.isBlank())
            throw new IllegalArgumentException("msg.receiverId must not be null or blank");

        Span span = telemetry.spanBuilder("message.send")
                .setAttribute("message.recipient", recipientAgentId)
                .setAttribute("message.id", msg.id() != null ? msg.id() : "")
                .setAttribute("agent.sender", msg.senderId() != null ? msg.senderId() : "")
                .startSpan();

        return agentResolver.resolveEndpoint(recipientAgentId)
                .thenCompose(opt -> {
                    if (opt.isEmpty()) {
                        // Fall back to a local receiver subscription registered via subscribeRecipient().
                        // This covers ephemeral callers (e.g. AgenorA2AAdapter.sendInternal) that
                        // register a temporary subscriber without being a registered agent.
                        if (receiverSubscriptions.containsKey(recipientAgentId)) {
                            return CompletableFuture.runAsync(() -> {
                                try {
                                    log.debug("Delivering direct message to ephemeral receiver '{}': {}",
                                            recipientAgentId, msg.id());
                                    deliverToReceiver(recipientAgentId, msg);
                                    deliverToPredicates(msg);
                                    span.setStatus(SpanStatus.OK);
                                } catch (Exception e) {
                                    span.recordException(e).setStatus(SpanStatus.ERROR);
                                    throw e;
                                } finally {
                                    span.end();
                                }
                            }, VIRTUAL_EXECUTOR);
                        }
                        var ex = new AgentNotFoundException(recipientAgentId);
                        span.recordException(ex).setStatus(SpanStatus.ERROR);
                        span.end();
                        return CompletableFuture.failedFuture(ex);
                    }
                    AgentEndpoint endpoint = opt.get();
                    span.setAttribute("endpoint.type", endpoint.transportType());
                    if ("local".equals(endpoint.transportType())) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                log.debug("Delivering direct message to local agent '{}': {}",
                                        recipientAgentId, msg.id());
                                deliverToReceiver(recipientAgentId, msg);
                                deliverToPredicates(msg);
                                span.setStatus(SpanStatus.OK);
                            } catch (Exception e) {
                                span.recordException(e).setStatus(SpanStatus.ERROR);
                                throw e;
                            } finally {
                                span.end();
                            }
                        }, VIRTUAL_EXECUTOR);
                    }
                    // Remote transport: not yet implemented (added in Item 3+)
                    var ex = new UnsupportedOperationException(
                            "Non-local transport not yet supported: " + endpoint.transportType());
                    span.recordException(ex).setStatus(SpanStatus.ERROR);
                    span.end();
                    return CompletableFuture.failedFuture(ex);
                })
                .exceptionally(ex -> {
                    // Span already ended in error paths above; only close if still open in
                    // unexpected paths (e.g. resolveEndpoint itself throws)
                    span.end();
                    if (ex instanceof RuntimeException re) throw re;
                    throw new RuntimeException(ex);
                });
    }

    // -------------------------------------------------------------------------
    // DirectReceiver
    // -------------------------------------------------------------------------

    @Override
    public Subscription subscribeRecipient(String localAgentId, MessageHandler handler) {
        Objects.requireNonNull(localAgentId, "localAgentId");
        Objects.requireNonNull(handler, "handler");
        if (localAgentId.isBlank()) throw new IllegalArgumentException("localAgentId must not be empty");

        var id = "receiver-" + localAgentId + "-" + UUID.randomUUID();
        receiverSubscriptions.computeIfAbsent(localAgentId, k -> new CopyOnWriteArrayList<>()).add(handler);
        subscriptionRegistry.put(id, new SubscriptionEntry(SubscriptionKind.RECEIVER, localAgentId, handler));

        log.debug("Subscribed receiver '{}' for direct messages (subscription: {})", localAgentId, id);
        return Subscription.of(id, () -> cancelSubscription(id));
    }

    // -------------------------------------------------------------------------
    // FilterableSubscriber
    // -------------------------------------------------------------------------

    @Override
    public Subscription subscribeFiltered(Predicate<Message> filter, MessageHandler handler) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(handler, "handler");

        var id = "predicate-" + UUID.randomUUID();
        predicateSubscriptions.put(id, new PredicateEntry(filter, handler));

        log.debug("Subscribed with predicate filter (subscription: {})", id);
        return Subscription.of(id, () -> cancelSubscription(id));
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of subscription counts for debugging and monitoring.
     *
     * @return map of metric names to counts
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "topicSubscriptions", topicSubscriptions.size(),
                "receiverSubscriptions", receiverSubscriptions.size(),
                "predicateSubscriptions", predicateSubscriptions.size()
        );
    }

    // -------------------------------------------------------------------------
    // Internal delivery helpers
    // -------------------------------------------------------------------------

    private void deliverToTopic(String topic, Message msg) {
        var handlers = topicSubscriptions.get(topic);
        if (handlers == null || handlers.isEmpty()) {
            log.trace("No topic subscribers for '{}'", topic);
            return;
        }
        for (var handler : handlers) {
            Thread.startVirtualThread(() -> {
                try {
                    handler.handle(msg).join();
                } catch (Exception e) {
                    log.error("Error handling topic '{}' message {}: {}", topic, msg.id(), e.getMessage(), e);
                }
            });
        }
    }

    private void deliverToReceiver(String agentId, Message msg) {
        var handlers = receiverSubscriptions.get(agentId);
        if (handlers == null || handlers.isEmpty()) {
            log.trace("No receiver subscriptions for agent '{}'", agentId);
            return;
        }
        for (var handler : handlers) {
            Thread.startVirtualThread(() -> {
                try {
                    handler.handle(msg).join();
                } catch (Exception e) {
                    log.error("Error handling direct message for agent '{}': {}", agentId, e.getMessage(), e);
                }
            });
        }
    }

    private void deliverToPredicates(Message msg) {
        for (var entry : predicateSubscriptions.values()) {
            if (entry.filter().test(msg)) {
                Thread.startVirtualThread(() -> {
                    try {
                        entry.handler().handle(msg).join();
                    } catch (Exception e) {
                        log.error("Error handling message {} via predicate subscription: {}",
                                msg.id(), e.getMessage(), e);
                    }
                });
            }
        }
    }

    private void cancelSubscription(String id) {
        // Check registry
        var entry = subscriptionRegistry.remove(id);
        if (entry != null) {
            switch (entry.kind()) {
                case TOPIC -> {
                    var handlers = topicSubscriptions.get(entry.key());
                    if (handlers != null) {
                        handlers.remove(entry.handler());
                        if (handlers.isEmpty()) topicSubscriptions.remove(entry.key());
                    }
                }
                case RECEIVER -> {
                    var handlers = receiverSubscriptions.get(entry.key());
                    if (handlers != null) {
                        handlers.remove(entry.handler());
                        if (handlers.isEmpty()) receiverSubscriptions.remove(entry.key());
                    }
                }
            }
            log.debug("Cancelled subscription {} (kind: {}, key: {})", id, entry.kind(), entry.key());
            return;
        }
        // Predicate subscription
        if (predicateSubscriptions.remove(id) != null) {
            log.debug("Cancelled predicate subscription {}", id);
            return;
        }
        log.trace("Subscription not found during cancel: {}", id);
    }

    // -------------------------------------------------------------------------
    // Private records
    // -------------------------------------------------------------------------

    private record PredicateEntry(Predicate<Message> filter, MessageHandler handler) {}

    private enum SubscriptionKind { TOPIC, RECEIVER }

    private record SubscriptionEntry(SubscriptionKind kind, String key, MessageHandler handler) {}
}
