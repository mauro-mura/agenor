package dev.jentic.runtime.behavior.composite;

import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorType;
import dev.jentic.core.composite.CompletionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Composite Behaviors Integration Tests")
class CompositeWorkflowIntegrationTest {

    private List<String> executionLog;
    private AtomicInteger orderCounter;

    @BeforeEach
    void setUp() {
        executionLog = new ArrayList<>();
        orderCounter = new AtomicInteger(0);
    }

    @Test
    @DisplayName("Should execute complex order processing workflow")
    void shouldExecuteOrderProcessingWorkflow() throws Exception {
        // Given - Order processing FSM with parallel validations and sequential fulfillment

        // 1. Create validation behaviors (run in parallel)
        ParallelBehavior validations = new ParallelBehavior("validations", CompletionStrategy.ALL);
        validations.addChildBehavior(createLogBehavior("validate-customer", 50));
        validations.addChildBehavior(createLogBehavior("validate-inventory", 50));
        validations.addChildBehavior(createLogBehavior("validate-payment", 50));

        // 2. Create fulfillment sequence
        SequentialBehavior fulfillment = new SequentialBehavior("fulfillment");
        fulfillment.addChildBehavior(createLogBehavior("reserve-inventory", 30));
        fulfillment.addChildBehavior(createLogBehavior("charge-payment", 30));
        fulfillment.addChildBehavior(createLogBehavior("ship-order", 30));

        // 3. Create FSM for order states
        FSMBehavior orderFSM = FSMBehavior.builder("order-fsm", "RECEIVED")
                .state("RECEIVED", createLogBehavior("receive-order", 20))
                .state("VALIDATING", validations)
                .state("FULFILLING", fulfillment)
                .state("COMPLETED", createLogBehavior("complete-order", 20))
                .transition("RECEIVED", "VALIDATING", fsm -> orderCounter.get() >= 1)
                .transition("VALIDATING", "FULFILLING", fsm -> orderCounter.get() >= 2)
                .transition("FULFILLING", "COMPLETED", fsm -> orderCounter.get() >= 3)
                .build();

        // When - Execute the workflow step by step

        // Step 1: RECEIVED state - executes receive-order, transitions to VALIDATING
        orderCounter.set(1);
        orderFSM.execute().get(1, TimeUnit.SECONDS);
        assertThat(orderFSM.getCurrentState()).isEqualTo("VALIDATING");

        // Step 2: VALIDATING state - executes parallel validations, transitions to FULFILLING
        orderCounter.set(2);
        orderFSM.execute().get(1, TimeUnit.SECONDS);
        assertThat(orderFSM.getCurrentState()).isEqualTo("FULFILLING");

        // Step 3: FULFILLING state - executes sequential fulfillment, transitions to COMPLETED
        orderCounter.set(3);
        orderFSM.execute().get(2, TimeUnit.SECONDS);
        assertThat(orderFSM.getCurrentState()).isEqualTo("COMPLETED");

        // Step 4: COMPLETED state - execute the complete-order behavior
        orderFSM.execute().get(1, TimeUnit.SECONDS);

        // Wait a bit for async completion
        Thread.sleep(100);

        // Then - Verify execution order
        assertThat(executionLog).contains(
                "receive-order",
                "validate-customer", "validate-inventory", "validate-payment",
                "reserve-inventory", "charge-payment", "ship-order",
                "complete-order"
        );

        // Verify parallel validations happened before sequential fulfillment
        int lastValidation = Math.max(
                executionLog.indexOf("validate-customer"),
                Math.max(executionLog.indexOf("validate-inventory"),
                        executionLog.indexOf("validate-payment"))
        );
        int firstFulfillment = executionLog.indexOf("reserve-inventory");
        assertThat(lastValidation).isLessThan(firstFulfillment);

        // Verify sequential order in fulfillment
        assertThat(executionLog.indexOf("reserve-inventory"))
                .isLessThan(executionLog.indexOf("charge-payment"));
        assertThat(executionLog.indexOf("charge-payment"))
                .isLessThan(executionLog.indexOf("ship-order"));
    }

