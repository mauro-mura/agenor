package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ScheduledBehavior.
 */
class ScheduledBehaviorTest {

    private ScheduledBehavior behavior;
    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicReference<Exception> lastException = new AtomicReference<>();

    // Timing tolerance for slow systems
    private static final long TIMING_TOLERANCE_MS = 100;
    private static final int MAX_WAIT_SECONDS = 10;

    @BeforeEach
    void setUp() {
        executionCount.set(0);
        lastException.set(null);
    }

    @AfterEach
    void tearDown() {
        if (behavior != null && behavior.isActive()) {
            behavior.stop();
        }
    }

    // ========== CRON EXPRESSION TESTS ==========

    @Test
    void testCronExpressionParsing() {
        assertDoesNotThrow(() -> CronExpression.parse("0 0 * * * *"));
        assertDoesNotThrow(() -> CronExpression.parse("0 */15 * * * *"));
        assertDoesNotThrow(() -> CronExpression.parse("0 0 9 * * MON-FRI"));
        assertDoesNotThrow(() -> CronExpression.parse("0 30 8 * * *"));
    }

    @Test
    void testInvalidCronExpression() {
        assertThrows(IllegalArgumentException.class, () ->
                CronExpression.parse("invalid"));

        assertThrows(IllegalArgumentException.class, () ->
                CronExpression.parse("* * *"));

        assertThrows(IllegalArgumentException.class, () ->
                CronExpression.parse("60 0 * * * *"));
    }

    @Test
    void testCronExpressionMatching() {
        CronExpression cron = CronExpression.parse("0 30 9 * * *");

        ZonedDateTime matching = ZonedDateTime.of(
                2025, 10, 30, 9, 30, 0, 0, ZoneId.systemDefault()
        );
        assertTrue(cron.matches(matching));

        ZonedDateTime notMatching = ZonedDateTime.of(
                2025, 10, 30, 9, 31, 0, 0, ZoneId.systemDefault()
        );
        assertFalse(cron.matches(notMatching));
    }

    @Test
    void testCronNextExecution() {
        CronExpression cron = CronExpression.parse("0 0 * * * *");

        ZonedDateTime now = ZonedDateTime.of(
                2025, 10, 30, 9, 30, 0, 0, ZoneId.systemDefault()
        );

        ZonedDateTime next = cron.getNextExecution(now);
        assertNotNull(next);
        assertEquals(10, next.getHour());
        assertEquals(0, next.getMinute());
        assertEquals(0, next.getSecond());
    }

    @Test
    void testCronWithStepValues() {
        CronExpression cron = CronExpression.parse("0 */15 * * * *");

        ZonedDateTime now = ZonedDateTime.of(
                2025, 10, 30, 9, 0, 0, 0, ZoneId.systemDefault()
        );

        ZonedDateTime next = cron.getNextExecution(now);
        assertNotNull(next);
        assertEquals(15, next.getMinute());
    }

    @Test
    void testCronWithDayOfWeek() {
        CronExpression cron = CronExpression.parse("0 0 9 * * MON-FRI");

        ZonedDateTime sunday = ZonedDateTime.of(
                2025, 11, 2, 8, 0, 0, 0, ZoneId.systemDefault()
        );

        ZonedDateTime next = cron.getNextExecution(sunday);
        assertNotNull(next);
        assertEquals(DayOfWeek.MONDAY, next.getDayOfWeek());
        assertEquals(9, next.getHour());
    }

    // ========== BASIC BEHAVIOR TESTS ==========

    @Test
    @Timeout(5)
    void testBasicScheduledExecution() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        behavior = new ScheduledBehavior("test-behavior", "* * * * * *") {
            @Override
            protected void scheduledAction() {
                executionCount.incrementAndGet();
                latch.countDown();
            }
        };

