package dev.jentic.examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jentic.runtime.JenticRuntime;

/**
 * Example demonstrating automatic agent discovery through package scanning.
 */
public class DiscoveryExample {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Jentic Agent Discovery Example ===");

        // Create runtime with package scanning
        JenticRuntime runtime = JenticRuntime.builder()
            .scanPackage("dev.jentic.examples.discovery") // Scan specific package
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