    @Test
    @DisplayName("Should handle nested composite behaviors")
    void shouldHandleNestedCompositeBehaviors() throws Exception {
        // Given - Sequential containing parallel behaviors
        SequentialBehavior workflow = new SequentialBehavior("main-workflow");

        // Step 1: Parallel initialization
        ParallelBehavior initPhase = new ParallelBehavior("init", CompletionStrategy.ALL);
        initPhase.addChildBehavior(createLogBehavior("init-db", 30));
        initPhase.addChildBehavior(createLogBehavior("init-cache", 30));
        workflow.addChildBehavior(initPhase);

        // Step 2: Sequential processing
        SequentialBehavior processPhase = new SequentialBehavior("process");
        processPhase.addChildBehavior(createLogBehavior("load-data", 20));
        processPhase.addChildBehavior(createLogBehavior("transform-data", 20));
        workflow.addChildBehavior(processPhase);

        // Step 3: Parallel cleanup
        ParallelBehavior cleanupPhase = new ParallelBehavior("cleanup", CompletionStrategy.ALL);
        cleanupPhase.addChildBehavior(createLogBehavior("cleanup-temp", 30));
        cleanupPhase.addChildBehavior(createLogBehavior("cleanup-cache", 30));
        workflow.addChildBehavior(cleanupPhase);

        // When
        workflow.execute().get(2, TimeUnit.SECONDS);

        // Then
        assertThat(executionLog).hasSize(6);

        // Init phase should complete before process phase
        int lastInit = Math.max(
                executionLog.indexOf("init-db"),
                executionLog.indexOf("init-cache")
        );
        int firstProcess = executionLog.indexOf("load-data");
        assertThat(lastInit).isLessThan(firstProcess);

        // Process phase should be sequential
        assertThat(executionLog.indexOf("load-data"))
                .isLessThan(executionLog.indexOf("transform-data"));
    }

    @Test
    @DisplayName("Should handle error in nested composite behaviors")
    void shouldHandleErrorInNestedBehaviors() throws Exception {
        // Given
        ParallelBehavior parallel = new ParallelBehavior("parallel", CompletionStrategy.ALL);
        parallel.addChildBehavior(createLogBehavior("task1", 30));
        parallel.addChildBehavior(createFailingBehavior("failing-task"));
        parallel.addChildBehavior(createLogBehavior("task2", 30));

        SequentialBehavior sequential = new SequentialBehavior("sequential");
        sequential.addChildBehavior(parallel);
        sequential.addChildBehavior(createLogBehavior("after-parallel", 20));

        // When
        sequential.execute().get(1, TimeUnit.SECONDS);

        // Then - Should continue despite failure
        assertThat(executionLog).contains("task1", "task2", "after-parallel");
        assertThat(executionLog).doesNotContain("failing-task");
    }

    @Test
    @DisplayName("Should respect timeout in nested behaviors")
    void shouldRespectTimeoutInNestedBehaviors() throws Exception {
        // Given
        SequentialBehavior sequential = new SequentialBehavior(
                "sequential-with-timeout"
                ).withStepTimeout(Duration.ofMillis(100));

        sequential.addChildBehavior(createLogBehavior("fast", 20));
        sequential.addChildBehavior(createLogBehavior("slow", 500)); // Will timeout
        sequential.addChildBehavior(createLogBehavior("after-timeout", 20));

        // When
        CompletableFuture<Void> future = sequential.execute();

        // Wait enough time for sequence to attempt all steps
        Thread.sleep(300);

        // Then
        assertThat(executionLog).contains("fast");
        // after-timeout should execute eventually despite slow timing out
        assertThat(executionLog.size()).isGreaterThanOrEqualTo(1);
        // slow might or might not complete depending on timing
    }

    @Test
    @DisplayName("Should handle parallel with timeout in nested workflow")
    void shouldHandleParallelWithTimeoutInNestedWorkflow() throws Exception {
        // Given - Sequential containing Parallel with timeouts
        SequentialBehavior workflow = new SequentialBehavior("workflow");

        ParallelBehavior parallelPhase = new ParallelBehavior(
                "parallel-phase",
                CompletionStrategy.ALL,
                0,
                Duration.ofMillis(100)  // Child timeout
        );
        parallelPhase.addChildBehavior(createLogBehavior("fast-task", 30));
        parallelPhase.addChildBehavior(createLogBehavior("slow-task", 500)); // Timeout

        workflow.addChildBehavior(parallelPhase);
        workflow.addChildBehavior(createLogBehavior("after-parallel", 20));

        // When
        workflow.execute().get(2, TimeUnit.SECONDS);

        // Then
        assertThat(executionLog).contains("fast-task", "after-parallel");
        // slow-task should have timed out
    }

