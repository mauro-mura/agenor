package dev.agenor.core.console;

import java.util.concurrent.CompletableFuture;

/**
 * Web-based management console for Agenor agents.
 *
 * <p>Provides HTTP REST API and WebSocket endpoints for monitoring
 * and managing agents at runtime. Implementations may use different
 * web frameworks (Jetty, Netty, Spring WebFlux).
 *
 * @since 0.4.0
 */
public interface WebConsole {

    /**
     * Start the web console server.
     *
     * @return future that completes when server is ready
     */
    CompletableFuture<Void> start();

    /**
     * Stop the web console server.
     *
     * @return future that completes when server has stopped
     */
    CompletableFuture<Void> stop();

    /**
     * Check if the console is running.
     *
     * @return true if server is accepting connections
     */
    boolean isRunning();

    /**
     * Get the configured port number.
     *
     * @return HTTP port
     */
    int getPort();

    /**
     * Get the base URL for the console.
     *
     * @return URL string like "http://localhost:8080"
     */
    default String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    /**
     * Get the API URL.
     *
     * @return API URL string like "http://localhost:8080/api"
     */
    default String getApiUrl() {
        return getBaseUrl() + "/api";
    }

    /**
     * Get the WebSocket URL.
     *
     * @return WebSocket URL string like "ws://localhost:8080/ws"
     */
    default String getWebSocketUrl() {
        return "ws://localhost:" + getPort() + "/ws";
    }
}
