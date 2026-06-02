package dev.agenor.tools.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * List all registered agents.
 *
 * <p>Usage: {@code agenor list [--format FORMAT]}
 */
@Command(
    name = "list",
    aliases = {"ls"},
    description = "List all registered agents"
)
public class ListCommand extends BaseCommand {

    @Option(names = {"-f", "--format"},
            description = "Output format: table, json",
            defaultValue = "table")
    private String format;

    @Override
    public void run() {
        try {
        	JsonNode response = apiGet("/api/agents");
            JsonNode agents = response.path("data");

            if ("json".equalsIgnoreCase(format)) {
                System.out.println(mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(agents));
                return;
            }

            if (!agents.isArray() || agents.isEmpty()) {
                System.out.println("No agents registered.");
                return;
            }

            table.addHeader("ID", "NAME", "STATUS", "BEHAVIORS");

            for (JsonNode agent : agents) {
                String id = agent.path("id").asText("-");
                String name = agent.path("name").asText("-");
                String status = agent.path("running").asBoolean() ? "RUNNING" : "STOPPED";
                int behaviors = agent.path("behaviorCount").asInt(0);

                table.addRow(id, name, status, String.valueOf(behaviors));
            }

            System.out.println(table.render());
            System.out.println("\nTotal: " + agents.size() + " agent(s)");

        } catch (Exception e) {
            printError("Failed to list agents: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }
}
