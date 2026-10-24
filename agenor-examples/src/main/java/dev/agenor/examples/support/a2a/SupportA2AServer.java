package dev.agenor.examples.support.a2a;

import dev.agenor.adapters.a2a.AgenorA2AServer;
import dev.agenor.adapters.a2a.AgenorAgentExecutor;
import dev.agenor.core.directory.AgentDirectory;
import dev.agenor.core.messaging.MessageDispatcher;

import java.time.Duration;

/**
 * Assembles the A2A side of the support chatbot: the card from {@link SupportAgentCardConfig}, and
 * an {@link AgenorAgentExecutor} that sends each task to {@link SupportA2AGatewayAgent}.
 *
 * <pre>{@code
 * runtime.registerAgent(new SupportA2AGatewayAgent(Duration.ofMinutes(2)));
 * AgenorA2AServer a2a = SupportA2AServer.create("http://localhost:8081",
 *         runtime.getMessageDispatcher(), runtime.getAgentDirectory(), Duration.ofMinutes(2));
 * }</pre>
 *
 * <p>The result serves no HTTP by itself: {@link A2AHttpServer} puts it behind Jetty, and any
 * other HTTP server takes the same two endpoints.
 */
public final class SupportA2AServer {

    private SupportA2AServer() {
    }

    /**
     * @param baseUrl    the URL the server is reached at, declared in the card
     * @param dispatcher the runtime's dispatcher
     * @param directory  the runtime's directory, so the gateway can answer from another node
     * @param timeout    how long a task waits for the support pipeline
     * @return the A2A server; close it with the HTTP server that fronts it
     */
    public static AgenorA2AServer create(String baseUrl, MessageDispatcher dispatcher,
                                         AgentDirectory directory, Duration timeout) {
        var executor = new AgenorAgentExecutor(SupportA2AGatewayAgent.ID, dispatcher, directory, timeout);
        return AgenorA2AServer.of(SupportAgentCardConfig.create(baseUrl).timeout(timeout), executor);
    }
}
