package dev.agenor.examples.cli;

import java.util.concurrent.CountDownLatch;

import dev.agenor.runtime.AgenorRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.filter.MessageFilter;
import dev.agenor.tools.agents.MessageSnifferAgent;
import dev.agenor.tools.console.JettyWebConsole;
import dev.agenor.tools.history.MessageHistoryService;

/**
 * Example demonstrating CLI usage with a running Agenor runtime.
 *
 * <p>This example starts a runtime with sample agents and a web console,
 * allowing you to interact via the CLI.
 *
 * <h2>Usage:</h2>
 * <pre>
 * # 1. Build the project
 * mvn clean install -DskipTests
 *
 * # 2. Start this example in Terminal 1 (keeps running)
 * mvn exec:java -pl agenor-examples -Dexec.mainClass="dev.agenor.examples.cli.CLIExample"
 *
 * # 3. In Terminal 2, use the CLI:
 *
 * # List all agents
 * mvn exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args="list"
 * +----------------+--------------------+---------+------------------------+
 * | ID             | NAME               | STATUS  | TYPE                   |
 * +----------------+--------------------+---------+------------------------+
 * | sensor-agent   | Temperature Sensor | RUNNING | TemperatureSensorAgent |
 * | alert-agent    | Alert Handler      | RUNNING | AlertHandlerAgent      |
 * | logger-agent   | System Logger      | RUNNING | SystemLoggerAgent      |
 * +----------------+--------------------+---------+------------------------+
 * Total: 3 agent(s)
 *
 * # Check runtime status
 * mvn exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args="status"
 * === Agenor Runtime Status ===
 *
 * Health:     ✓ UP
 * Agents:     3 total, 3 running
 * Memory:     128 MB / 512 MB
 * Uptime:     45s
 *
 * # Check specific agent
 * mvn exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args="status sensor-agent"
 * === Agent: Temperature Sensor ===
 *
 * ID:         sensor-agent
 * Name:       Temperature Sensor
 * Type:       TemperatureSensorAgent
 * Status:     RUNNING
 *
 * # Stop an agent
 * mvn exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args="stop sensor-agent"
 * ✓ Agent 'sensor-agent' stopped successfully
 *
 * # Start an agent
 * mvn exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args="start sensor-agent"
 * ✓ Agent 'sensor-agent' started successfully
 *
 * # Health check
 * mvn exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args="health"
 * === Health Check ===
 *
 * Status:     ✓ UP
 * Runtime:    agenor-runtime
 * Agents:     3/3 running
 * Memory:     128 MB / 512 MB (25%)
 *             [█████░░░░░░░░░░░░░░░]
 *
 * ✓ Runtime is healthy
 *
 * # Alternatively, create an alias for easier usage:
 * alias agenor='mvn -q exec:java -pl agenor-tools -Dexec.mainClass="dev.agenor.tools.cli.AgenorCLI" -Dexec.args='
 * agenor "list"
 * agenor "status"
 * agenor "health"
 * </pre>
 */
public class CLIExample {

    private static final Logger log = LoggerFactory.getLogger(CLIExample.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Agenor CLI Example...");

        // Create message history service
        MessageHistoryService messageHistory = new MessageHistoryService(500);

        // Create sniffer agent with shared history
        MessageSnifferAgent sniffer = new MessageSnifferAgent(
            MessageFilter.acceptAll(),
            messageHistory
        );

        // Build runtime with sample agents
        AgenorRuntime runtime = AgenorRuntime.builder()
                .scanPackages("dev.agenor.examples.cli")
                .build();

        // Register sniffer as system agent
        runtime.registerAgent(sniffer);

        log.info("Registered {} agents", runtime.getAgents().size());

        // Start web console on port 8080
        JettyWebConsole console = JettyWebConsole.builder()
                .runtime(runtime)
                .messageHistory(messageHistory)
                .port(8080)
                .build();

        // Start everything
        runtime.start().join();
        console.start().join();

        // Wire sniffer to WebSocket for live streaming (agenor logs -f)
        sniffer.setEventListener(console.getWebSocketHandler());

        log.info("=".repeat(60));
        log.info("Runtime started successfully!");
        log.info("=".repeat(60));
        log.info("");
        log.info("Web Console: {}", console.getBaseUrl());
        log.info("API URL:     {}", console.getApiUrl());
        log.info("WebSocket:   {}", console.getWebSocketUrl());
        log.info("");
        log.info("CLI Commands (run in another terminal):");
        log.info("  agenor list              - List all agents");
        log.info("  agenor status            - Runtime status");
        log.info("  agenor status sensor-agent - Agent details");
        log.info("  agenor stop sensor-agent - Stop agent");
        log.info("  agenor start sensor-agent - Start agent");
        log.info("  agenor logs -f           - Stream live logs");
        log.info("  agenor health --watch    - Watch health");
        log.info("");
        log.info("Press Ctrl+C to shutdown...");
        log.info("=".repeat(60));

        // Wait for shutdown signal
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                console.stop().join();
                runtime.stop().join();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
            shutdownLatch.countDown();
        }));

        shutdownLatch.await();
        log.info("Goodbye!");
    }

}
