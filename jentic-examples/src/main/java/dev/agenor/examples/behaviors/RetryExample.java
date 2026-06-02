package dev.agenor.examples.behaviors;

import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.advanced.RetryBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating RetryBehavior usage in real-world scenarios.
 *
 * <p>Scenarios covered:
 * <ol>
 *   <li>External API calls with network failures (exponential backoff)</li>
 *   <li>Database operations with transient errors (linear backoff)</li>
 *   <li>File operations with temporary locks (jittered backoff)</li>
 *   <li>Message processing with selective retry (fixed delay)</li>
 * </ol>
 */
public class RetryExample {

    private static final Logger log = LoggerFactory.getLogger(RetryExample.class);

    public static void main(String[] args) throws Exception {
        log.info("=".repeat(80));
        log.info("JENTIC RETRY BEHAVIOR - COMPREHENSIVE EXAMPLE");
        log.info("Demonstrating retry patterns for real-world scenarios");
        log.info("=".repeat(80) + "\n");

        ApiClientAgent   apiAgent  = new ApiClientAgent();
        DatabaseAgent    dbAgent   = new DatabaseAgent();
        FileProcessorAgent fileAgent = new FileProcessorAgent();
        MessageProcessorAgent msgAgent = new MessageProcessorAgent();

        AgenorRuntime runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(apiAgent);
        runtime.registerAgent(dbAgent);
        runtime.registerAgent(fileAgent);
        runtime.registerAgent(msgAgent);

        log.info("🚀 Starting Jentic Runtime...\n");
        runtime.start().get(10, TimeUnit.SECONDS);

        Thread.sleep(2000);

        // =====================================================================
        // SCENARIO 1: API Client with Retry
        // =====================================================================

        log.info("\n" + "=".repeat(80));
        log.info("SCENARIO 1: External API calls with exponential backoff");
        log.info("=".repeat(80) + "\n");

        apiAgent.demonstrateApiRetry();
        Thread.sleep(3000);

        // =====================================================================
        // SCENARIO 2: Database Operations with Retry
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("SCENARIO 2: Database operations with linear backoff");
        log.info("=".repeat(80) + "\n");

        dbAgent.demonstrateDatabaseRetry();
        Thread.sleep(3000);

        // =====================================================================
        // SCENARIO 3: File Operations with Retry
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("SCENARIO 3: File operations with jittered retry");
        log.info("=".repeat(80) + "\n");

        fileAgent.demonstrateFileRetry();
        Thread.sleep(3000);

        // =====================================================================
        // SCENARIO 4: Message Processing with Selective Retry
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("SCENARIO 4: Message processing with selective retry");
        log.info("=".repeat(80) + "\n");

        msgAgent.demonstrateMessageRetry();
        Thread.sleep(3000);

        // =====================================================================
        // Metrics Summary
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("METRICS SUMMARY");
        log.info("=".repeat(80));

        apiAgent.printMetrics();
        dbAgent.printMetrics();
        fileAgent.printMetrics();
        msgAgent.printMetrics();

        // =====================================================================
        // Shutdown
        // =====================================================================

        log.info("\n\n" + "=".repeat(80));
        log.info("Shutting down Jentic Runtime...");
        log.info("=".repeat(80) + "\n");

        runtime.stop().get(10, TimeUnit.SECONDS);

        log.info("✅ RetryExample completed successfully\n");
    }

    // =========================================================================
    // SCENARIO 1: API Client Agent
    // =========================================================================

    @Agent(value = "api-client", type = "ApiClient",
                 capabilities = {"http", "retry", "monitoring"})
    public static class ApiClientAgent extends BaseAgent {

        private final SimulatedExternalApi externalApi = new SimulatedExternalApi();
        private RetryBehavior<String> apiRetryBehavior;

        public ApiClientAgent() { super("api-client", "API Client Agent"); }

        @Override
        protected void onStart() {
            log.info("🌐 API Client Agent started");

            apiRetryBehavior = new RetryBehavior<>(
                    "api-call-retry",
                    5,
                    RetryBehavior.BackoffStrategy.EXPONENTIAL,
                    Duration.ofMillis(100),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(2)
            ) {
                @Override
                protected String attemptAction() throws Exception {
                    return externalApi.callApi();
                }

                @Override
                protected boolean shouldRetry(Exception e) {
                    if (e instanceof ApiException apiEx) return apiEx.isRetryable();
                    return true;
                }
            };

            apiRetryBehavior
                    .onSuccess(r  -> log.info("   ✅ API call succeeded: {}", r))
                    .onFailure(e  -> log.error("   ❌ API call failed after all retries: {}", e.getMessage()))
                    .onRetry(n    -> log.info("   🔄 Retrying API call (attempt {})", n));

            addBehavior(apiRetryBehavior);
        }

