package dev.agenor.examples.behaviors;

import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.advanced.ScheduledBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating ScheduledBehavior with cron expressions.
 *
 * <p>This example showcases:
 * <ul>
 *   <li>Various cron expression patterns</li>
 *   <li>Timezone-aware scheduling</li>
 *   <li>Factory methods for common schedules</li>
 *   <li>Execution callbacks and metrics</li>
 *   <li>Real-world use cases</li>
 * </ul>
 */
public class ScheduledExample {

    private static final Logger log = LoggerFactory.getLogger(ScheduledExample.class);

    public static void main(String[] args) throws Exception {
        log.info("=".repeat(80));
        log.info("JENTIC SCHEDULED BEHAVIOR - DEMONSTRATION");
        log.info("Cron-like scheduling with timezone support");
        log.info("=".repeat(80) + "\n");

        AgenorRuntime runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new SchedulerDemoAgent());
        runtime.start().join();

        // =====================================================================
        // SCENARIO 1: Basic Scheduled Tasks
        // =====================================================================

        log.info("\n" + "=".repeat(80));
        log.info("SCENARIO 1: Basic Scheduled Tasks");
        log.info("=".repeat(80) + "\n");

        demonstrateBasicScheduling(runtime);
        Thread.sleep(15000);

        // =====================================================================
        // SCENARIO 2: Real-World Use Cases
        // =====================================================================

        log.info("\n" + "=".repeat(80));
        log.info("SCENARIO 2: Real-World Use Cases");
        log.info("=".repeat(80) + "\n");

        demonstrateRealWorldUseCases(runtime);
        Thread.sleep(20000);

        // =====================================================================
        // SCENARIO 3: Metrics and Monitoring
        // =====================================================================

        log.info("\n" + "=".repeat(80));
        log.info("SCENARIO 3: Metrics and Monitoring");
        log.info("=".repeat(80) + "\n");

        demonstrateMetrics(runtime);

        // =====================================================================
        // Shutdown
        // =====================================================================

        log.info("\n" + "=".repeat(80));
        log.info("Shutting down...");
        log.info("=".repeat(80) + "\n");

