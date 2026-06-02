package dev.agenor.examples.behaviors;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.annotations.JenticBehavior;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;

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

        getMessageDispatcher().publish(result);
    }
}
