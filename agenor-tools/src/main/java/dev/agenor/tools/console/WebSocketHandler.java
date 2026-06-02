package dev.agenor.tools.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.agenor.core.console.ConsoleEventListener;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.JettyWebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time updates.
 *
 * Events:
 * - agent.started
 * - agent.stopped
 * - message.sent
 * - message.received
 * - behavior.executed
 * - error.occurred
 */
public class WebSocketHandler implements JettyWebSocketCreator, ConsoleEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

    public WebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp) {
        return new WebSocketConnection();
    }

    @Override
    public void onAgentStarted(String agentId, String agentName) {
        broadcast("agent.started", Map.of("agentId", agentId, "agentName", agentName));
    }

    @Override
    public void onAgentStopped(String agentId, String agentName) {
        broadcast("agent.stopped", Map.of("agentId", agentId, "agentName", agentName));
    }

    @Override
    public void onMessageSent(String messageId, String topic, String senderId) {
        broadcast("message.sent", Map.of("messageId", messageId, "topic", topic, "senderId", senderId));
    }

    @Override
    public void onError(String source, String message) {
        broadcast("error", Map.of("source", source, "message", message));
    }

    @Override
    public void onBehaviorExecuted(String agentId, String behaviorId,
                                   long durationMs, boolean success, String error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentId", agentId);
        data.put("behaviorId", behaviorId);
        data.put("durationMs", durationMs);
        data.put("success", success);
        if (error != null) {
            data.put("error", error);
        }
        broadcast("behavior.executed", data);
    }

    /**
     * Broadcast event to all connected clients.
     */
    public void broadcast(String type, Object data) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", type);
            event.set("data", objectMapper.valueToTree(data));
            event.put("timestamp", Instant.now().toString());

            String message = objectMapper.writeValueAsString(event);

            sessions.forEach(session -> {
                if (session.isOpen()) {
                    try {
                        session.getRemote().sendString(message);
                    } catch (Exception e) {
                        logger.error("Failed to send message to client", e);
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Failed to create broadcast message", e);
        }
    }

    /**
     * Individual WebSocket connection.
     */
    private class WebSocketConnection extends WebSocketAdapter {

        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            sessions.add(session);
            logger.info("WebSocket client connected: {}", session.getRemoteAddress());

            // Send welcome message
            try {
                ObjectNode welcome = objectMapper.createObjectNode();
                welcome.put("type", "connection.established");
                welcome.put("message", "Connected to Jentic Web Console");
                welcome.put("timestamp", Instant.now().toString());

                session.getRemote().sendString(objectMapper.writeValueAsString(welcome));
            } catch (Exception e) {
                logger.error("Failed to send welcome message", e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            super.onWebSocketClose(statusCode, reason);
            sessions.remove(getSession());
            logger.info("WebSocket client disconnected: {} ({})", statusCode, reason);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            super.onWebSocketError(cause);
            logger.error("WebSocket error", cause);
            sessions.remove(getSession());
        }

        @Override
        public void onWebSocketText(String message) {
            super.onWebSocketText(message);
            logger.debug("Received message: {}", message);

            // Echo back for now (can implement commands later)
            try {
                ObjectNode response = objectMapper.createObjectNode();
                response.put("type", "echo");
                response.put("message", "Message received: " + message);
                response.put("timestamp", Instant.now().toString());

                getSession().getRemote().sendString(objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                logger.error("Failed to send response", e);
            }
        }
    }
}