        public void demonstrateApiRetry() {
            log.info("📡 Calling external API (will fail 3 times before succeeding)...");
            externalApi.setFailuresBeforeSuccess(3);
            try { apiRetryBehavior.execute().get(); } catch (Exception e) { log.error("Unexpected: {}", e.getMessage()); }
        }

        public void printMetrics() {
            log.info("\n📊 API Client Metrics:");
            log.info("   {}", apiRetryBehavior.getMetricsSummary());
        }
    }

    // =========================================================================
    // SCENARIO 2: Database Agent
    // =========================================================================

    @Agent(value = "database-agent", type = "Database",
                 capabilities = {"database", "retry", "transaction"})
    public static class DatabaseAgent extends BaseAgent {

        private final SimulatedDatabase database = new SimulatedDatabase();
        private RetryBehavior<Integer> dbRetryBehavior;

        public DatabaseAgent() { super("database-agent", "Database Agent"); }

        @Override
        protected void onStart() {
            log.info("💾 Database Agent started");

            dbRetryBehavior = new RetryBehavior<>(
                    "db-operation-retry",
                    3,
                    RetryBehavior.BackoffStrategy.LINEAR,
                    Duration.ofMillis(200)
            ) {
                @Override
                protected Integer attemptAction() throws Exception {
                    return database.executeQuery();
                }

                @Override
                protected boolean shouldRetry(Exception e) {
                    if (e instanceof DatabaseException dbEx) return dbEx.isTransient();
                    return false;
                }
            };

            dbRetryBehavior
                    .onSuccess(rows -> log.info("   ✅ Database query succeeded: {} rows affected", rows))
                    .onFailure(e   -> log.error("   ❌ Database query failed: {}", e.getMessage()))
                    .onRetry(n     -> log.warn("   🔄 Retrying database query (attempt {})", n));

            addBehavior(dbRetryBehavior);
        }

        public void demonstrateDatabaseRetry() {
            log.info("💾 Executing database query (will encounter deadlock twice)...");
            database.setDeadlocksBeforeSuccess(2);
            try { dbRetryBehavior.execute().get(); } catch (Exception e) { log.error("Unexpected: {}", e.getMessage()); }
        }

        public void printMetrics() {
            log.info("\n📊 Database Agent Metrics:");
            log.info("   {}", dbRetryBehavior.getMetricsSummary());
        }
    }

    // =========================================================================
    // SCENARIO 3: File Processor Agent
    // =========================================================================

    @Agent(value = "file-processor", type = "FileProcessor",
                 capabilities = {"file-io", "retry"})
    public static class FileProcessorAgent extends BaseAgent {

        private final SimulatedFileSystem fileSystem = new SimulatedFileSystem();
        private RetryBehavior<Boolean> fileRetryBehavior;

        public FileProcessorAgent() { super("file-processor", "File Processor Agent"); }

        @Override
        protected void onStart() {
            log.info("📁 File Processor Agent started");

            fileRetryBehavior = new RetryBehavior<>(
                    "file-operation-retry",
                    4,
                    RetryBehavior.BackoffStrategy.JITTER,
                    Duration.ofMillis(100)
            ) {
                @Override
                protected Boolean attemptAction() throws Exception {
                    return fileSystem.processFile();
                }

                @Override
                protected boolean shouldRetry(Exception e) {
                    return e instanceof IOException && e.getMessage().contains("locked");
                }
            };

            fileRetryBehavior
                    .onSuccess(ok -> log.info("   ✅ File processed successfully"))
                    .onFailure(e  -> log.error("   ❌ File processing failed: {}", e.getMessage()))
                    .onRetry(n   -> log.info("   🔄 Retrying file operation (attempt {})", n));

            addBehavior(fileRetryBehavior);
        }

        public void demonstrateFileRetry() {
            log.info("📁 Processing file (will be locked 3 times)...");
            fileSystem.setLocksBeforeSuccess(3);
            try { fileRetryBehavior.execute().get(); } catch (Exception e) { log.error("Unexpected: {}", e.getMessage()); }
        }

        public void printMetrics() {
            log.info("\n📊 File Processor Metrics:");
            log.info("   {}", fileRetryBehavior.getMetricsSummary());
        }
    }

    // =========================================================================
    // SCENARIO 4: Message Processor Agent
    // =========================================================================

