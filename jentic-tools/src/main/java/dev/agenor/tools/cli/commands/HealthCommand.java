package dev.agenor.tools.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Check runtime health.
 *
 * <p>Usage: {@code agenor health [--watch]}
 */
@Command(
    name = "health",
    description = "Check runtime health"
)
public class HealthCommand extends BaseCommand {

    @Option(names = {"-w", "--watch"},
            description = "Continuously watch health status")
    private boolean watch;

    @Option(names = {"--interval"},
            description = "Watch interval in seconds",
            defaultValue = "5")
    private int interval;

    @Override
    public void run() {
        try {
            if (watch) {
                watchHealth();
            } else {
                checkHealth();
            }
        } catch (Exception e) {
            printError("Health check failed: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }

    private void checkHealth() throws Exception {
    	 JsonNode healthResp = apiGet("/api/health");
         JsonNode statsResp = apiGet("/api/stats");
         JsonNode health = extractData(healthResp);
         JsonNode stats = extractData(statsResp);

        String status = health.path("status").asText("UNKNOWN");
        boolean isUp = "UP".equalsIgnoreCase(status);

        System.out.println("=== Health Check ===\n");
        System.out.printf("Status:     %s %s%n", isUp ? "✓" : "✗", status);

        // Runtime info
        System.out.printf("Runtime:    %s%n",
                stats.path("runtimeName").asText("agenor-runtime"));

        // Agents
        int total = stats.path("totalAgents").asInt(0);
        int running = stats.path("activeAgents").asInt(0);
        System.out.printf("Agents:     %d/%d running%n", running, total);

        // Memory
        JsonNode memory = stats.path("memory");
        if (!memory.isMissingNode()) {
            long usedMB = memory.path("used").asLong(0) / (1024 * 1024);
            long maxMB = memory.path("max").asLong(0) / (1024 * 1024);
            int pct = maxMB > 0 ? (int) (usedMB * 100 / maxMB) : 0;
            System.out.printf("Memory:     %d MB / %d MB (%d%%)%n", usedMB, maxMB, pct);

            // Memory bar
            System.out.print("            [");
            int bars = pct / 5;
            for (int i = 0; i < 20; i++) {
                System.out.print(i < bars ? "█" : "░");
            }
            System.out.println("]");
        }

        // Threads
        int threads = stats.path("threads").asInt(0);
        if (threads > 0) {
            System.out.printf("Threads:    %d%n", threads);
        }

        System.out.println();
        if (isUp) {
            printSuccess("Runtime is healthy");
        } else {
            printError("Runtime is unhealthy");
        }
    }

    private void watchHealth() throws Exception {
        System.out.println("Watching health (Ctrl+C to exit)...\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nStopped watching.");
        }));

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Clear screen (ANSI escape code)
                System.out.print("\033[H\033[2J");
                System.out.flush();

                checkHealth();
                System.out.printf("%nRefreshing in %d seconds...%n", interval);

                Thread.sleep(interval * 1000L);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                printError("Health check failed: " + e.getMessage());
                Thread.sleep(interval * 1000L);
            }
        }
    }
}
