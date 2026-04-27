package dev.jentic.examples.behaviors;

import dev.jentic.core.BehaviorType;
import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticBehavior;
import dev.jentic.runtime.JenticRuntime;
import dev.jentic.runtime.agent.BaseAgent;

public class ThrottledExample {
    
    public static void main(String[] args) throws InterruptedException {
        var runtime = JenticRuntime.builder().build();
        runtime.registerAgent(new APICallerAgent());
        runtime.start().join();
        
        Thread.sleep(20_000);
        runtime.stop().join();
    }
}

/**
 * Agent that calls external API with rate limiting
 */
@JenticAgent("api-caller")
class APICallerAgent extends BaseAgent {
    
    public APICallerAgent() {
        super("api-caller", "API Caller Agent");
    }
    
    @Override
    protected void onStart() {
        log.info("API caller started - max 10 calls/minute");
    }
    
    /**
     * Calls external API with rate limiting (max 10 calls per minute)
     * Executes every 2 seconds but respects the rate limit
     */
    @JenticBehavior(
        type = BehaviorType.THROTTLED,
        rateLimit = "10/m",
        interval = "2s"
    )
    private void callExternalAPI() {
        log.info("🌐 Calling external API");
        
        // Simulate API call
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        Message result = Message.builder()
            .topic("api.response")
            .senderId(getAgentId())
            .content("API call successful")
            .build();
        
        getMessageDispatcher().publish(result.topic(), result);
    }
}