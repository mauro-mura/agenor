package dev.agenor.examples.discovery;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Agent that monitors system activity and reports status.
 */
@Agent(value = "system-monitor",
             type = "monitor",
             capabilities = {"monitoring", "reporting", "system-health"},
             autoStart = true)
public class MonitoringAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(MonitoringAgent.class);

    private int heartbeatCount = 0;
    private int messagesReceived = 0;

    public MonitoringAgent() {
        super("system-monitor", "System Monitor");
    }

    @Behavior(type = CYCLIC, interval = "8s", autoStart = true)
    public void sendHeartbeat() {
        heartbeatCount++;

        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String status = String.format("System healthy - Beat #%d at %s", heartbeatCount, timestamp);

        Message heartbeat = Message.builder()
            .topic("system.heartbeat")
            .senderId(getAgentId())
            .content(status)
            .header("beat-number", String.valueOf(heartbeatCount))
            .header("timestamp", timestamp)
            .header("system-status", "healthy")
            .build();

        log.info("💓 {}", status);
        getMessageDispatcher().publish(heartbeat);
    }

    @Behavior(type = CYCLIC, interval = "15s", autoStart = true)
    public void reportStatistics() {
        Message report = Message.builder()
            .topic("system.report")
            .senderId(getAgentId())
            .content(String.format("Stats Report: %d heartbeats sent, %d messages received",
                                  heartbeatCount, messagesReceived))
            .header("heartbeats", String.valueOf(heartbeatCount))
            .header("messages", String.valueOf(messagesReceived))
            .build();

        log.info("📊 Statistics: Heartbeats sent: {}, Messages received: {}",
                heartbeatCount, messagesReceived);
        getMessageDispatcher().publish(report);
    }

    @AgenorMessageHandler("system.alert")
    public void handleSystemAlert(Message message) {
        messagesReceived++;
        String alertLevel = message.headers().getOrDefault("level", "UNKNOWN");

        log.warn("🚨 System Alert [{}]: {}", alertLevel, message.content());

        // Acknowledge alert
        Message ack = Message.builder()
            .topic("system.alert.ack")
            .senderId(getAgentId())
            .correlationId(message.id())
            .content("Alert acknowledged by " + getAgentName())
            .header("alert-id", message.id())
            .header("handled-by", getAgentId())
            .build();

        getMessageDispatcher().publish(ack);
    }

    @Override
    protected void onStart() {
        log.info("🔍 System Monitor started - watching system health");
    }

    @Override
    protected void onStop() {
        log.info("🔍 System Monitor stopped - Final stats: {} heartbeats, {} messages",
                heartbeatCount, messagesReceived);
    }
}
