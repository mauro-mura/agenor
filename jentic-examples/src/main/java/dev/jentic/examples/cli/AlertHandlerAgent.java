package dev.jentic.examples.cli;

import java.util.concurrent.CompletableFuture;

import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.messaging.FilterableSubscriber;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.filter.TopicFilter;

@JenticAgent(
    value = "alert-agent"
)
public class AlertHandlerAgent extends BaseAgent {

    public AlertHandlerAgent() {
        super("alert-agent", "Alert Handler");
    }

    @Override
    protected void onStart() {
        // Subscribe to alert topics
        ((FilterableSubscriber) getMessageDispatcher()).subscribeFiltered(TopicFilter.wildcard("sensor.alert.*"), message -> {
        	handleAlert(message);
        	return CompletableFuture.completedFuture(null);
        });
        log.info("Alert Handler subscribed to alert.*");
    }

    private void handleAlert(Message message) {
        String severity = message.headers().getOrDefault("severity", "INFO");
        log.warn("[ALERT][{}] {}: {}", severity, message.topic(), message.content());

        // Acknowledge the alert
        var ackMsg = Message.builder()
                .topic("notification.processed")
                .senderId(getAgentId())
                .content("Processed: " + message.topic())
                .correlationId(message.id())
                .build();
        getMessageDispatcher().publish(ackMsg);
    }
}