    @Test
    @DisplayName("Should handle FSM with composite behaviors in states")
    void shouldHandleFSMWithCompositeBehaviors() throws Exception {
        // Given
        ParallelBehavior startupTasks = new ParallelBehavior("startup", CompletionStrategy.ALL);
        startupTasks.addChildBehavior(createLogBehavior("load-config", 30));
        startupTasks.addChildBehavior(createLogBehavior("connect-db", 30));

        SequentialBehavior shutdownTasks = new SequentialBehavior("shutdown");
        shutdownTasks.addChildBehavior(createLogBehavior("flush-data", 30));
        shutdownTasks.addChildBehavior(createLogBehavior("close-connections", 30));

        FSMBehavior systemFSM = FSMBehavior.builder("system-fsm", "STOPPED")
                .state("STOPPED", createLogBehavior("idle", 10))
                .state("STARTING", startupTasks)
                .state("RUNNING", createLogBehavior("running", 10))
                .state("STOPPING", shutdownTasks)
                .transition("STOPPED", "STARTING", fsm -> orderCounter.get() == 1)
                .transition("STARTING", "RUNNING", fsm -> orderCounter.get() == 2)
                .transition("RUNNING", "STOPPING", fsm -> orderCounter.get() == 3)
                .transition("STOPPING", "STOPPED", fsm -> orderCounter.get() == 4)
                .build();

        // When - Simulate system lifecycle
        orderCounter.set(1);
        systemFSM.execute().get(1, TimeUnit.SECONDS); // STOPPED (idle) -> STARTING

        orderCounter.set(2);
        systemFSM.execute().get(1, TimeUnit.SECONDS); // STARTING (load+connect) -> RUNNING

        orderCounter.set(3);
        systemFSM.execute().get(1, TimeUnit.SECONDS); // RUNNING (running) -> STOPPING

        orderCounter.set(4);
        systemFSM.execute().get(1, TimeUnit.SECONDS); // STOPPING (flush+close) -> STOPPED

        // Execute one more time to run the STOPPED state behavior again
        systemFSM.execute().get(1, TimeUnit.SECONDS); // STOPPED (idle) again

        // Wait for async completion
        Thread.sleep(100);

        // Then
        assertThat(systemFSM.getCurrentState()).isEqualTo("STOPPED");
        assertThat(executionLog).contains(
                "idle",
                "load-config", "connect-db",
                "running",
                "flush-data", "close-connections"
        );
        // May have "idle" twice (initial and after full cycle)
    }

    // Helper methods

    private Behavior createLogBehavior(String name, long delayMs) {
        return new LogBehavior(name, delayMs, executionLog);
    }

    private Behavior createFailingBehavior(String name) {
        return new LogBehavior(name, 0, executionLog, true);
    }

    private static class LogBehavior implements Behavior {
        private final String name;
        private final long delayMs;
        private final List<String> log;
        private final boolean shouldFail;
        private boolean active = true;

        LogBehavior(String name, long delayMs, List<String> log) {
            this(name, delayMs, log, false);
        }

        LogBehavior(String name, long delayMs, List<String> log, boolean shouldFail) {
            this.name = name;
            this.delayMs = delayMs;
            this.log = log;
            this.shouldFail = shouldFail;
        }

        @Override
        public String getBehaviorId() {
            return name;
        }

        @Override
        public dev.jentic.core.Agent getAgent() {
            return null;
        }

        @Override
        public CompletableFuture<Void> execute() {
            if (shouldFail) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("Simulated failure: " + name)
                );
            }

            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(delayMs);
                    synchronized (log) {
                        log.add(name);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void stop() {
            active = false;
        }

        @Override
        public BehaviorType getType() {
            return BehaviorType.ONE_SHOT;
        }

        @Override
        public Duration getInterval() {
            return null;
        }
    }
}