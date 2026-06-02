package dev.agenor.examples;

import dev.agenor.runtime.AgenorRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating automatic agent discovery through package scanning.
 */
public class DiscoveryExample {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Agenor Agent Discovery Example ===");

        // Create runtime with package scanning
        AgenorRuntime runtime = AgenorRuntime.builder()
            .scanPackage("dev.agenor.examples.discovery") // Scan specific package
            .build();

        // Start runtime - agents will be discovered and created automatically
        runtime.start().join();

        // Log runtime statistics
        var stats = runtime.getStats();
        log.info("Runtime started - {}", stats);

        // List of discovered agents
        log.info("Discovered agents:");
        runtime.getAgents().forEach(agent ->
            log.info("  - {} ({}) - Running: {}",
                   agent.getAgentName(), agent.getAgentId(), agent.isRunning()));

        // Let agents run for 20 seconds
        Thread.sleep(20_000);

        // Stop runtime
        log.info("Stopping runtime...");
        runtime.stop().join();

        log.info("=== Discovery Example completed ===");
    }
}
