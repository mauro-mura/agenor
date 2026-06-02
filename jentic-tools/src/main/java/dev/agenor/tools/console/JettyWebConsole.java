package dev.agenor.tools.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.agenor.core.BehaviorScheduler;
import dev.agenor.core.console.WebConsole;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import dev.agenor.tools.history.MessageHistoryService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Jetty-based web console for Jentic runtime management.
 *
 * <p>Provides REST API, WebSocket events, and static resource serving
 * for monitoring and managing agents at runtime.
 *
 * @since 0.4.0
 */
public class JettyWebConsole implements WebConsole {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebConsole.class);

    private final int port;
    private final JenticRuntime runtime;
    private final ObjectMapper objectMapper;
    private final MessageHistoryService messageHistory;
    private final int messageHistorySize;

    private Server server;
    private WebSocketHandler webSocketHandler;
    private volatile boolean running;

    private JettyWebConsole(Builder builder) {
        this.port = builder.port;
        this.runtime = Objects.requireNonNull(builder.runtime, "runtime required");
        this.messageHistorySize = builder.messageHistorySize;
        // Use external MessageHistoryService if provided, otherwise create new one
        if (builder.messageHistory != null) {
            this.messageHistory = builder.messageHistory;
        } else {
            this.messageHistory = new MessageHistoryService(builder.messageHistorySize);
        }
        this.objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (running) {
                logger.warn("Console already running on port {}", port);
                return;
            }

            try {
                logger.info("Starting Jetty console on port {}", port);

                server = new Server();

                ServerConnector connector = new ServerConnector(server);
                connector.setPort(port);
                connector.setIdleTimeout(Duration.ofMinutes(5).toMillis());
                server.addConnector(connector);

                ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
                context.setContextPath("/");
                server.setHandler(context);

                // REST API with message history
                RestAPIHandler restServlet = new RestAPIHandler(runtime, objectMapper, messageHistory);
                context.addServlet(new ServletHolder("rest-api", restServlet), "/api/*");

                // Static resources
                StaticResourceHandler staticHandler = new StaticResourceHandler();
                context.addServlet(new ServletHolder("static", staticHandler), "/*");

                // WebSocket
                webSocketHandler = new WebSocketHandler(objectMapper);
                JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
                    wsContainer.setMaxTextMessageSize(65536);
                    wsContainer.setIdleTimeout(Duration.ofMinutes(10));
                    wsContainer.addMapping("/ws", webSocketHandler);
                });

                // Wire behavior events to WebSocket
                wireBehaviorEvents();

                server.start();
                running = true;

                logger.info("Console started: {}", getBaseUrl());
                logger.info("Message history enabled with capacity: {}", messageHistorySize);

            } catch (Exception e) {
                logger.error("Failed to start console", e);
                throw new RuntimeException("Console start failed", e);
            }
        });
    }

    /**
     * Wires the behavior scheduler to broadcast execution events via WebSocket.
     */
    private void wireBehaviorEvents() {
        BehaviorScheduler scheduler = runtime.getBehaviorScheduler();
        if (scheduler instanceof SimpleBehaviorScheduler sbs) {
            sbs.setEventListener(webSocketHandler);
            logger.info("Behavior execution events wired to WebSocket");
        } else {
            logger.debug("Scheduler is not SimpleBehaviorScheduler, behavior events not wired");
        }
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            if (!running) return;

            try {
                logger.info("Stopping console");
                if (server != null) {
                    server.stop();
                    server = null;
                }
                running = false;
                logger.info("Console stopped");
            } catch (Exception e) {
                logger.error("Error stopping console", e);
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPort() {
        return port;
    }

    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    public JenticRuntime getRuntime() {
        return runtime;
    }

    public MessageHistoryService getMessageHistory() {
        return messageHistory;
    }

    /**
     * Gets the WebSocket handler for event notifications.
     *
     * <p>Use this to wire up StoringMessageService for live message streaming:
     * <pre>{@code
     * storingMessageService.setEventListener(console.getWebSocketHandler());
     * }</pre>
     *
     * @return the WebSocket handler, or null if console not started
     */
    public WebSocketHandler getWebSocketHandler() {
        return webSocketHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 8080;
        private JenticRuntime runtime;
        private int messageHistorySize = MessageHistoryService.DEFAULT_MAX_SIZE;
        private MessageHistoryService messageHistory;

        public Builder port(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
            this.port = port;
            return this;
        }

        public Builder runtime(JenticRuntime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder messageHistorySize(int size) {
            this.messageHistorySize = size;
            return this;
        }

        /**
         * Sets an external MessageHistoryService instance.
         * Use this when you need to share the same instance with StoringMessageService.
         */
        public Builder messageHistory(MessageHistoryService messageHistory) {
            this.messageHistory = messageHistory;
            return this;
        }

        public JettyWebConsole build() {
            return new JettyWebConsole(this);
        }
    }
}
