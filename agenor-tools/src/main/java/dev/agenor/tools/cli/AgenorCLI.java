package dev.agenor.tools.cli;

import dev.agenor.tools.cli.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main CLI entry point for Agenor framework.
 *
 * <p>Usage: {@code agenor [COMMAND] [OPTIONS]}
 */
@Command(
    name = "agenor",
    description = "Agenor Multi-Agent Framework CLI",
    versionProvider = AgenorCLI.ManifestVersionProvider.class,
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

    /**
     * Prints the version the {@code agenor-tools} jar declares in its manifest, so {@code -V}
     * cannot fall behind the release the way a version written into the annotation did.
     */
    static class ManifestVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = AgenorCLI.class.getPackage().getImplementationVersion();
            return new String[] {"agenor " + (version != null
                    ? version
                    : "(version unknown: not loaded from a jar)")};
        }
    }
}
