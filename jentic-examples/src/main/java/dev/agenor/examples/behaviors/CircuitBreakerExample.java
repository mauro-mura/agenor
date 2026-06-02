package dev.agenor.examples.behaviors;

import dev.agenor.runtime.behavior.advanced.CircuitBreakerBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating CircuitBreakerBehavior usage
 * for protecting against cascading failures in external service calls.
 */
public class CircuitBreakerExample {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerExample.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("=".repeat(80));
        log.info("CircuitBreaker Pattern - Practical Example");
        log.info("=".repeat(80));

        // Run all scenarios
        scenario1_BasicProtection();
        scenario2_RecoveryBehavior();
        scenario3_MetricsAndMonitoring();
        scenario4_RealWorldApiClient();

        log.info("\n" + "=".repeat(80));
        log.info("Example completed successfully!");
        log.info("=".repeat(80));
    }

    /**
     * Scenario 1: Basic circuit breaker protection
     */
    private static void scenario1_BasicProtection() throws InterruptedException {
        log.info("\n--- Scenario 1: Basic Protection ---");

        var unstableService = new UnstableExternalService(0.7); // 70% failure rate

        var breaker = new CircuitBreakerBehavior<String>(
            "external-api-breaker",
            3,                          // Open after 3 failures
            Duration.ofSeconds(5),      // Try recovery after 5s
            2                           // Close after 2 successes
        ) {
            @Override
            protected String executeAction() throws Exception {
                return unstableService.call();
            }

            @Override
            protected String fallback(Exception e) {
                return "FALLBACK: Service unavailable";
            }
        };

        // Track state changes
        breaker.onStateChange(state ->
            log.info("⚡ Circuit state changed: {}", state)
        );

        // Make multiple calls
        log.info("Making calls to unstable service...");
        for (int i = 1; i <= 10; i++) {
            try {
                String result = breaker.call();
                log.info("  Call {}: ✓ {}", i, result);
            } catch (CircuitBreakerBehavior.CircuitBreakerOpenException e) {
                log.warn("  Call {}: ✗ Circuit OPEN - request rejected", i);
            } catch (Exception e) {
                log.warn("  Call {}: ✗ Failed - {}", i, e.getMessage());
            }
            Thread.sleep(200);
        }

        log.info("\nFinal state: {}", breaker.getCurrentState());
        log.info("Metrics: {}", breaker.getMetrics());
    }

    /**
     * Scenario 2: Automatic recovery behavior
     */
    private static void scenario2_RecoveryBehavior() throws InterruptedException {
        log.info("\n--- Scenario 2: Automatic Recovery ---");

        var service = new UnstableExternalService(1.0); // Start with 100% failure

        var breaker = CircuitBreakerBehavior.standard(
            "recovery-breaker",
            () -> service.call()
        );

        breaker.onStateChange(state ->
            log.info("⚡ State: {}", state)
        );

        // Phase 1: Trip the circuit
        log.info("\nPhase 1: Tripping circuit with failures...");
        for (int i = 1; i <= 5; i++) {
            try {
                breaker.call();
            } catch (Exception e) {
                log.warn("  Failure {}: {}", i, e.getMessage());
            }
            Thread.sleep(100);
        }

        log.info("Circuit is now: {}", breaker.getCurrentState());

        // Phase 2: Wait for recovery timeout
        log.info("\nPhase 2: Waiting for recovery timeout (5 seconds)...");
        Thread.sleep(5500);

        // Phase 3: Service recovers
        log.info("\nPhase 3: Service recovers, making successful calls...");
        service.setFailureRate(0.0); // Service is now healthy

        for (int i = 1; i <= 5; i++) {
            try {
                String result = breaker.call();
                log.info("  Success {}: {}", i, result);
            } catch (Exception e) {
                log.warn("  Failed {}: {}", i, e.getMessage());
            }
            Thread.sleep(200);
        }

        log.info("\nFinal state: {} (circuit recovered!)", breaker.getCurrentState());
    }

    /**
     * Scenario 3: Metrics and monitoring
     */
    private static void scenario3_MetricsAndMonitoring() throws InterruptedException {
        log.info("\n--- Scenario 3: Metrics & Monitoring ---");

        var service = new UnstableExternalService(0.3); // 30% failure rate

        var breaker = CircuitBreakerBehavior.custom(
            "monitored-breaker",
            () -> service.call(),
            5,                      // Higher threshold
            Duration.ofSeconds(10),
            3
        );

        // Set up comprehensive monitoring
        breaker.onSuccess(result ->
            log.debug("✓ Success: {}", result)
        );

        breaker.onFailure(ex ->
            log.debug("✗ Failure: {}", ex.getMessage())
        );

        breaker.onStateChange(state ->
            log.warn("⚡ STATE CHANGE: {}", state)
        );

        // Execute many requests
        log.info("Executing 50 requests with monitoring...");
        int successCount = 0;
        int failureCount = 0;
        int rejectedCount = 0;

        for (int i = 1; i <= 50; i++) {
            try {
                breaker.call();
                successCount++;
            } catch (CircuitBreakerBehavior.CircuitBreakerOpenException e) {
                rejectedCount++;
            } catch (Exception e) {
                failureCount++;
            }

            // Print metrics every 10 requests
            if (i % 10 == 0) {
                var metrics = breaker.getMetrics();
                log.info("\n--- After {} requests ---", i);
                log.info("State: {}", metrics.currentState());
                log.info("Success rate: {:.1f}%", metrics.successRate());
                log.info("Failure rate: {:.1f}%", metrics.failureRate());
                log.info("Rejected: {}", metrics.rejectedRequests());
            }

            Thread.sleep(50);
        }

        log.info("\n=== Final Statistics ===");
        log.info("Successful: {}", successCount);
        log.info("Failed: {}", failureCount);
        log.info("Rejected: {}", rejectedCount);
        log.info("\nDetailed Metrics:\n{}", breaker.getMetrics());
    }

    /**
     * Scenario 4: Real-world API client with circuit breaker
     */
    private static void scenario4_RealWorldApiClient() throws InterruptedException {
        log.info("\n--- Scenario 4: Real-World API Client ---");

        var apiClient = new ProtectedApiClient();

        log.info("Simulating API calls with varying service health...");

        // Start with healthy service
        apiClient.setServiceHealth(0.9); // 90% success rate
        log.info("\nPhase 1: Healthy service (90% success rate)");
        executeApiCalls(apiClient, 10);

        // Service degrades
        apiClient.setServiceHealth(0.3); // 30% success rate
        log.info("\nPhase 2: Degraded service (30% success rate)");
        executeApiCalls(apiClient, 10);

        // Wait for recovery
        log.info("\nPhase 3: Waiting for circuit recovery...");
        Thread.sleep(3000);

        // Service recovers
        apiClient.setServiceHealth(1.0); // 100% success rate
        log.info("\nPhase 4: Service recovered (100% success rate)");
        executeApiCalls(apiClient, 10);

        // Print final report
        log.info("\n=== API Client Statistics ===");
        apiClient.printStatistics();
    }

    private static void executeApiCalls(ProtectedApiClient client, int count)
            throws InterruptedException {
        for (int i = 1; i <= count; i++) {
            String result = client.fetchData("user-" + i);
            log.info("  Call {}: {}", i, result);
            Thread.sleep(100);
        }
    }

    // ==================== HELPER CLASSES ====================

    /**
     * Simulated unstable external service
     */
    static class UnstableExternalService {
        private final Random random = new Random();
        private volatile double failureRate;
        private final AtomicInteger callCount = new AtomicInteger(0);

        public UnstableExternalService(double failureRate) {
            this.failureRate = Math.max(0.0, Math.min(1.0, failureRate));
        }

        public String call() throws Exception {
            int count = callCount.incrementAndGet();

            // Simulate network latency
            Thread.sleep(50 + random.nextInt(100));

            if (random.nextDouble() < failureRate) {
                throw new ServiceException("Service temporarily unavailable");
            }

            return String.format("Response #%d at %s", count, Instant.now());
        }

        public void setFailureRate(double rate) {
            this.failureRate = Math.max(0.0, Math.min(1.0, rate));
        }
    }

    /**
     * Real-world example: API client with circuit breaker protection
     */
    static class ProtectedApiClient {
        private final CircuitBreakerBehavior<String> circuitBreaker;
        private final UnstableExternalService backendService;
        private final AtomicInteger totalCalls = new AtomicInteger(0);
        private final AtomicInteger successfulCalls = new AtomicInteger(0);
        private final AtomicInteger failedCalls = new AtomicInteger(0);
        private final AtomicInteger fallbackCalls = new AtomicInteger(0);

        public ProtectedApiClient() {
            this.backendService = new UnstableExternalService(0.5);

            this.circuitBreaker = new CircuitBreakerBehavior<>(
                "api-client-breaker",
                3,                          // Open after 3 failures
                Duration.ofSeconds(3),      // 3s recovery timeout
                2                           // Close after 2 successes
            ) {
                @Override
                protected String executeAction() throws Exception {
                    return backendService.call();
                }

                @Override
                protected String fallback(Exception e) {
                    fallbackCalls.incrementAndGet();
                    return "[CACHED] Fallback data";
                }
            };

            // Setup monitoring
            circuitBreaker.onSuccess(result -> successfulCalls.incrementAndGet());
            circuitBreaker.onFailure(ex -> failedCalls.incrementAndGet());
        }

        public String fetchData(String userId) {
            totalCalls.incrementAndGet();

            try {
                return circuitBreaker.call();
            } catch (CircuitBreakerBehavior.CircuitBreakerOpenException e) {
                // Circuit is open, return fallback immediately
                fallbackCalls.incrementAndGet();
                return "[CACHED] Fallback data (circuit open)";
            } catch (Exception e) {
                // Unexpected error, return fallback
                fallbackCalls.incrementAndGet();
                return "[ERROR] " + e.getMessage();
            }
        }

        public void setServiceHealth(double successRate) {
            backendService.setFailureRate(1.0 - successRate);
        }

        public void printStatistics() {
            var metrics = circuitBreaker.getMetrics();

            log.info("Total calls: {}", totalCalls.get());
            log.info("Successful: {}", successfulCalls.get());
            log.info("Failed: {}", failedCalls.get());
            log.info("Fallbacks: {}", fallbackCalls.get());
            log.info("Circuit state: {}", metrics.currentState());
            log.info("State changes: {}", metrics.stateChangeCount());
            log.info("\nCircuit breaker metrics:\n{}", metrics);
        }
    }

    /**
     * Custom exception for service failures
     */
    static class ServiceException extends Exception {
        public ServiceException(String message) {
            super(message);
        }
    }
}
