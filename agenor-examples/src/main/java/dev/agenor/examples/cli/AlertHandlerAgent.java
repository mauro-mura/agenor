package dev.agenor.examples.cli;

import java.util.concurrent.CompletableFuture;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.messaging.FilterableSubscriber;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.filter.TopicFilter;

@Agent(
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
