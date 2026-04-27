package dev.jentic.examples.ecommerce;

import dev.jentic.core.*;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@JenticAgent(
        value = "notification-service",
        type = "Notification",
        capabilities = {"email", "sms"}
)
public class NotificationServiceAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceAgent.class);

    public NotificationServiceAgent(AgentDirectory agentDirectory,
                                    BehaviorScheduler behaviorScheduler) {
        this.agentDirectory = agentDirectory;
        this.behaviorScheduler = behaviorScheduler;
    }

    @JenticMessageHandler("order-notification")
    public void handleNotification(Message message) {
        Map<String, String> content = message.getContent(Map.class);

        String customerId = content.get("customerId");
        String text = content.get("message");

        log.info("📧 Sending notification to {}: {}", customerId, text);

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("   ✉️  Notification sent");
    }
}