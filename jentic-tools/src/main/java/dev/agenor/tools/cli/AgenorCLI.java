package dev.agenor.tools.cli;

import dev.agenor.tools.cli.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main CLI entry point for Jentic framework.
 *
 * <p>Usage: {@code agenor [COMMAND] [OPTIONS]}
 */
@Command(
    name = "agenor",
    description = "Jentic Multi-Agent Framework CLI",
    version = "0.4.0",
    mixinStandardHelpOptions = true,
    subcommands = {
        ListCommand.class,
        StatusCommand.class,
        StartCommand.class,
        StopCommand.class,
        LogsCommand.class,
        ConfigCommand.class,
        HealthCommand.class,
        WatchCommand.class
    }
)
public class AgenorCLI implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AgenorCLI())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
