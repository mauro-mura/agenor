package dev.agenor.examples.support.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.examples.support.model.SupportResponse;
import io.a2a.spec.AgentCard;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Jetty-based A2A HTTP server for FinanceCloud Support Chatbot.
 *
 * <p>Implements A2A protocol endpoints:
 * <ul>
 *   <li>GET /.well-known/agent.json - Agent discovery (AgentCard)</li>
 *   <li>POST /a2a - JSON-RPC message handling</li>
 *   <li>GET /health - Health check</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * A2AHttpServer server = A2AHttpServer.builder()
 *     .port(8080)
 *     .messageService(runtime.getMessageDispatcher())
 *     .build();
 *
 * server.start();
 * // ...
 * server.stop();
 * </pre>
 */
public class A2AHttpServer {

    private static final Logger log = LoggerFactory.getLogger(A2AHttpServer.class);

    private final int port;
    private final MessageDispatcher messageService;
    private final AgentCard agentCard;
    private final String agentCardJson;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    private Server server;
    private volatile boolean running = false;

    // Pending requests waiting for response
    private final Map<String, CompletableFuture<SupportResponse>> pendingRequests = new ConcurrentHashMap<>();

    private A2AHttpServer(Builder builder) {
        this.port = builder.port;
        this.messageService = builder.messageService;
        this.timeout = builder.timeout;
        this.objectMapper = new ObjectMapper();

        String baseUrl = builder.baseUrl != null ? builder.baseUrl : "http://localhost:" + port;
        this.agentCard = SupportA2AServer.createAgentCard(baseUrl, builder.streaming);
        this.agentCardJson = SupportA2AServer.serializeAgentCard(agentCard);
    }

    /**
     * Starts the A2A HTTP server.
     */
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (running) {
                log.warn("A2A server already running on port {}", port);
                return;
            }

