package dev.jentic.examples.cli;

import java.util.concurrent.CompletableFuture;

import dev.jentic.core.BehaviorType;
import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticBehavior;
import dev.jentic.runtime.agent.BaseAgent;

@JenticAgent(
    value = "logger-agent"
)
public class SystemLoggerAgent extends BaseAgent {

    private int messageCount = 0;

    public SystemLoggerAgent() {
        super("logger-agent", "System Logger");
    }

    @Override
    protected void onStart() {
        // Subscribe to sensor.temperature only
        getMessageDispatcher().subscribeTopic("sensor.temperature", message -> {
        	messageCount++;
        	log.debug("[MSG #{}] {} -> {} : {}",
                    messageCount,
                    message.senderId(),
                    message.topic(),
                    summarize(message.content()));
        	return CompletableFuture.completedFuture(null);
        });
        log.info("System Logger subscribed to all messages");
    }

    @JenticBehavior(
        type = BehaviorType.CYCLIC,
        interval = "30s"
    )
    public void reportStats() {
        log.info("Messages processed: {}", messageCount);
        
        var statsMsg = Message.builder()
                .topic("system.stats")
                .senderId(getAgentId())
                .content(new Stats(messageCount))
                .build();
        getMessageDispatcher().publish(statsMsg.topic(), statsMsg);
    }

    private String summarize(Object content) {
        if (content == null) return "null";
        String s = content.toString();
        return s.length() > 50 ? s.substring(0, 47) + "..." : s;
    }

    public record Stats(int messagesProcessed) {}
}