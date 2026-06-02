package dev.agenor.examples;

import dev.agenor.core.AgentQuery;
import dev.agenor.core.Message;
import dev.agenor.core.PageRequest;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.annotations.JenticBehavior;
import dev.agenor.core.annotations.JenticMessageHandler;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Example demonstrating two agents communicating via messages.
 * PingAgent sends periodic ping messages, PongAgent responds with pong messages.
 *
 */
public class PingPongExample {

    private static final Logger log = LoggerFactory.getLogger(PingPongExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Jentic Ping-Pong Example ===");

        JenticRuntime runtime = JenticRuntime.builder().build();

        runtime.registerAgent(new PingAgent());
        runtime.registerAgent(new PongAgent());

        runtime.start().join();

        log.info("Runtime started with {} agents", runtime.getAgents().size());
        runtime.getAgents().forEach(agent ->
            log.info("  - {} ({})", agent.getAgentName(), agent.getAgentId()));

        runtime.getAgentDirectory()
                .findAgents(AgentQuery.all(), PageRequest.first(100))
                .thenAccept(page -> {
                    log.info("Agents in directory: {}", page.totalElements());
                    page.content().forEach(descriptor ->
                        log.info("  - {} [{}]", descriptor.agentId(), descriptor.status()));
                }).join();

        Thread.sleep(20_000);

        log.info("Stopping runtime...");
        runtime.stop().join();

        log.info("=== Example completed ===");
    }

    // =========================================================================
    // AGENTS
    // =========================================================================

    /** Sends periodic ping messages and stops after receiving 5 pongs. */
    @JenticAgent(value = "ping-agent",
                 type = "example",
                 capabilities = {"ping", "messaging"},
                 autoStart = true)
    public static class PingAgent extends BaseAgent {

        private int pingCount = 0;
        private int pongReceived = 0;

        public PingAgent() {
            super("ping-agent", "Ping Agent");
        }

        @JenticBehavior(type = CYCLIC, interval = "3s", autoStart = true)
        public void sendPing() {
            pingCount++;
            Message pingMessage = Message.builder()
                .topic("ping")
                .senderId(getAgentId())
                .content("Ping #" + pingCount + " from " + getAgentName())
                .header("ping-number", String.valueOf(pingCount))
                .build();
            log.info("Sending: {}", pingMessage.content());
            getMessageDispatcher().publish(pingMessage);
        }

        @JenticMessageHandler("pong")
        public void handlePong(Message message) {
            pongReceived++;
            log.info("Received: {} (Total pongs received: {})", message.content(), pongReceived);
            if (pongReceived >= 5) {
                log.info("Received 5 pongs, stopping ping agent");
                stop();
            }
        }

        @Override
        protected void onStart() {
            log.info("Ping Agent is ready to start pinging!");
        }

        @Override
        protected void onStop() {
            log.info("Ping Agent stopped. Sent {} pings, received {} pongs", pingCount, pongReceived);
        }
    }

    /** Responds to every ping with a pong addressed back to the sender. */
    @JenticAgent(value = "pong-agent",
                 type = "example",
                 capabilities = {"pong", "messaging"},
                 autoStart = true)
    public static class PongAgent extends BaseAgent {

        private int pingReceived = 0;
        private int pongSent = 0;

        public PongAgent() {
            super("pong-agent", "Pong Agent");
        }

        @JenticMessageHandler("ping")
        public void handlePing(Message message) {
            pingReceived++;
            String pingNumber = message.headers().get("ping-number");
            log.info("Received: {} (Ping #{}, Total pings received: {})",
                    message.content(), pingNumber, pingReceived);
            sendPong(message.senderId(), pingNumber);
        }

        private void sendPong(String originalSender, String pingNumber) {
            pongSent++;
            Message pongMessage = Message.builder()
                .topic("pong")
                .senderId(getAgentId())
                .receiverId(originalSender)
                .content("Pong #" + pongSent + " from " + getAgentName()
                        + " (responding to ping #" + pingNumber + ")")
                .header("pong-number", String.valueOf(pongSent))
                .header("original-ping", pingNumber)
                .build();
            log.info("Sending: {}", pongMessage.content());
            getMessageDispatcher().publish(pongMessage);
        }

        @Override
        protected void onStart() {
            log.info("Pong Agent is ready to respond to pings!");
        }

        @Override
        protected void onStop() {
            log.info("Pong Agent stopped. Received {} pings, sent {} pongs", pingReceived, pongSent);
        }
    }
}
