package dev.agenor.examples;

import dev.agenor.core.Message;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.CyclicBehavior;
import dev.agenor.runtime.behavior.OneShotBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Simple example without annotations, showing programmatic agent creation.
 */
public class SimpleExample {

    private static final Logger log = LoggerFactory.getLogger(SimpleExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Jentic Simple Example (Programmatic) ===");

        AgenorRuntime runtime = AgenorRuntime.builder().build();

        // Create agents programmatically
        SimpleAgent agent1 = new SimpleAgent("agent-1", "First Agent");
        SimpleAgent agent2 = new SimpleAgent("agent-2", "Second Agent");

        // Register agents
        runtime.registerAgent(agent1);
        runtime.registerAgent(agent2);

        // Start runtime
        runtime.start().join();

        log.info("Simple example started");

        // Run for 15 seconds
        Thread.sleep(15_000);

        // Stop runtime
        log.info("Stopping simple example...");
        runtime.stop().join();

        log.info("=== Simple Example completed ===");
    }

    /**
     * Simple agent that demonstrates programmatic behavior creation
     */
    public static class SimpleAgent extends BaseAgent {

        public SimpleAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }

        @Override
        protected void onStart() {
            log.info("[{}] Starting up", getAgentName());

            // Add one-shot behavior
            addBehavior(OneShotBehavior.from("startup", () -> {
                log.info("[{}] Startup behavior executed", getAgentName());

                Message announcement = Message.builder()
                    .topic("agent.announcement")
                    .senderId(getAgentId())
                    .content(getAgentName() + " has started up")
                    .build();

                getMessageDispatcher().publish(announcement);
            }));

            // Add cyclic behavior
            addBehavior(CyclicBehavior.from("heartbeat", Duration.ofSeconds(5), () -> {
                Message heartbeat = Message.builder()
                    .topic("agent.heartbeat")
                    .senderId(getAgentId())
                    .content(getAgentName() + " is alive at " + java.time.LocalTime.now())
                    .build();

                log.info("[{}] Sending heartbeat", getAgentName());
                getMessageDispatcher().publish(heartbeat);
            }));

            // Subscribe to messages
            getMessageDispatcher().subscribeTopic("agent.announcement", message -> {
                if (!getAgentId().equals(message.senderId())) {
                    log.info("[{}] Heard announcement: {}", getAgentName(), message.content());
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });

            getMessageDispatcher().subscribeTopic("agent.heartbeat", message -> {
                if (!getAgentId().equals(message.senderId())) {
                    log.debug("[{}] Heard heartbeat: {}", getAgentName(), message.content());
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
        }

        @Override
        protected void onStop() {
            log.info("[{}] Shutting down", getAgentName());
        }
    }
}