            try {
                log.info("Starting A2A HTTP server on port {}", port);

                server = new Server();

                ServerConnector connector = new ServerConnector(server);
                connector.setPort(port);
                connector.setIdleTimeout(Duration.ofMinutes(5).toMillis());
                server.addConnector(connector);

                ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
                context.setContextPath("/");
                server.setHandler(context);

                // Agent discovery endpoint
                context.addServlet(new ServletHolder("agent-card", new AgentCardServlet()),
                    "/.well-known/agent.json");

                // A2A JSON-RPC endpoint
                context.addServlet(new ServletHolder("a2a", new A2AServlet()), "/a2a");

                // Health check
                context.addServlet(new ServletHolder("health", new HealthServlet()), "/health");

                // Subscribe to responses
                subscribeToResponses();

                server.start();
                running = true;

                log.info("A2A HTTP Server started on port {}", port);
                log.info("  Agent: {}", agentCard.name());
                log.info("  Skills: {}", agentCard.skills().size());
                log.info("  Discovery: http://localhost:{}/.well-known/agent.json", port);
                log.info("  Endpoint: http://localhost:{}/a2a", port);

            } catch (Exception e) {
                log.error("Failed to start A2A server", e);
                throw new RuntimeException("A2A server startup failed", e);
            }
        });
    }

    /**
     * Stops the A2A HTTP server.
     */
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            if (!running) return;

            try {
                log.info("Stopping A2A HTTP server");
                if (server != null) {
                    server.stop();
                    server = null;
                }
                running = false;
                log.info("A2A HTTP Server stopped");
            } catch (Exception e) {
                log.error("Error stopping A2A server", e);
            }
        });
    }

    /**
     * Subscribe to support responses to complete pending A2A requests.
     */
    private void subscribeToResponses() {
        messageService.subscribeTopic("support.response", MessageHandler.sync(msg -> {
            String correlationId = msg.correlationId();
            if (correlationId == null) return;

            CompletableFuture<SupportResponse> future = pendingRequests.remove(correlationId);
            if (future != null && msg.content() instanceof SupportResponse response) {
                future.complete(response);
            }
        }));
    }

    /**
     * Sends a query and waits for response.
     */
    private SupportResponse sendQueryAndWait(String text, String taskId) throws Exception {
        CompletableFuture<SupportResponse> future = new CompletableFuture<>();
        pendingRequests.put(taskId, future);

        try {
            Message query = Message.builder()
                .topic("support.query")
                .senderId("a2a-client")
                .correlationId(taskId)
                .content(text)
                .build();

            messageService.publish(query);

            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(taskId);
            throw new RuntimeException("Request timeout after " + timeout.toSeconds() + "s");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public AgentCard getAgentCard() {
        return agentCard;
    }

    // ========== SERVLETS ==========

    /**
     * Serves AgentCard JSON at /.well-known/agent.json
     */
    private class AgentCardServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(agentCardJson);
        }
    }

    /**
     * Health check endpoint.
     */
    private class HealthServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.getWriter().write("{\"status\":\"ok\",\"agent\":\"" + agentCard.name() + "\"}");
        }
    }

    /**
     * A2A JSON-RPC endpoint at /a2a
     *
     * Simplified implementation handling:
     * - message/send method
     */
    private class A2AServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");

            String requestId = null;

            try {
                // Parse JSON with Jackson
                JsonNode root = objectMapper.readTree(req.getInputStream());
                log.debug("A2A request: {}", root);

                // Extract JSON-RPC fields
                requestId = root.has("id") ? root.get("id").asText() : null;
                String method = root.has("method") ? root.get("method").asText() : null;
                JsonNode params = root.get("params");

                log.debug("Parsed: id={}, method={}", requestId, method);

                if (method == null) {
                    sendJsonRpcError(resp, requestId, -32600, "Invalid request: method required");
                    return;
                }

                // Handle based on method
                switch (method) {
                    case "message/send" -> handleMessageSend(resp, requestId, params);
                    case "tasks/get" -> handleTasksGet(resp, requestId, params);
                    default -> sendJsonRpcError(resp, requestId, -32601, "Method not found: " + method);
                }

            } catch (Exception e) {
                log.error("A2A request failed", e);
                sendJsonRpcError(resp, requestId, -32603, "Internal error: " + e.getMessage());
            }
        }

        private void handleMessageSend(HttpServletResponse resp, String requestId, JsonNode params) throws Exception {
            // Extract message text from params.message.parts[0].text
            String messageText = extractMessageText(params);
            if (messageText == null || messageText.isBlank()) {
                sendJsonRpcError(resp, requestId, -32602, "Invalid params: message text required");
                return;
            }

            log.info("A2A message received: {}", messageText);

            String taskId = UUID.randomUUID().toString();

            // Send to support system and wait for response
            SupportResponse supportResponse = sendQueryAndWait(messageText, taskId);

            // Build A2A response
            ObjectNode result = buildTaskResult(taskId, supportResponse);
            sendJsonRpcResult(resp, requestId, result);
        }

        private void handleTasksGet(HttpServletResponse resp, String requestId, JsonNode params) throws IOException {
            // Simplified: we don't track tasks, return error
            sendJsonRpcError(resp, requestId, -32602, "Task not found");
        }

        private String extractMessageText(JsonNode params) {
            if (params == null) return null;

            // Navigate: params.message.parts[0].text
            JsonNode message = params.get("message");
            if (message == null) return null;

            JsonNode parts = message.get("parts");
            if (parts == null || !parts.isArray() || parts.isEmpty()) return null;

            JsonNode firstPart = parts.get(0);
            if (firstPart == null) return null;

            JsonNode text = firstPart.get("text");
            return text != null ? text.asText() : null;
        }

        private ObjectNode buildTaskResult(String taskId, SupportResponse response) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("id", taskId);

            ObjectNode status = result.putObject("status");
            status.put("state", "completed");

            ObjectNode artifact = objectMapper.createObjectNode();
            ObjectNode part = objectMapper.createObjectNode();
            part.put("type", "text");
            part.put("text", response.text());
            artifact.putArray("parts").add(part);

            result.putArray("artifacts").add(artifact);

            return result;
        }

        private void sendJsonRpcResult(HttpServletResponse resp, String id, ObjectNode result) throws IOException {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.set("result", result);

            objectMapper.writeValue(resp.getWriter(), response);
        }

        private void sendJsonRpcError(HttpServletResponse resp, String id, int code, String message) throws IOException {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.put("id", id);
            } else {
                response.putNull("id");
            }

            ObjectNode error = response.putObject("error");
            error.put("code", code);
            error.put("message", message);

            objectMapper.writeValue(resp.getWriter(), response);
        }
    }

    // ========== BUILDER ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 8080;
        private MessageDispatcher messageService;
        private String baseUrl;
        private Duration timeout = Duration.ofMinutes(2);
        private boolean streaming = true;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder messageService(MessageDispatcher messageService) {
            this.messageService = messageService;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public A2AHttpServer build() {
            if (messageService == null) {
                throw new IllegalStateException("MessageService is required");
            }
            return new A2AHttpServer(this);
        }
    }
}