        runtime.stop().join();
        log.info("✅ Example completed successfully\n");
    }

    private static void demonstrateBasicScheduling(AgenorRuntime runtime) {
        log.info("Creating various scheduled behaviors...\n");

        ScheduledBehavior everySecond = new ScheduledBehavior("every-second", "* * * * * *") {
            private int count = 0;

            @Override
            protected void scheduledAction() {
                count++;
                log.info("⏰ Every Second Task - Execution #{}", count);
            }
        };
        everySecond.onSuccess(b -> log.debug("   ✓ Completed in {}ms", b.getAverageExecutionTimeMs()));

        ScheduledBehavior everyFiveSeconds = new ScheduledBehavior("every-five-seconds", "*/5 * * * * *") {
            @Override
            protected void scheduledAction() {
                log.info("🔄 Every 5 Seconds Task - Running at {}",
                        ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        };

        ScheduledBehavior hourly = ScheduledBehavior.everyHour(
                "hourly-report", () -> log.info("📊 Hourly Report Generated"));

        log.info("Scheduled Behaviors Created:");
        log.info("  1. Every Second: next at {}",
                everySecond.getNextExecutionTime().format(DateTimeFormatter.ISO_LOCAL_TIME));
        log.info("  2. Every 5 Seconds: next at {}",
                everyFiveSeconds.getNextExecutionTime().format(DateTimeFormatter.ISO_LOCAL_TIME));
        log.info("  3. Hourly: next at {}",
                hourly.getNextExecutionTime().format(DateTimeFormatter.ISO_LOCAL_TIME));
        log.info("");

        registerBehaviorWithAgent(runtime, everySecond);
        registerBehaviorWithAgent(runtime, everyFiveSeconds);
        registerBehaviorWithAgent(runtime, hourly);
    }

    private static void demonstrateRealWorldUseCases(AgenorRuntime runtime) {
        ScheduledBehavior businessHours = ScheduledBehavior.weekdays(
                "business-hours-check", 9, 0,
                () -> log.info("💼 Business Hours Task - Market Opening Check"));

        log.info("Business Hours Task: scheduled for weekdays at 9:00 AM");
        log.info("  Next execution: {}",
                businessHours.getNextExecutionTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        ScheduledBehavior dailyBackup = ScheduledBehavior.daily(
                "daily-backup", 2, 0,
                () -> log.info("💾 Daily Backup - Running database backup"));

        log.info("\nDaily Backup: scheduled for 2:00 AM daily");
        log.info("  Next execution: {}",
                dailyBackup.getNextExecutionTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        ScheduledBehavior weeklyCleanup = new ScheduledBehavior("weekly-cleanup", "0 0 0 * * SUN") {
            @Override
            protected void scheduledAction() {
                log.info("🧹 Weekly Cleanup - Removing old logs and temporary files");
            }
        };

        log.info("\nWeekly Cleanup: scheduled for Sundays at midnight");
        log.info("  Next execution: {}",
                weeklyCleanup.getNextExecutionTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        ScheduledBehavior monitoring = new ScheduledBehavior("system-monitor", "*/10 * * * * *") {
            private final AtomicInteger errorCount = new AtomicInteger(0);

            @Override
            protected void scheduledAction() throws Exception {
                log.info("📡 System Monitor - Checking system health");
                if (Math.random() < 0.2) {
                    errorCount.incrementAndGet();
                    throw new RuntimeException("Simulated monitoring error");
                }
            }
        };
        monitoring.onFailure(e -> log.warn("   ⚠️  Monitoring failed: {}", e.getMessage()));
        monitoring.setExecutionTimeout(Duration.ofSeconds(5));

        log.info("\nSystem Monitor: checking every 10 seconds with error handling\n");

        registerBehaviorWithAgent(runtime, businessHours);
        registerBehaviorWithAgent(runtime, dailyBackup);
        registerBehaviorWithAgent(runtime, weeklyCleanup);
        registerBehaviorWithAgent(runtime, monitoring);
    }

    private static void demonstrateMetrics(AgenorRuntime runtime) throws Exception {
        log.info("Creating behavior with metrics tracking...\n");

        ScheduledBehavior metricsDemo = new ScheduledBehavior("metrics-demo", "*/2 * * * * *") {
            private int taskNumber = 0;

            @Override
            protected void scheduledAction() throws Exception {
                taskNumber++;
                log.info("📈 Metrics Demo Task #{}", taskNumber);
                Thread.sleep(100 + (int) (Math.random() * 200));
                if (taskNumber % 5 == 0) throw new RuntimeException("Simulated failure for metrics");
            }
        };
        metricsDemo.onSuccess(b -> {
            if (b.getTotalExecutions() % 3 == 0) log.info("   📊 Metrics Update: {}", b.getMetricsSummary());
        });
        metricsDemo.onFailure(e -> log.error("   ❌ Task failed: {}", e.getMessage()));

        registerBehaviorWithAgent(runtime, metricsDemo);

        log.info("Running metrics demo for 10 seconds...\n");
        Thread.sleep(10000);

        log.info("\n" + "=".repeat(80));
        log.info("FINAL METRICS SUMMARY");
        log.info("=".repeat(80));
        log.info(metricsDemo.getMetricsSummary());
        log.info("=".repeat(80) + "\n");
    }

    /** Attach a behavior to the first available agent in the runtime. */
    private static void registerBehaviorWithAgent(AgenorRuntime runtime, ScheduledBehavior behavior) {
        runtime.getAgents().stream()
                .findFirst()
                .ifPresent(agent -> agent.addBehavior(behavior));
    }

    // =========================================================================
    // AGENT
    // =========================================================================

    @Agent(value = "scheduler-demo", type = "scheduler",
                 capabilities = {"scheduled-tasks", "cron", "automation"})
    public static class SchedulerDemoAgent extends BaseAgent {

        public SchedulerDemoAgent() { super("scheduler-demo", "Scheduler Demo Agent"); }

        @Override
        protected void onStart() {
            log.info("🚀 Scheduler Demo Agent started");
            log.info("   Ready to host scheduled behaviors");
            log.info("   Timezone: {}", ZoneId.systemDefault());
        }

        @Override
        protected void onStop() {
            log.info("🛑 Scheduler Demo Agent stopped");
            log.info("   All scheduled behaviors stopped");
        }
    }
}
