package dev.agenor.runtime.behavior.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorType;

@DisplayName("Sequential Behavior Tests")
class SequentialBehaviorTest {

    // Dedicated thread pool avoids FJP common-pool starvation on constrained CI runners
    private static final ExecutorService TEST_EXECUTOR = Executors.newCachedThreadPool();

    private SequentialBehavior sequentialBehavior;
    private List<String> executionOrder;
    private AtomicInteger executionCount;

    @BeforeEach
    void setUp() {
        sequentialBehavior = new SequentialBehavior("test-sequential");
        executionOrder = new ArrayList<>();
        executionCount = new AtomicInteger(0);
    }

    // -------------------------------------------------------------------------
    // One-shot mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should execute behaviors in sequence")
    void shouldExecuteInSequence() throws Exception {
        // Given
        sequentialBehavior.addChildBehavior(createTestBehavior("step1", 50));
        sequentialBehavior.addChildBehavior(createTestBehavior("step2", 50));
        sequentialBehavior.addChildBehavior(createTestBehavior("step3", 50));

        // When
        CompletableFuture<Void> future = sequentialBehavior.execute();
        future.get(2, TimeUnit.SECONDS);

        // Then
        assertThat(executionOrder).containsExactly("step1", "step2", "step3");
        assertThat(sequentialBehavior.isActive()).isFalse();
        assertThat(sequentialBehavior.getCurrentStep()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should continue after step failure")
    void shouldContinueAfterStepFailure() throws Exception {
        // Given
        sequentialBehavior.addChildBehavior(createTestBehavior("step1", 50));
        sequentialBehavior.addChildBehavior(createFailingBehavior("step2"));
        sequentialBehavior.addChildBehavior(createTestBehavior("step3", 50));

        // When
        sequentialBehavior.execute().get(2, TimeUnit.SECONDS);

        // Then - step2 fails but step3 still executes
        assertThat(executionOrder).contains("step1", "step3");
        assertThat(executionOrder).doesNotContain("step2");
    }

    @Test
    @DisplayName("Should reset sequence to beginning")
    void shouldResetSequence() throws Exception {
        // Given
        sequentialBehavior.addChildBehavior(createTestBehavior("step1", 20));
        sequentialBehavior.addChildBehavior(createTestBehavior("step2", 20));

        // When
        sequentialBehavior.execute().get(1, TimeUnit.SECONDS);
        assertThat(sequentialBehavior.getCurrentStep()).isEqualTo(2);

        sequentialBehavior.reset();

        // Then
        assertThat(sequentialBehavior.getCurrentStep()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Repeating mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should advance one step per execute() call in repeating mode")
    void shouldRepeatSequence() throws Exception {
        // Given — interval present → repeating mode
        sequentialBehavior = new SequentialBehavior("test-sequential", Duration.ofMillis(20));
        sequentialBehavior.addChildBehavior(createTestBehavior("step1", 20));
        sequentialBehavior.addChildBehavior(createTestBehavior("step2", 20));

        // When - each execute() advances exactly one step
        sequentialBehavior.execute().get(1, TimeUnit.SECONDS); // step1, index: 0→1
        sequentialBehavior.execute().get(1, TimeUnit.SECONDS); // step2, index: 1→2→0 (wrap)
        sequentialBehavior.execute().get(1, TimeUnit.SECONDS); // step1 again, index: 0→1
        sequentialBehavior.execute().get(1, TimeUnit.SECONDS); // step2 again, index: 1→2→0

        // Then - wrapped round-robin, still active
        assertThat(executionOrder).containsExactly("step1", "step2", "step1", "step2");
        assertThat(sequentialBehavior.isActive()).isTrue();
        assertThat(sequentialBehavior.getCurrentStep()).isEqualTo(0);
    }

    @Test
    @DisplayName("isRepeating() should reflect construction mode")
    void shouldReportRepeatingMode() {
        assertThat(new SequentialBehavior("one-shot").isRepeating()).isFalse();
        assertThat(new SequentialBehavior("repeating", Duration.ofSeconds(1)).isRepeating()).isTrue();
    }

    @Test
    @DisplayName("Repeating constructor should reject null interval")
    void shouldRejectNullIntervalOnRepeatingConstructor() {
        assertThatThrownBy(() -> new SequentialBehavior("bad", (Duration) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval must not be null");
    }

    // -------------------------------------------------------------------------
    // Step timeout
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should skip timed-out step and continue")
    void shouldHandleStepTimeout() throws Exception {
        // Given — 100ms timeout, step2 takes 500ms
        sequentialBehavior = new SequentialBehavior("test-sequential")
                .withStepTimeout(Duration.ofMillis(100));
        sequentialBehavior.addChildBehavior(createTestBehavior("step1", 50));
        sequentialBehavior.addChildBehavior(createTestBehavior("step2", 500)); // will time out
        sequentialBehavior.addChildBehavior(createTestBehavior("step3", 50));

        // When
        sequentialBehavior.execute();
        Thread.sleep(400);

        // Then
        assertThat(executionOrder).contains("step1", "step3");
        assertThat(executionOrder).doesNotContain("step2");
    }

    @Test
    @DisplayName("Should get and set step timeout")
    void shouldGetAndSetStepTimeout() {
        // Given
        Duration initialTimeout = Duration.ofSeconds(10);
        sequentialBehavior = new SequentialBehavior("test-sequential")
                .withStepTimeout(initialTimeout);

        assertThat(sequentialBehavior.getStepTimeout()).isEqualTo(initialTimeout);

        // When
        Duration newTimeout = Duration.ofSeconds(30);
        sequentialBehavior.setStepTimeout(newTimeout);

        // Then
        assertThat(sequentialBehavior.getStepTimeout()).isEqualTo(newTimeout);
    }

    @Test
    @DisplayName("Should work without timeout")
    void shouldWorkWithoutTimeout() throws Exception {
        // Given — default constructor has no timeout
        sequentialBehavior.addChildBehavior(createTestBehavior("step1", 50));
        sequentialBehavior.addChildBehavior(createTestBehavior("step2", 50));

        // When
        sequentialBehavior.execute().get(2, TimeUnit.SECONDS);

        // Then
        assertThat(executionOrder).containsExactly("step1", "step2");
        assertThat(sequentialBehavior.getStepTimeout()).isNull();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should return SEQUENTIAL type")
    void shouldReturnSequentialType() {
        assertThat(sequentialBehavior.getType()).isEqualTo(BehaviorType.SEQUENTIAL);
    }

    @Test
    @DisplayName("Should handle empty child behaviors")
    void shouldHandleEmptyChildren() throws Exception {
        CompletableFuture<Void> future = sequentialBehavior.execute();
        future.get(1, TimeUnit.SECONDS);

        assertThat(executionOrder).isEmpty();
        assertThat(sequentialBehavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should stop all child behaviors when stopped")
    void shouldStopChildBehaviors() {
        // Given
        TestBehavior child1 = new TestBehavior("child1", 100);
        TestBehavior child2 = new TestBehavior("child2", 100);
        sequentialBehavior.addChildBehavior(child1);
        sequentialBehavior.addChildBehavior(child2);

        // When
        sequentialBehavior.stop();

        // Then
        assertThat(sequentialBehavior.isActive()).isFalse();
        assertThat(child1.isActive()).isFalse();
        assertThat(child2.isActive()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Behavior createTestBehavior(String name, long delayMs) {
        return new TestBehavior(name, delayMs);
    }

    private Behavior createFailingBehavior(String name) {
        return new TestBehavior(name, 0, true);
    }

    private class TestBehavior implements Behavior {
        private final String name;
        private final long delayMs;
        private final boolean shouldFail;
        private boolean active = true;

        TestBehavior(String name, long delayMs) {
            this(name, delayMs, false);
        }

        TestBehavior(String name, long delayMs, boolean shouldFail) {
            this.name = name;
            this.delayMs = delayMs;
            this.shouldFail = shouldFail;
        }

        @Override
        public String getBehaviorId() { return name; }

        @Override
        public BehaviorType getType() { return BehaviorType.ONE_SHOT; }

        @Override
        public boolean isActive() { return active; }

        @Override
        public void stop() { active = false; }

        @Override
        public dev.agenor.core.Agent getAgent() { return null; }

        @Override
        public Duration getInterval() { return null; }

        @Override
        public CompletableFuture<Void> execute() {
            return CompletableFuture.runAsync(() -> {
                if (shouldFail) {
                    throw new RuntimeException("Simulated failure in " + name);
                }
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return; // don't record if interrupted
                    }
                }
                // Add after sleep so timed-out steps are not recorded
                // (the future has already failed before this line runs)
                executionOrder.add(name);
                executionCount.incrementAndGet();
            }, TEST_EXECUTOR);
        }
    }
}
