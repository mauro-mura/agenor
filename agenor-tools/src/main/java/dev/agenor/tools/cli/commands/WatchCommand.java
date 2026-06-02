package dev.agenor.tools.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * CLI command to watch real-time events via WebSocket.
 *
 * Usage:
 *   agenor watch                    # Watch all events
 *   agenor watch --filter behavior  # Watch only behavior events
 *   agenor watch --host localhost --port 8080
 */
@Command(
    name = "watch",
    description = "Watch real-time events from Agenor console"
)
public class WatchCommand implements Runnable {

    @Option(names = {"-h", "--host"}, description = "Console host", defaultValue = "localhost")
    private String host;

    @Option(names = {"-p", "--port"}, description = "Console port", defaultValue = "8080")
    private int port;

    @Option(names = {"-f", "--filter"}, description = "Filter by event type prefix (e.g., 'behavior', 'agent')")
    private String filter;

    @Option(names = {"-q", "--quiet"}, description = "Minimal output (no timestamps)")
    private boolean quiet;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void run() {
        String wsUrl = String.format("ws://%s:%d/ws", host, port);

        if (!quiet) {
            System.out.println("Connecting to " + wsUrl + "...");
            if (filter != null) {
                System.out.println("Filter: " + filter + "*");
            }
            System.out.println("Press Ctrl+C to stop\n");
        }

        HttpClient client = HttpClient.newHttpClient();

        try {
            WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new EventListener())
                .join();

            // Wait until interrupted
            latch.await();
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private class EventListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!quiet) {
                System.out.println("Connected.\n");
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);

            if (last) {
                processMessage(buffer.toString());
                buffer.setLength(0);
            }

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("\nConnection closed: " + reason);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
            latch.countDown();
        }

        private void processMessage(String json) {
            try {
                JsonNode event = mapper.readTree(json);
                String type = event.path("type").asText();

                // Apply filter
                if (filter != null && !type.startsWith(filter)) {
                    return;
                }

                // Skip connection.established unless verbose
                if ("connection.established".equals(type) && quiet) {
                    return;
                }

                printEvent(type, event.path("data"), event.path("timestamp").asText());

            } catch (Exception e) {
                System.err.println("Parse error: " + e.getMessage());
            }
        }

        private void printEvent(String type, JsonNode data, String timestamp) {
            String prefix = quiet ? "" : formatTimestamp(timestamp) + " ";

            switch (type) {
                case "behavior.executed" -> printBehaviorExecuted(prefix, data);
                case "agent.started" -> System.out.printf("%s▶ Agent started: %s (%s)%n",
                    prefix, data.path("agentId").asText(), data.path("agentName").asText());
                case "agent.stopped" -> System.out.printf("%s■ Agent stopped: %s%n",
                    prefix, data.path("agentId").asText());
                case "message.sent" -> System.out.printf("%s✉ Message: %s → %s%n",
                    prefix, data.path("senderId").asText(), data.path("topic").asText());
                case "error" -> System.out.printf("%s✗ Error [%s]: %s%n",
                    prefix, data.path("source").asText(), data.path("message").asText());
                case "connection.established" -> System.out.println(prefix + "✓ " + data.asText());
                default -> System.out.printf("%s[%s] %s%n", prefix, type, data);
            }
        }

        private void printBehaviorExecuted(String prefix, JsonNode data) {
            String agentId = data.path("agentId").asText();
            String behaviorId = data.path("behaviorId").asText();
            long durationMs = data.path("durationMs").asLong();
            boolean success = data.path("success").asBoolean();

            if (success) {
                System.out.printf("%s✓ [%s] %s completed in %dms%n",
                    prefix, agentId, behaviorId, durationMs);
            } else {
                String error = data.path("error").asText("unknown error");
                System.out.printf("%s✗ [%s] %s failed (%dms): %s%n",
                    prefix, agentId, behaviorId, durationMs, error);
            }
        }

        private String formatTimestamp(String isoTimestamp) {
            try {
                Instant instant = Instant.parse(isoTimestamp);
                return DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(instant);
            } catch (Exception e) {
                return isoTimestamp;
            }
        }
    }
}