        behavior.execute().join();

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(executionCount.get() > 0);
    }

    @Test
    void testBehaviorType() {
        behavior = createSimpleBehavior("* * * * * *");
        assertEquals(BehaviorType.SCHEDULED, behavior.getType());
    }

    @Test
    void testNextExecutionCalculation() {
        behavior = createSimpleBehavior("0 0 * * * *");

        ZonedDateTime next = behavior.getNextExecutionTime();
        assertNotNull(next);
        assertTrue(next.isAfter(ZonedDateTime.now()));

        Duration timeUntil = behavior.getTimeUntilNextExecution();
        assertNotNull(timeUntil);
        assertTrue(timeUntil.getSeconds() > 0);
    }

    // ========== FACTORY METHOD TESTS ==========

    @Test
    void testEveryHourFactory() {
        AtomicInteger count = new AtomicInteger(0);
        behavior = ScheduledBehavior.everyHour("hourly", count::incrementAndGet);

        assertNotNull(behavior);
        assertEquals("0 0 * * * *", behavior.getCronExpression());
    }

    @Test
    void testDailyFactory() {
        AtomicInteger count = new AtomicInteger(0);
        behavior = ScheduledBehavior.daily("daily", 9, 30, count::incrementAndGet);

        assertNotNull(behavior);
        assertTrue(behavior.getCronExpression().contains("30"));
        assertTrue(behavior.getCronExpression().contains("9"));
    }

    @Test
    void testWeekdaysFactory() {
        AtomicInteger count = new AtomicInteger(0);
        behavior = ScheduledBehavior.weekdays("weekdays", 8, 0, count::incrementAndGet);

        assertNotNull(behavior);
        assertTrue(behavior.getCronExpression().contains("MON-FRI"));
    }

    // ========== CALLBACK TESTS ==========

    @Test
    @Timeout(5)
    void testSuccessCallback() throws Exception {
        CountDownLatch successLatch = new CountDownLatch(1);
        AtomicReference<ScheduledBehavior> callbackBehavior = new AtomicReference<>();

        behavior = createSimpleBehavior("* * * * * *");
        behavior.onSuccess(b -> {
            callbackBehavior.set(b);
            successLatch.countDown();
        });

        behavior.execute().join();

        assertTrue(successLatch.await(3, TimeUnit.SECONDS));
        assertNotNull(callbackBehavior.get());
        assertEquals(behavior, callbackBehavior.get());
    }

    @Test
    @Timeout(5)
    void testFailureCallback() throws Exception {
        CountDownLatch failureLatch = new CountDownLatch(1);

        behavior = new ScheduledBehavior("failing", "* * * * * *") {
            @Override
            protected void scheduledAction() throws Exception {
                throw new RuntimeException("Test failure");
            }
        };

        behavior.onFailure(e -> {
            lastException.set(e);
            failureLatch.countDown();
        });

        behavior.execute().join();

        assertTrue(failureLatch.await(3, TimeUnit.SECONDS));
        assertNotNull(lastException.get());
        assertEquals("Test failure", lastException.get().getMessage());
    }

    // ========== EXECUTION TIMEOUT TESTS ==========

    @Test
    @Timeout(10)
    void testExecutionTimeout() throws Exception {
        CountDownLatch failureLatch = new CountDownLatch(1);

        // Use longer timeout margin to avoid flakiness
        behavior = new ScheduledBehavior("timeout-test", "* * * * * *") {
            @Override
            protected void scheduledAction() throws Exception {
                Thread.sleep(2000);
            }
        };

        // Shorter timeout but with more margin from sleep time
        behavior.setExecutionTimeout(Duration.ofMillis(200));
        behavior.onFailure(e -> failureLatch.countDown());

        behavior.execute().join();

        // Longer wait to ensure timeout has occurred
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(behavior.getFailedExecutions() > 0);
    }

    // ========== MISSED EXECUTION TESTS ==========

    @Test
    void testMissedExecutionPolicy() {
        behavior = createSimpleBehavior("0 0 * * * *");

        behavior.setMissedExecutionPolicy(ScheduledBehavior.MissedExecutionPolicy.SKIP);

        assertDoesNotThrow(() ->
                behavior.setMissedExecutionPolicy(ScheduledBehavior.MissedExecutionPolicy.EXECUTE_ONCE)
        );
    }

    // ========== METRICS TESTS ==========

    @Test
    @Timeout(10)
    void testExecutionMetrics() throws Exception {
        CountDownLatch successLatch = new CountDownLatch(2);

        behavior = new ScheduledBehavior("metrics-test", "* * * * * *") {
            @Override
            protected void scheduledAction() {
                executionCount.incrementAndGet();
            }
        };

        // Use onSuccess callback: it fires AFTER successfulExecutions is incremented
        behavior.onSuccess(b -> successLatch.countDown());

        behavior.execute().join();

        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Expected 2 successful executions within 5 seconds");

        // Use >= instead of exact match to handle timing variations
        assertTrue(behavior.getTotalExecutions() >= 2,
                "Expected at least 2 executions, got " + behavior.getTotalExecutions());
        assertTrue(behavior.getSuccessfulExecutions() >= 2,
                "Expected at least 2 successful executions, got " + behavior.getSuccessfulExecutions());
        assertEquals(0, behavior.getFailedExecutions());
        assertEquals(1.0, behavior.getSuccessRate(), 0.01);
    }

    @Test
    @Timeout(5)
    void testAverageExecutionTime() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong executionStartTime = new AtomicLong();

        // FIX: Track actual execution time instead of assuming sleep duration
        behavior = new ScheduledBehavior("timing-test", "* * * * * *") {
            @Override
            protected void scheduledAction() throws Exception {
                long start = System.currentTimeMillis();
                executionStartTime.set(start);
                Thread.sleep(100);  // FIX: Increased from 50ms for more reliable timing
                latch.countDown();
            }
        };

        behavior.execute().join();

        assertTrue(latch.await(3, TimeUnit.SECONDS));

        // Wait for metrics to be updated (race condition between execution completion and metrics calculation)
        long avgTime = 0;
        long deadline = System.currentTimeMillis() + 2000;
        while (avgTime == 0 && System.currentTimeMillis() < deadline) {
            avgTime = (long) behavior.getAverageExecutionTimeMs();
            if (avgTime == 0) {
                Thread.sleep(50);
            }
        }

        // More lenient check with tolerance for system variations
        // Expect at least 70ms (allowing 30% margin below 100ms sleep)
        assertTrue(avgTime >= 70,
                String.format("Expected execution time >= 70ms but was %dms (with 100ms sleep)", avgTime));

        // FIX: Also check upper bound to catch timing anomalies
        assertTrue(avgTime <= 500,
                String.format("Execution time suspiciously high: %dms (expected ~100ms)", avgTime));
    }

    @Test
    void testMetricsSummary() {
        behavior = createSimpleBehavior("0 0 * * * *");

        String summary = behavior.getMetricsSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("metrics-test"));
        assertTrue(summary.contains("total="));
        assertTrue(summary.contains("success="));
        assertTrue(summary.contains("failed="));
    }

    // ========== TIMEZONE TESTS ==========

    @Test
    void testTimezoneHandling() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        behavior = new ScheduledBehavior("tokyo-behavior", "0 0 9 * * *", tokyo) {
            @Override
            protected void scheduledAction() {
                executionCount.incrementAndGet();
            }
        };

        assertEquals(tokyo, behavior.getTimezone());
        assertNotNull(behavior.getNextExecutionTime());
    }

    @Test
    void testDefaultTimezone() {
        behavior = createSimpleBehavior("0 0 * * * *");
        assertEquals(ZoneId.systemDefault(), behavior.getTimezone());
    }

    // ========== LIFECYCLE TESTS ==========

    @Test
    void testBehaviorStop() {
        behavior = createSimpleBehavior("* * * * * *");

        assertTrue(behavior.isActive());
        behavior.stop();
        assertFalse(behavior.isActive());
    }

    @Test
    @Timeout(5)
    void testExecutionState() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch executingCheckLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);
        AtomicReference<Boolean> wasExecuting = new AtomicReference<>(false);

        behavior = new ScheduledBehavior("state-test", "* * * * * *") {
            @Override
            protected void scheduledAction() throws Exception {
                startLatch.countDown();
                executingCheckLatch.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                finishLatch.countDown();
            }
        };

        CompletableFuture<Void> future = behavior.execute();

        // Wait for execution to start
        assertTrue(startLatch.await(2, TimeUnit.SECONDS));

        // Store executing state reliably
        wasExecuting.set(behavior.isExecuting());
        executingCheckLatch.countDown();

        // Check the stored state value instead of racing with completion
        assertTrue(wasExecuting.get(), "Behavior should have been executing");

        future.join();
        behavior.stop();
        assertFalse(behavior.isExecuting());
    }

    // ========== CONCURRENT EXECUTION TESTS ==========

    @Test
    @Timeout(10)
    void testNoConcurrentExecution() throws Exception {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch firstExecutionCanFinish = new CountDownLatch(1);
        AtomicInteger executionCounter = new AtomicInteger(0);

        behavior = new ScheduledBehavior("concurrent-test", "* * * * * *") {
            @Override
            protected void scheduledAction() throws Exception {
                int execNum = executionCounter.incrementAndGet();
                int current = concurrent.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));

                if (execNum == 1) {
                    executionStarted.countDown();
                    // Wait for signal instead of fixed sleep
                    firstExecutionCanFinish.await(5, TimeUnit.SECONDS);
                }

                concurrent.decrementAndGet();
            }
        };

        // Start first execution
        CompletableFuture<Void> first = behavior.execute();

        // Wait for first execution to start
        assertTrue(executionStarted.await(2, TimeUnit.SECONDS));

        // Try to execute while first is running
        CompletableFuture<Void> second = behavior.execute();
        CompletableFuture<Void> third = behavior.execute();

        // Give some time for concurrent attempts
        Thread.sleep(100);

        // Release first execution
        firstExecutionCanFinish.countDown();

        // Wait for all to complete
        CompletableFuture.allOf(first, second, third).join();

        // Should never have more than 1 concurrent execution
        assertEquals(1, maxConcurrent.get(),
                "Expected max 1 concurrent execution, but got " + maxConcurrent.get());
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    void testEmptyCronExpression() {
        assertThrows(IllegalArgumentException.class, () ->
                new ScheduledBehavior("empty-cron", "") {
                    @Override
                    protected void scheduledAction() {}
                }
        );
    }

    @Test
    void testNullCronExpression() {
        assertThrows(IllegalArgumentException.class, () ->
                new ScheduledBehavior("null-cron", null) {
                    @Override
                    protected void scheduledAction() {}
                }
        );
    }

    @Test
    @Timeout(5)
    void testLastExecutionTime() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        behavior = new ScheduledBehavior("last-exec-test", "* * * * * *") {
            @Override
            protected void scheduledAction() {
                latch.countDown();
            }
        };

        assertNull(behavior.getLastExecutionTime());

        behavior.execute().join();
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        // Wait for the lastExecutionTime to be set (race condition between action completion and time setting)
        ZonedDateTime lastExec = null;
        long deadline = System.currentTimeMillis() + 2000;
        while (lastExec == null && System.currentTimeMillis() < deadline) {
            lastExec = behavior.getLastExecutionTime();
            if (lastExec == null) {
                Thread.sleep(50);
            }
        }

        assertNotNull(lastExec, "lastExecutionTime should be set after execution");

        // More lenient time check with tolerance
        ZonedDateTime now = ZonedDateTime.now();
        assertTrue(lastExec.isBefore(now.plusSeconds(2)),
                "Last execution time should be before now + 2s tolerance");
        assertTrue(lastExec.isAfter(now.minusSeconds(10)),
                "Last execution time should be after now - 10s tolerance");
    }

    // ========== HELPER METHODS ==========

    private ScheduledBehavior createSimpleBehavior(String cron) {
        return new ScheduledBehavior("metrics-test", cron) {
            @Override
            protected void scheduledAction() {
                executionCount.incrementAndGet();
            }
        };
    }
}
