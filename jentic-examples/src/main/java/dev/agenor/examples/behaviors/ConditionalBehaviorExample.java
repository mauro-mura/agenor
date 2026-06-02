package dev.agenor.examples.behaviors;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.annotations.JenticBehavior;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating ConditionalBehavior usage.
 * Shows how to execute behaviors only when specific conditions are met.
 */
public class ConditionalBehaviorExample {

    private static final Logger log = LoggerFactory.getLogger(ConditionalBehaviorExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=== Conditional Behavior Example ===");

        // Create runtime
        var runtime = JenticRuntime.builder().build();

        // Register conditional agents
        runtime.registerAgent(new ResourceAwareAgent());
        runtime.registerAgent(new BusinessHoursAgent());
        runtime.registerAgent(new AdaptiveAgent());

        // Start runtime
        runtime.start().join();

        log.info("Conditional behaviors running. Watch for condition-based execution...");
        log.info("ResourceAwareAgent: Only runs when system load is low");
        log.info("BusinessHoursAgent: Only runs during business hours (9 AM - 5 PM)");
        log.info("AdaptiveAgent: Adjusts behavior based on system conditions");

        // Let it run for 30 seconds
        Thread.sleep(30_000);

        // Print statistics
        printStatistics(runtime);

        // Stop runtime
        log.info("Stopping runtime...");
        runtime.stop().join();

        log.info("=== Example completed ===");
    }

    private static void printStatistics(JenticRuntime runtime) {
        log.info("\n=== Execution Statistics ===");
        log.info("Agents are running with conditional behaviors");
    }
}

/**
 * Agent that performs resource-intensive tasks only when system load is low.
 */
@JenticAgent("resource-aware-agent")
class ResourceAwareAgent extends BaseAgent {

    public ResourceAwareAgent() {
        super("resource-aware-agent", "Resource Aware Agent");
    }

    @Override
    protected void onStart() {
        log.info("Starting Resource Aware Agent - heavy task will execute only when system load is low");
    }

    /**
     * Heavy task that executes only when CPU < 50% AND Memory < 70%
     * Runs every 3 seconds but only executes when condition is met
     */
    @JenticBehavior(
        type = BehaviorType.CONDITIONAL,
        condition = "system.cpu < 50 AND system.memory < 70",
        interval = "3s"
    )
    private void performHeavyTask() {
        log.info("🔨 Executing heavy computational task (system load is acceptable)");

        // Simulate heavy computation
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Send result
        Message result = Message.builder()
                .topic("heavy.task.completed")
                .senderId(getAgentId())
                .content("Task completed at low system load")
                .build();

        getMessageDispatcher().publish(result);
    }
}

/**
 * Agent that sends notifications only during business hours.
 */
@JenticAgent("business-hours-agent")
class BusinessHoursAgent extends BaseAgent {

    public BusinessHoursAgent() {
        super("business-hours-agent", "Business Hours Agent");
    }

    @Override
    protected void onStart() {
        log.info("Starting Business Hours Agent - notifications will be sent only during business hours (9 AM - 5 PM, weekdays)");
    }

    /**
     * Sends notifications only during business hours (9 AM - 5 PM) on weekdays
     * Checks every 5 seconds but only sends when condition is met
     */
    @JenticBehavior(
        type = BehaviorType.CONDITIONAL,
        condition = "time.businessHours AND time.weekday",
        interval = "5s"
    )
    private void sendNotification() {
        log.info("📧 Sending business notification (currently business hours)");

        Message notification = Message.builder()
                .topic("notification.sent")
                .senderId(getAgentId())
                .content("Business notification sent during working hours")
                .build();

        getMessageDispatcher().publish(notification);
    }
}

/**
 * Agent that adapts its behavior based on system conditions.
 */
@JenticAgent("adaptive-agent")
class AdaptiveAgent extends BaseAgent {

    public AdaptiveAgent() {
        super("adaptive-agent", "Adaptive Agent");
    }

    @Override
    protected void onStart() {
        log.info("Starting Adaptive Agent - adaptive behaviors configured based on system load");
    }

    /**
     * High priority task that runs when system is healthy
     * Executes every 2 seconds when condition is met
     */
    @JenticBehavior(
        type = BehaviorType.CONDITIONAL,
        condition = "system.healthy",
        interval = "2s"
    )
    private void highPriorityTask() {
        log.info("⚡ High priority task (system healthy)");
    }

    /**
     * Low priority task that runs only when system is VERY idle
     * Executes every 4 seconds when CPU < 30% AND Memory < 50%
     */
    @JenticBehavior(
        type = BehaviorType.CONDITIONAL,
        condition = "system.cpu < 30 AND system.memory < 50",
        interval = "4s"
    )
    private void lowPriorityTask() {
        log.info("🐌 Low priority background task (system very idle)");
    }

    /**
     * Emergency monitoring that runs when system is under load
     * Executes every 6 seconds when condition is met
     */
    @JenticBehavior(
        type = BehaviorType.CONDITIONAL,
        condition = "system.underload",
        interval = "6s"
    )
    private void emergencyTask() {
        log.warn("🚨 Emergency monitoring (system under load!)");
    }
}
