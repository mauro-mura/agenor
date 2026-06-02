package dev.agenor.tools.cli.commands;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "logs",
        description = "View agent logs and messages"
)
public class LogsCommand extends BaseCommand {

    @Parameters(index = "0", arity = "0..1",
            description = "Agent ID to filter (omit for all)")
    private String agentId;

    @Option(names = {"-f", "--follow"},
            description = "Follow log output (stream via WebSocket)")
    private boolean follow;

    @Option(names = {"-n", "--lines"},
            description = "Number of messages to show",
            defaultValue = "50")
    private int lines;

    @Option(names = {"-t", "--topic"},
            description = "Filter by topic (exact match)")
    private String topic;

    @Option(names = {"-p", "--pattern"},
            description = "Filter by topic pattern (supports * and ?)")
    private String topicPattern;

    @Override
    public void run() {
        try {
            if (follow) {
                streamLogs();
            } else {
                showRecentLogs();
            }
        } catch (Exception e) {
            printError("Failed to get logs: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }

    private void showRecentLogs() throws Exception {
        StringBuilder endpoint = new StringBuilder("/api/messages?limit=" + lines);

        if (agentId != null && !agentId.isEmpty()) {
            endpoint.append("&senderId=").append(agentId);
        }
        if (topic != null && !topic.isEmpty()) {
            endpoint.append("&topic=").append(topic);
        }
        if (topicPattern != null && !topicPattern.isEmpty()) {
            endpoint.append("&topicPattern=").append(topicPattern);
        }

        JsonNode response = apiGet(endpoint.toString());
        JsonNode messages = extractData(response);

        if (!messages.isArray() || messages.isEmpty()) {
            System.out.println("No recent messages.");
            return;
        }

        System.out.println("Recent messages (" + messages.size() + "):\n");

        for (JsonNode msg : messages) {
            printLogEntry(msg);
        }
    }

    private void streamLogs() throws Exception {
        // Build WebSocket URL from API URL
        // apiUrl = "http://localhost:8080" -> wsUrl = "ws://localhost:8080/ws"
        String baseUrl = getApiUrl();
        String wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws";

        System.out.println("Connecting to WebSocket: " + wsUrl);

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                System.out.println("✓ Connected! Streaming events (Ctrl+C to exit)...");
                System.out.println();
                connectedLatch.countDown();
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);

                if (last) {
                    try {
                        String jsonStr = buffer.toString();
                        buffer = new StringBuilder();

                        if (isVerbose()) {
                            System.out.println("[RAW] " + jsonStr);
                        }

                        JsonNode event = mapper.readTree(jsonStr);
                        String type = event.path("type").asText("");
                        JsonNode eventData = event.path("data");

                        // Filter by agent if specified
                        if (agentId != null && !agentId.isEmpty()) {
                            String eventSender = eventData.path("senderId").asText("");
                            String eventAgentId = eventData.path("agentId").asText("");
                            if (!agentId.equals(eventSender) && !agentId.equals(eventAgentId)) {
                                webSocket.request(1);
                                return CompletableFuture.completedFuture(null);
                            }
                        }

                        // Filter by topic if specified
                        if (topic != null && !topic.isEmpty()) {
                            String eventTopic = eventData.path("topic").asText("");
                            if (!topic.equals(eventTopic)) {
                                webSocket.request(1);
                                return CompletableFuture.completedFuture(null);
                            }
                        }

                        printEvent(event, type);
                    } catch (Exception e) {
                        if (isVerbose()) {
                            System.err.println("[ERROR] Failed to parse: " + e.getMessage());
                        }
                    }
                }

                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                System.out.println("\n✗ Connection closed (code=" + statusCode + ", reason=" + reason + ")");
                closedLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                System.err.println("\n✗ WebSocket error: " + error.getMessage());
                if (isVerbose()) {
                    error.printStackTrace();
                }
                closedLatch.countDown();
            }
        };

        try {
            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(10, TimeUnit.SECONDS);  // Timeout for connection

            // Wait for connection to be established
            if (!connectedLatch.await(5, TimeUnit.SECONDS)) {
                System.err.println("✗ Connection timeout");
                return;
            }

            // Setup shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nClosing connection...");
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing");
                closedLatch.countDown();
            }));

            // Wait until closed
            closedLatch.await();

        } catch (Exception e) {
            System.err.println("✗ Failed to connect: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }

    private void printLogEntry(JsonNode msg) {
        String timestamp = formatTimestamp(msg.path("timestamp").asText(""));
        String sender = msg.path("senderId").asText("-");
        String topic = msg.path("topic").asText("-");
        String content = formatPayload(msg.path("payload"));

        System.out.printf("[%s] %-15s %-25s %s%n", timestamp, sender, topic, content);
    }

    private void printEvent(JsonNode event, String type) {
        String timestamp = formatTimestamp(event.path("timestamp").asText(""));
        JsonNode data = event.path("data");

        switch (type) {
            case "message.sent" -> {
                String sender = data.path("senderId").asText("-");
                String msgTopic = data.path("topic").asText("-");
                String msgId = data.path("messageId").asText("");
                System.out.printf("[%s] MSG   %-12s -> %-20s %s%n",
                        timestamp, sender, msgTopic, shortId(msgId));
            }
            case "agent.started" -> {
                String agentName = data.path("agentName").asText("-");
                String id = data.path("agentId").asText("-");
                System.out.printf("[%s] START %s (%s)%n", timestamp, agentName, id);
            }
            case "agent.stopped" -> {
                String agentName = data.path("agentName").asText("-");
                String id = data.path("agentId").asText("-");
                System.out.printf("[%s] STOP  %s (%s)%n", timestamp, agentName, id);
            }
            case "error" -> {
                String source = data.path("source").asText("-");
                String message = data.path("message").asText("-");
                System.out.printf("[%s] ERROR %s: %s%n", timestamp, source, message);
            }
            case "connection.established" -> {
                System.out.printf("[%s] ✓ Connected to Agenor Web Console%n", timestamp);
            }
            default -> {
                // Show all events in verbose mode
                if (isVerbose()) {
                    System.out.printf("[%s] %-6s %s%n", timestamp, type, data);
                }
            }
        }
    }

    private String formatTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "??:??:??";

        if (timestamp.contains("T")) {
            timestamp = timestamp.substring(timestamp.indexOf("T") + 1);
        }
        if (timestamp.contains(".")) {
            timestamp = timestamp.substring(0, timestamp.indexOf("."));
        }
        if (timestamp.endsWith("Z")) {
            timestamp = timestamp.substring(0, timestamp.length() - 1);
        }
        return timestamp;
    }

    private String formatPayload(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return "-";
        }
        if (payload.isTextual()) {
            return truncate(payload.asText(), 60);
        }
        return truncate(payload.toString(), 60);
    }

    private String truncate(String s, int max) {
        if (s == null) return "-";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private String shortId(String id) {
        if (id == null || id.length() < 8) return id != null ? id : "";
        return id.substring(0, 8);
    }
}
