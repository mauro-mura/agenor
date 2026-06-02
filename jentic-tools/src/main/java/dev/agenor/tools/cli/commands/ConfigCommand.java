package dev.agenor.tools.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Manage Jentic configuration.
 *
 * <p>Usage: {@code agenor config [show|validate|path]}
 */
@Command(
    name = "config",
    description = "Manage configuration"
)
public class ConfigCommand extends BaseCommand {

    @Parameters(index = "0", arity = "0..1",
            description = "Action: show, validate, path",
            defaultValue = "show")
    private String action;

    @Option(names = {"-c", "--config"},
            description = "Configuration file path")
    private Path configPath;

    @Override
    public void run() {
        try {
            switch (action.toLowerCase()) {
                case "show" -> showConfig();
                case "validate" -> validateConfig();
                case "path" -> showConfigPath();
                default -> printError("Unknown action: " + action +
                        ". Use: show, validate, or path");
            }
        } catch (Exception e) {
            printError("Configuration error: " + e.getMessage());
            if (isVerbose()) {
                e.printStackTrace();
            }
        }
    }

    private void showConfig() throws Exception {
        Path path = resolveConfigPath();
        if (path == null || !Files.exists(path)) {
            System.out.println("No configuration file found.");
            System.out.println("Default configuration is being used.");
            System.out.println("\nTo create a config file, save as agenor.yaml:");
            System.out.println(getDefaultConfig());
            return;
        }

        System.out.println("Configuration file: " + path);
        System.out.println("---");
        System.out.println(Files.readString(path));
    }

    private void validateConfig() throws Exception {
        Path path = resolveConfigPath();
        if (path == null || !Files.exists(path)) {
            printError("No configuration file found at expected locations.");
            return;
        }

        String content = Files.readString(path);

        // Basic YAML validation
        if (!content.contains(":")) {
            printError("Invalid YAML: no key-value pairs found");
            return;
        }

        // Check for common required sections
        boolean hasJentic = content.contains("agenor:");
        boolean hasRuntime = content.contains("runtime:");
        boolean hasAgents = content.contains("agents:");

        System.out.println("Configuration: " + path);
        System.out.println("---");
        System.out.printf("  agenor section:  %s%n", hasJentic ? "✓" : "✗ missing");
        System.out.printf("  runtime section: %s%n", hasRuntime ? "✓" : "optional");
        System.out.printf("  agents section:  %s%n", hasAgents ? "✓" : "optional");
        System.out.println("---");

        if (hasJentic) {
            printSuccess("Configuration is valid");
        } else {
            System.out.println("Warning: Missing 'agenor:' root section");
        }
    }

    private void showConfigPath() {
        Path path = resolveConfigPath();
        if (path != null && Files.exists(path)) {
            System.out.println(path.toAbsolutePath());
        } else {
            System.out.println("No configuration file found.");
            System.out.println("\nSearched locations:");
            System.out.println("  1. ./agenor.yaml");
            System.out.println("  2. ./config/agenor.yaml");
            System.out.println("  3. ~/.agenor/config.yaml");
        }
    }

    private Path resolveConfigPath() {
        if (configPath != null) {
            return configPath;
        }

        // Search order
        Path[] searchPaths = {
            Path.of("agenor.yaml"),
            Path.of("agenor.yml"),
            Path.of("config/agenor.yaml"),
            Path.of("config/agenor.yml"),
            Path.of(System.getProperty("user.home"), ".agenor", "config.yaml")
        };

        for (Path p : searchPaths) {
            if (Files.exists(p)) {
                return p;
            }
        }

        return null;
    }

    private String getDefaultConfig() {
        return """
            agenor:
              runtime:
                name: "agenor-runtime"

              discovery:
                packages:
                  - "com.example.agents"
                auto-start: true

              console:
                enabled: true
                port: 8080

              logging:
                level: INFO
            """;
    }
}
