package dev.agenor.tools.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

/**
 * Stop an agent.
 *
 * <p>Usage: {@code agenor stop <AGENT_ID> [--force]}
 */
@Command(
    name = "stop",
    description = "Stop an agent"
)
public class StopCommand extends BaseCommand {

    @Parameters(index = "0", description = "Agent ID to stop")
    private String agentId;

    @Option(names = {"--force", "-f"},
            description = "Force stop (skip graceful shutdown)")
    private boolean force;

    @Override
    public void run() {
        try {
            String endpoint = "/api/agents/" + agentId + "/stop";
            if (force) {
                endpoint += "?force=true";
            }

            JsonNode result = apiPost(endpoint);

            boolean success = result.path("success").asBoolean(false);
            if (success) {
                printSuccess("Agent '" + agentId + "' stopped successfully");
            } else {
                String message = result.path("message").asText("Unknown error");
                printError("Failed to stop agent: " + message);
            }
        } catch (Exception e) {
            printError("Failed to stop agent: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }
}
