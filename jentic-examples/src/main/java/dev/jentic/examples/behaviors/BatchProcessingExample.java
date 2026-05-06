package dev.jentic.examples.behaviors;

import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticBehavior;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.runtime.JenticRuntime;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.behavior.advanced.BatchBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static dev.jentic.core.BehaviorType.CYCLIC;

/**
 * Comprehensive example demonstrating BatchBehavior usage.
 *
 * Scenario: Log Aggregation System
 * - EventGeneratorAgent produces log events continuously
 * - LogAggregatorAgent batches log events and writes to "storage"
 * - DatabaseWriterAgent batches database operations
 *
 * Demonstrates:
 * 1. Size-based batch triggering
 * 2. Time-based batch triggering
 * 3. Manual flush operations
 * 4. Batch processing with error handling
 * 5. Statistics tracking
 */
public class BatchProcessingExample {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingExample.class);

    public static void main(String[] args) throws Exception {
        log.info("=".repeat(80));
        log.info("JENTIC BATCH PROCESSING EXAMPLE");
        log.info("Scenario: Log Aggregation and Database Batch Operations");
        log.info("=".repeat(80) + "\n");

        JenticRuntime runtime = JenticRuntime.builder().build();

        runtime.registerAgent(new EventGeneratorAgent());
        runtime.registerAgent(new LogAggregatorAgent());
        runtime.registerAgent(new DatabaseWriterAgent());

        log.info("🚀 Starting Jentic Runtime...\n");
        runtime.start().join();

        Thread.sleep(2000);

        log.info("📊 Running batch processing demonstration for 30 seconds...\n");
        Thread.sleep(30_000);

        runtime.getAgent("log-aggregator").ifPresent(agent -> {
            if (agent instanceof LogAggregatorAgent aggregator) {
                aggregator.printStatistics();
            }
        });

        runtime.getAgent("db-writer").ifPresent(agent -> {
            if (agent instanceof DatabaseWriterAgent dbWriter) {
                dbWriter.printStatistics();
            }
        });

        log.info("\n" + "=".repeat(80));
        log.info("Shutting down runtime...");
        log.info("=".repeat(80));

        runtime.stop().join();

        log.info("\n✅ Example completed successfully!");
    }

    // =========================================================================
    // DATA RECORDS
    // =========================================================================

    record LogEvent(String id, Instant timestamp, String level, String service, String message) {
        @Override
        public String toString() {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
            return String.format("[%s] [%s] [%s] %s",
                LocalDateTime.ofInstant(timestamp, java.time.ZoneId.systemDefault()).format(formatter),
                level, service, message);
        }
    }

    record DatabaseOperation(String type, String table, String data) {
        @Override
        public String toString() {
            return String.format("%s INTO %s (%s)", type, table, data);
        }
    }

    // =========================================================================
    // EVENT GENERATOR AGENT
    // =========================================================================

    /** Generates log events at varying rates. */
    @JenticAgent(value = "event-generator", type = "Producer", capabilities = {"event-generation"})
    public static class EventGeneratorAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(EventGeneratorAgent.class);
        private final Random random = ThreadLocalRandom.current();
        private int eventCounter = 0;

        private static final String[] LOG_LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"};
        private static final String[] SERVICES = {"auth-service", "api-gateway", "user-service", "order-service"};

        public EventGeneratorAgent() {
            super("event-generator", "Event Generator");
        }

        @JenticBehavior(type = CYCLIC, interval = "500ms")
        public void generateEvents() {
            int eventsThisCycle = random.nextInt(5) + 1;
            for (int i = 0; i < eventsThisCycle; i++) {
                LogEvent event = generateRandomEvent();
                Message message = Message.builder()
                    .topic("log.event")
                    .senderId(getAgentId())
                    .content(event)
                    .header("level", event.level())
                    .header("service", event.service())
                    .build();
                getMessageDispatcher().publish(message);
            }
            log.debug("Generated {} events (total: {})", eventsThisCycle, eventCounter);
        }

        private LogEvent generateRandomEvent() {
            eventCounter++;
            String level = LOG_LEVELS[random.nextInt(LOG_LEVELS.length)];
            String service = SERVICES[random.nextInt(SERVICES.length)];
            String message = switch (level) {
                case "DEBUG" -> "Processing request for user " + random.nextInt(1000);
                case "INFO"  -> "Request completed in " + random.nextInt(500) + "ms";
                case "WARN"  -> "High memory usage detected: " + (50 + random.nextInt(40)) + "%";
                case "ERROR" -> "Connection timeout to database after " + random.nextInt(10) + " retries";
                default -> "Unknown event";
            };
            return new LogEvent("evt-" + eventCounter, Instant.now(), level, service, message);
        }
    }

    // =========================================================================
    // LOG AGGREGATOR AGENT
    // =========================================================================

    /** Aggregates log events using BatchBehavior and writes batches to storage. */
    @JenticAgent(value = "log-aggregator", type = "Processor", capabilities = {"log-batching", "storage"})
    public static class LogAggregatorAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(LogAggregatorAgent.class);

        private BatchBehavior<LogEvent> logBatcher;
        private int batchesWritten = 0;
        private int errorBatchesCount = 0;

        public LogAggregatorAgent() {
            super("log-aggregator", "Log Aggregator");
        }

        @Override
        protected void onStart() {
            log.info("🗂️  Log Aggregator started");

            logBatcher = new BatchBehavior<LogEvent>("log-batch", 20, Duration.ofSeconds(3), true) {
                @Override
                protected void processBatch(List<LogEvent> batch) {
                    writeLogsToStorage(batch);
                }

                @Override
                protected void onBatchError(List<LogEvent> failedBatch, Exception error) {
                    errorBatchesCount++;
                    log.error("Failed to write batch of {} logs: {}", failedBatch.size(), error.getMessage());
                }
            };

            logBatcher.setAgent(this);
            addBehavior(logBatcher);
        }

        @JenticMessageHandler("log.event")
        public void handleLogEvent(Message message) {
            LogEvent event = message.getContent(LogEvent.class);
            if (!logBatcher.add(event)) {
                log.warn("Failed to add log event to batch, queue full");
            }
        }

        private void writeLogsToStorage(List<LogEvent> logs) {
            batchesWritten++;
            log.info("📝 Writing batch #{} to storage: {} logs", batchesWritten, logs.size());

            var levelCounts = logs.stream()
                .collect(java.util.stream.Collectors.groupingBy(LogEvent::level, java.util.stream.Collectors.counting()));
            log.info("   Batch composition: {}", levelCounts);

            simulateIO(50 + ThreadLocalRandom.current().nextInt(100));

            if (batchesWritten % 5 == 0 && !logs.isEmpty()) {
                log.info("   Sample entry: {}", logs.get(0));
            }
        }

        public void printStatistics() {
            log.info("\n" + "=".repeat(80));
            log.info("LOG AGGREGATOR STATISTICS");
            log.info("=".repeat(80));
            log.info("Total logs processed: {}", logBatcher.getTotalItemsProcessed());
            log.info("Total batches written: {}", logBatcher.getTotalBatchesProcessed());
            log.info("Partial batches: {}", logBatcher.getPartialBatchesProcessed());
            log.info("Average batch size: {}", String.format("%.2f", logBatcher.getAverageBatchSize()));
            log.info("Batch fullness rate: {}%", String.format("%.1f", logBatcher.getBatchFullnessRate() * 100));
            log.info("Failed batches: {}", errorBatchesCount);
            log.info("Current queue size: {}", logBatcher.getQueueSize());
            log.info("=".repeat(80));
        }

        private void simulateIO(long millis) {
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // =========================================================================
    // DATABASE WRITER AGENT
    // =========================================================================

    /** Batches database write operations. */
    @JenticAgent(value = "db-writer", type = "Persistence", capabilities = {"database", "batch-operations"})
    public static class DatabaseWriterAgent extends BaseAgent {

        private static final Logger log = LoggerFactory.getLogger(DatabaseWriterAgent.class);

        private BatchBehavior<DatabaseOperation> dbBatcher;
        private int transactionsCommitted = 0;

        public DatabaseWriterAgent() {
            super("db-writer", "Database Writer");
        }

        @Override
        protected void onStart() {
            log.info("💾 Database Writer started");

            dbBatcher = BatchBehavior.withTimeout(
                "db-batch", 10, Duration.ofSeconds(2), this::executeBatchedOperations);

            dbBatcher.setAgent(this);
            addBehavior(dbBatcher);
        }

        @JenticBehavior(type = CYCLIC, interval = "800ms")
        public void generateDatabaseOperations() {
            Random random = ThreadLocalRandom.current();
            int opsThisCycle = random.nextInt(3) + 1;
            for (int i = 0; i < opsThisCycle; i++) {
                dbBatcher.add(generateRandomOperation());
            }
        }

        private DatabaseOperation generateRandomOperation() {
            Random random = ThreadLocalRandom.current();
            String[] types  = {"INSERT", "UPDATE", "DELETE"};
            String[] tables = {"users", "orders", "products", "audit_log"};
            return new DatabaseOperation(
                types[random.nextInt(types.length)],
                tables[random.nextInt(tables.length)],
                "record_" + random.nextInt(10000)
            );
        }

        private void executeBatchedOperations(List<DatabaseOperation> operations) {
            transactionsCommitted++;
            log.info("💿 Executing batch transaction #{}: {} operations", transactionsCommitted, operations.size());

            var opTypeCounts = operations.stream()
                .collect(java.util.stream.Collectors.groupingBy(DatabaseOperation::type, java.util.stream.Collectors.counting()));
            log.info("   Operations: {}", opTypeCounts);

            log.debug("   BEGIN TRANSACTION");
            operations.forEach(op -> simulateSQL(5));
            log.debug("   COMMIT TRANSACTION");

            simulateIO(20);
        }

        public void printStatistics() {
            log.info("\n" + "=".repeat(80));
            log.info("DATABASE WRITER STATISTICS");
            log.info("=".repeat(80));
            log.info("Total operations executed: {}", dbBatcher.getTotalItemsProcessed());
            log.info("Total transactions: {}", dbBatcher.getTotalBatchesProcessed());
            log.info("Partial transactions: {}", dbBatcher.getPartialBatchesProcessed());
            log.info("Average ops per transaction: {}", String.format("%.2f", dbBatcher.getAverageBatchSize()));
            log.info("Transaction fullness rate: {}%", String.format("%.1f", dbBatcher.getBatchFullnessRate() * 100));
            log.info("Pending operations: {}", dbBatcher.getQueueSize());
            log.info("=".repeat(80));
        }

        private void simulateIO(long millis) {
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private void simulateSQL(long millis) {
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}