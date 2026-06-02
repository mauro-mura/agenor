package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.Agent;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for BatchBehavior
 */
class BatchBehaviorTest {

    private Agent testAgent;

    static class TestAgent extends BaseAgent {
        TestAgent() { super("test-agent", "Test Agent"); }
    }

    @BeforeEach
    void setUp() {
        testAgent = new TestAgent();
    }

    @Test
    @DisplayName("Should create batch behavior with valid parameters")
    void testCreation() {
        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 10, items -> {});

        assertThat(batch).isNotNull();
        assertThat(batch.getBehaviorId()).isEqualTo("test-batch");
        assertThat(batch.getMaxBatchSize()).isEqualTo(10);
        assertThat(batch.getCurrentBatchSize()).isZero();
        assertThat(batch.getQueueSize()).isZero();
    }

    @Test
    @DisplayName("Should throw exception for invalid batch size")
    void testInvalidBatchSize() {
        assertThatThrownBy(() -> BatchBehavior.simple("test", 0, items -> {}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> BatchBehavior.simple("test", -5, items -> {}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("Should add items to batch queue")
    void testAddItems() {
        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 10, items -> {});
        batch.setAgent(testAgent);

        boolean added1 = batch.add("item1");
        boolean added2 = batch.add("item2");
        boolean added3 = batch.add("item3");

        assertThat(added1).isTrue();
        assertThat(added2).isTrue();
        assertThat(added3).isTrue();
        assertThat(batch.getQueueSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should not add null items")
    void testAddNullItem() {
        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 10, items -> {});
        batch.setAgent(testAgent);

        boolean added = batch.add(null);

        assertThat(added).isFalse();
        assertThat(batch.getQueueSize()).isZero();
    }

    @Test
    @DisplayName("Should process batch when size trigger reached")
    void testSizeTrigger() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> processedItems = new ArrayList<>();

        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 5, items -> {
            processedItems.addAll(items);
            latch.countDown();
        });
        batch.setAgent(testAgent);

        // Add 5 items (reaches max batch size)
        for (int i = 1; i <= 5; i++) {
            batch.add("item" + i);
        }

        // Execute behavior to process batch
        batch.execute().get(1, TimeUnit.SECONDS);

        // Wait for processing
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify batch was processed
        assertThat(processedItems).hasSize(5);
        assertThat(processedItems).containsExactly("item1", "item2", "item3", "item4", "item5");
        assertThat(batch.getTotalItemsProcessed()).isEqualTo(5);
        assertThat(batch.getTotalBatchesProcessed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should process batch when time trigger reached")
    void testTimeTrigger() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> processedItems = new ArrayList<>();

        BatchBehavior<String> batch = BatchBehavior.withTimeout(
            "test-batch",
            10,  // Max batch size
            Duration.ofMillis(200),  // Max wait time
            items -> {
                processedItems.addAll(items);
                latch.countDown();
            }
        );
        batch.setAgent(testAgent);

        // Add only 3 items (less than max)
        batch.add("item1");
        batch.add("item2");
        batch.add("item3");

        // Wait for time trigger (need multiple executions)
        Thread.sleep(50); // Wait a bit
        batch.execute().get();

        Thread.sleep(200); // Wait past timeout
        batch.execute().get();

        // Wait for processing
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify partial batch was processed
        assertThat(processedItems).hasSize(3);
        assertThat(batch.getTotalItemsProcessed()).isEqualTo(3);
        assertThat(batch.getTotalBatchesProcessed()).isEqualTo(1);
        assertThat(batch.getPartialBatchesProcessed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should flush partial batch on demand")
    void testManualFlush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> processedItems = new ArrayList<>();

        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 10, items -> {
            processedItems.addAll(items);
            latch.countDown();
        });
        batch.setAgent(testAgent);

        // Add only 3 items
        batch.add("item1");
        batch.add("item2");
        batch.add("item3");

        // Manually flush
        batch.flush().get(1, TimeUnit.SECONDS);

        // Wait for processing
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(processedItems).hasSize(3);
        assertThat(batch.getTotalBatchesProcessed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should flush partial batch on stop when configured")
    void testFlushOnStop() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> processedItems = new ArrayList<>();

        BatchBehavior<String> batch = new BatchBehavior<>("test-batch", 10, null, true) {
            @Override
            protected void processBatch(List<String> items) {
                processedItems.addAll(items);
                latch.countDown();
            }
        };
        batch.setAgent(testAgent);

        // Add partial batch
        batch.add("item1");
        batch.add("item2");

        // Execute once to collect items
        batch.execute().get();

        // Stop behavior (should flush)
        batch.stop();

        // Wait for processing
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(processedItems).hasSize(2);
        assertThat(batch.getTotalBatchesProcessed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should NOT flush partial batch on stop when disabled")
    void testNoFlushOnStop() throws Exception {
        AtomicInteger processCount = new AtomicInteger(0);

        BatchBehavior<String> batch = new BatchBehavior<>("test-batch", 10, null, false) {
            @Override
            protected void processBatch(List<String> items) {
                processCount.incrementAndGet();
            }
        };
        batch.setAgent(testAgent);

        // Add partial batch
        batch.add("item1");
        batch.add("item2");

        // Execute once to collect items
        batch.execute().get();

        // Stop behavior (should NOT flush)
        batch.stop();

        Thread.sleep(100);

        // Verify no processing occurred
        assertThat(processCount.get()).isZero();
        assertThat(batch.getTotalBatchesProcessed()).isZero();
    }

    @Test
    @DisplayName("Should process multiple batches sequentially")
    void testMultipleBatches() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        List<List<String>> allBatches = new ArrayList<>();

        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 3, items -> {
            synchronized (allBatches) {
                allBatches.add(new ArrayList<>(items));
            }
            latch.countDown();
        });
        batch.setAgent(testAgent);

        // Add items for 3 complete batches
        for (int i = 1; i <= 9; i++) {
            batch.add("item" + i);
        }

        // Execute multiple times to process all batches
        for (int i = 0; i < 5; i++) {
            batch.execute().get();
            Thread.sleep(50);
        }

        // Wait for all processing
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(allBatches).hasSize(3);
        assertThat(batch.getTotalItemsProcessed()).isEqualTo(9);
        assertThat(batch.getTotalBatchesProcessed()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle batch processing errors gracefully")
    void testBatchProcessingError() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger(0);

        BatchBehavior<String> batch = new BatchBehavior<>("test-batch", 3) {
            @Override
            protected void processBatch(List<String> items) {
                throw new RuntimeException("Simulated processing error");
            }

            @Override
            protected void onBatchError(List<String> failedBatch, Exception error) {
                errorCount.incrementAndGet();
                errorLatch.countDown();
            }
        };
        batch.setAgent(testAgent);

        // Add items
        batch.add("item1");
        batch.add("item2");
        batch.add("item3");

        // Execute (should fail but not crash)
        batch.execute().get();

        // Wait for error handling
        assertThat(errorLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errorCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should calculate statistics correctly")
    void testStatistics() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);

        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 5, items -> {
            latch.countDown();
        });
        batch.setAgent(testAgent);

        // Batch 1: Full (5 items)
        for (int i = 1; i <= 5; i++) {
            batch.add("batch1-item" + i);
        }
        batch.execute().get();

        // Batch 2: Full (5 items)
        for (int i = 1; i <= 5; i++) {
            batch.add("batch2-item" + i);
        }
        batch.execute().get();

        // Batch 3: Partial (3 items)
        for (int i = 1; i <= 3; i++) {
            batch.add("batch3-item" + i);
        }
        batch.flush().get();

        // Wait for all processing
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Verify statistics
        assertThat(batch.getTotalItemsProcessed()).isEqualTo(13);
        assertThat(batch.getTotalBatchesProcessed()).isEqualTo(3);
        assertThat(batch.getPartialBatchesProcessed()).isEqualTo(1);
        assertThat(batch.getAverageBatchSize()).isCloseTo(13.0 / 3.0, within(0.01));
        assertThat(batch.getBatchFullnessRate()).isCloseTo(2.0 / 3.0, within(0.01));
    }

    @Test
    @DisplayName("Should add items with timeout")
    void testAddWithTimeout() throws Exception {
        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 10, items -> {});
        batch.setAgent(testAgent);

        boolean added = batch.add("item1", Duration.ofMillis(100));

        assertThat(added).isTrue();
        assertThat(batch.getQueueSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should collect items from queue before processing")
    void testItemCollection() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> processedItems = new ArrayList<>();

        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 5, items -> {
            processedItems.addAll(items);
            latch.countDown();
        });
        batch.setAgent(testAgent);

        // Add items to queue
        for (int i = 1; i <= 5; i++) {
            batch.add("item" + i);
        }

        // Items should be in queue
        assertThat(batch.getQueueSize()).isEqualTo(5);
        assertThat(batch.getCurrentBatchSize()).isZero();

        // Execute to collect and process
        batch.execute().get();

        // Wait for processing
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify items were collected and processed
        assertThat(batch.getQueueSize()).isZero();
        assertThat(processedItems).hasSize(5);
    }

    @Test
    @DisplayName("Should not process empty batch")
    void testEmptyBatchNotProcessed() throws Exception {
        AtomicInteger processCount = new AtomicInteger(0);

        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 5, items -> {
            processCount.incrementAndGet();
        });
        batch.setAgent(testAgent);

        // Execute without adding items
        batch.execute().get();

        Thread.sleep(100);

        // Verify no processing occurred
        assertThat(processCount.get()).isZero();
        assertThat(batch.getTotalBatchesProcessed()).isZero();
    }

    @Test
    @DisplayName("Should handle concurrent item additions")
    void testConcurrentAdditions() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger totalProcessed = new AtomicInteger(0);

        BatchBehavior<Integer> batch = BatchBehavior.simple("test-batch", 100, items -> {
            totalProcessed.addAndGet(items.size());
            latch.countDown();
        });
        batch.setAgent(testAgent);

        // Add items from multiple threads
        int threadCount = 10;
        int itemsPerThread = 10;
        CountDownLatch addLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < itemsPerThread; i++) {
                    batch.add(threadId * 100 + i);
                }
                addLatch.countDown();
            });
        }

        // Wait for all additions
        assertThat(addLatch.await(2, TimeUnit.SECONDS)).isTrue();

        // Process batch
        batch.execute().get();

        // Wait for processing
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify all items were added and processed
        assertThat(totalProcessed.get()).isEqualTo(threadCount * itemsPerThread);
    }

    @Test
    @DisplayName("Should expose getters correctly")
    void testGetters() {
        Duration maxWait = Duration.ofSeconds(5);

        BatchBehavior<String> batch = new BatchBehavior<>("test-batch", 10, maxWait, true) {
            @Override
            protected void processBatch(List<String> items) {
            }
        };

        assertThat(batch.getBehaviorId()).isEqualTo("test-batch");
        assertThat(batch.getMaxBatchSize()).isEqualTo(10);
        assertThat(batch.getMaxWaitTime()).isEqualTo(maxWait);
        assertThat(batch.isFlushOnStop()).isTrue();
    }

    @Test
    @DisplayName("Should allow changing flush on stop setting")
    void testSetFlushOnStop() {
        BatchBehavior<String> batch = BatchBehavior.simple("test-batch", 10, items -> {});

        assertThat(batch.isFlushOnStop()).isTrue();

        batch.setFlushOnStop(false);
        assertThat(batch.isFlushOnStop()).isFalse();

        batch.setFlushOnStop(true);
        assertThat(batch.isFlushOnStop()).isTrue();
    }
}
