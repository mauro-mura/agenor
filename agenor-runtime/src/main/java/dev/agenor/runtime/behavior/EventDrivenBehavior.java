package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Behavior that responds to messages/events.
 * Does not execute on a schedule but reacts to incoming messages.
 */
public abstract class EventDrivenBehavior extends BaseBehavior implements MessageHandler {

    private final String topic;

    protected EventDrivenBehavior(String topic) {
        super(BehaviorType.EVENT_DRIVEN);
        this.topic = topic;
    }

    protected EventDrivenBehavior(String behaviorId, String topic) {
        super(behaviorId, BehaviorType.EVENT_DRIVEN, null);
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }

    @Override
    protected void action() {
        // Event-driven behaviors don't execute on schedule
        // They respond to handle() method calls
    }

    @Override
    public CompletableFuture<Void> handle(Message message) {
        if (!isActive()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                log.trace("Handling message in behavior: {} for topic: {}", getBehaviorId(), topic);
                handleMessage(message);
            } catch (Exception e) {
                log.error("Error handling message in behavior: {}", getBehaviorId(), e);
                onError(e);
            }
        });
    }

    /**
     * Handle the received message.
     * Must be implemented by subclasses.
     */
    protected abstract void handleMessage(Message message);

    /**
     * Create an event-driven behavior from a message handler
     */
    public static EventDrivenBehavior from(String topic, MessageHandler handler) {
        return new EventDrivenBehavior(topic) {
            @Override
            protected void handleMessage(Message message) {
                try {
                    handler.handle(message).join();
                } catch (Exception e) {
                    throw new RuntimeException("Error in message handler", e);
                }
            }
        };
    }
}
