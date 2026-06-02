package dev.agenor.tools.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Start an agent.
 *
 * <p>Usage: {@code agenor start <AGENT_ID>}
 */
@Command(
    name = "start",
    description = "Start an agent"
)
public class StartCommand extends BaseCommand {

    @Parameters(index = "0", description = "Agent ID to start")
    private String agentId;

    @Override
    public void run() {
        try {
            JsonNode result = apiPost("/api/agents/" + agentId + "/start");

            boolean success = result.path("success").asBoolean(false);
            if (success) {
                printSuccess("Agent '" + agentId + "' started successfully");
            } else {
                String message = result.path("message").asText("Unknown error");
                printError("Failed to start agent: " + message);
            }
        } catch (Exception e) {
            printError("Failed to start agent: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }
}