    @Agent(value = "message-processor", type = "MessageProcessor",
                 capabilities = {"messaging", "retry", "validation"})
    public static class MessageProcessorAgent extends BaseAgent {

        private final SimulatedMessageQueue messageQueue = new SimulatedMessageQueue();
        private RetryBehavior<String> messageRetryBehavior;

        public MessageProcessorAgent() { super("message-processor", "Message Processor Agent"); }

        @Override
        protected void onStart() {
            log.info("📨 Message Processor Agent started");

            messageRetryBehavior = new RetryBehavior<>(
                    "message-processing-retry",
                    3,
                    RetryBehavior.BackoffStrategy.FIXED,
                    Duration.ofMillis(150)
            ) {
                @Override
                protected String attemptAction() throws Exception {
                    return messageQueue.processNextMessage();
                }
            };

            messageRetryBehavior.withRetryCondition(e -> {
                if (e instanceof MessageException msgEx) return msgEx.isTransient();
                return false;
            });

            messageRetryBehavior
                    .onSuccess(id -> log.info("   ✅ Message processed: {}", id))
                    .onFailure(e  -> log.error("   ❌ Message processing failed: {}", e.getMessage()))
                    .onRetry(n   -> log.warn("   🔄 Retrying message processing (attempt {})", n));

            addBehavior(messageRetryBehavior);
        }

        public void demonstrateMessageRetry() {
            log.info("📨 Processing message (will have transient errors)...");
            messageQueue.setTransientErrorsBeforeSuccess(2);
            try { messageRetryBehavior.execute().get(); } catch (Exception e) { log.error("Unexpected: {}", e.getMessage()); }
        }

        public void printMetrics() {
            log.info("\n📊 Message Processor Metrics:");
            log.info("   {}", messageRetryBehavior.getMetricsSummary());
        }
    }

    // =========================================================================
    // SIMULATED EXTERNAL SYSTEMS
    // =========================================================================

    static class SimulatedExternalApi {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private int failuresBeforeSuccess = 0;

        void setFailuresBeforeSuccess(int n) { failuresBeforeSuccess = n; callCount.set(0); }

        String callApi() throws ApiException {
            int call = callCount.incrementAndGet();
            if (call <= failuresBeforeSuccess) throw new ApiException("Network timeout", 503, true);
            return "API Response: Success (call #" + call + ")";
        }
    }

    static class ApiException extends Exception {
        private final int statusCode;
        private final boolean retryable;

        ApiException(String message, int statusCode, boolean retryable) {
            super(message);
            this.statusCode = statusCode;
            this.retryable = retryable;
        }

        int getStatusCode() { return statusCode; }
        boolean isRetryable() { return retryable; }
    }

    static class SimulatedDatabase {
        private final AtomicInteger queryCount = new AtomicInteger(0);
        private int deadlocksBeforeSuccess = 0;

        void setDeadlocksBeforeSuccess(int n) { deadlocksBeforeSuccess = n; queryCount.set(0); }

        int executeQuery() throws DatabaseException {
            int query = queryCount.incrementAndGet();
            if (query <= deadlocksBeforeSuccess) throw new DatabaseException("Deadlock detected", true);
            return ThreadLocalRandom.current().nextInt(1, 100);
        }
    }

    static class DatabaseException extends Exception {
        private final boolean transient_;

        DatabaseException(String message, boolean transient_) { super(message); this.transient_ = transient_; }
        boolean isTransient() { return transient_; }
    }

    static class SimulatedFileSystem {
        private final AtomicInteger operationCount = new AtomicInteger(0);
        private int locksBeforeSuccess = 0;

        void setLocksBeforeSuccess(int n) { locksBeforeSuccess = n; operationCount.set(0); }

        boolean processFile() throws IOException {
            int operation = operationCount.incrementAndGet();
            if (operation <= locksBeforeSuccess) throw new IOException("File is locked by another process");
            return true;
        }
    }

    static class SimulatedMessageQueue {
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private int transientErrorsBeforeSuccess = 0;

        void setTransientErrorsBeforeSuccess(int n) { transientErrorsBeforeSuccess = n; messageCount.set(0); }

        String processNextMessage() throws MessageException {
            int msg = messageCount.incrementAndGet();
            if (msg <= transientErrorsBeforeSuccess) throw new MessageException("Temporary processing error", true);
            return "MSG-" + msg;
        }
    }

    static class MessageException extends Exception {
        private final boolean transient_;

        MessageException(String message, boolean transient_) { super(message); this.transient_ = transient_; }
        boolean isTransient() { return transient_; }
    }
}
