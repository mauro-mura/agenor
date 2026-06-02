package dev.agenor.tools.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Show detailed status of an agent or runtime.
 *
 * <p>Usage: {@code agenor status [AGENT_ID]}
 */
@Command(
    name = "status",
    description = "Show agent or runtime status"
)
public class StatusCommand extends BaseCommand {

    @Parameters(index = "0", arity = "0..1",
            description = "Agent ID (omit for runtime status)")
    private String agentId;

    @Override
    public void run() {
        try {
            if (agentId == null || agentId.isEmpty()) {
                showRuntimeStatus();
            } else {
                showAgentStatus(agentId);
            }
        } catch (Exception e) {
            printError("Failed to get status: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }

    private void showRuntimeStatus() throws Exception {
    	JsonNode statsResp = apiGet("/api/stats");
        JsonNode healthResp = apiGet("/api/health");
        JsonNode stats = extractData(statsResp);
        JsonNode health = extractData(healthResp);

        System.out.println("=== Agenor Runtime Status ===\n");

        // Health
        String status = health.path("status").asText("unknown");
        String statusIcon = "UP".equals(status) ? "✓" : "✗";
        System.out.printf("Health:     %s %s%n", statusIcon, status);

        // Stats
        System.out.printf("Agents:     %d total, %d running%n",
                stats.path("totalAgents").asInt(0),
                stats.path("activeAgents").asInt(0));

        // Memory
        JsonNode memory = stats.path("memory");
        if (!memory.isMissingNode()) {
            long usedMB = memory.path("used").asLong(0) / (1024 * 1024);
            long maxMB = memory.path("max").asLong(0) / (1024 * 1024);
            System.out.printf("Memory:     %d MB / %d MB%n", usedMB, maxMB);
        }

        // Uptime
        long uptime = stats.path("uptime").asLong(0);
        System.out.printf("Uptime:     %s%n", formatUptime(uptime));
    }

    private String formatUptime(long millis) {
        long seconds = millis / 1000;

        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + "h " + mins + "m";
        } else {
            long days = seconds / 86400;
            long hours = (seconds % 86400) / 3600;
            return days + "d " + hours + "h";
        }
    }

    private void showAgentStatus(String id) throws Exception {
    	JsonNode response = apiGet("/api/agents/" + id);
        JsonNode agent = extractData(response);

        System.out.println("=== Agent: " + agent.path("name").asText(id) + " ===\n");

        System.out.printf("ID:         %s%n", agent.path("id").asText("-"));
        System.out.printf("Name:       %s%n", agent.path("name").asText("-"));
        System.out.printf("Status:     %s%n",
                agent.path("running").asBoolean() ? "RUNNING" : "STOPPED");
        System.out.printf("Behaviors:  %d%n", agent.path("behaviorCount").asInt(0));

        // Behaviors list
        JsonNode behaviors = agent.path("behaviors");
        if (behaviors.isArray() && !behaviors.isEmpty()) {
            System.out.println("\nBehaviors:");
            for (JsonNode b : behaviors) {
                System.out.printf("  - %s (%s)%n",
                        b.path("name").asText("-"),
                        b.path("type").asText("-"));
            }
        }

        // Capabilities
        JsonNode capabilities = agent.path("capabilities");
        if (capabilities.isArray() && !capabilities.isEmpty()) {
            System.out.println("\nCapabilities:");
            for (JsonNode cap : capabilities) {
                System.out.println("  - " + cap.asText());
            }
        }
    }
}
