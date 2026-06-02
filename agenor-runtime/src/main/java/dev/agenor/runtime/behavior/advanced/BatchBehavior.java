package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.BehaviorType;
import dev.agenor.runtime.behavior.BaseBehavior;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Behavior that collects items into batches and processes them when:
 * 1. Batch size reaches maxBatchSize (size-based trigger)
 * 2. Max wait time is exceeded (time-based trigger)
 * 3. Agent is stopping (flush partial batch)
 *
 * <p>This is useful for:
 * <ul>
 *   <li>Bulk database operations</li>
 *   <li>Batch API calls</li>
 *   <li>Log aggregation</li>
 *   <li>Event buffering</li>
 * </ul>
 *
 * @param <T> The type of items to batch
 */
public abstract class BatchBehavior<T> extends BaseBehavior {

    private final int maxBatchSize;
    private final Duration maxWaitTime;
    private final BlockingQueue<T> queue;
    private final List<T> currentBatch;

    private Instant batchStartTime;
    private final AtomicLong totalItemsProcessed;
    private final AtomicLong totalBatchesProcessed;
    private final AtomicLong partialBatchesProcessed;
    private volatile boolean flushOnStop;

    /**
     * Create batch behavior with size-based triggering only
     *
     * @param behaviorId unique behavior identifier
     * @param maxBatchSize maximum number of items per batch
     */
    protected BatchBehavior(String behaviorId, int maxBatchSize) {
        this(behaviorId, maxBatchSize, null, true);
    }

    /**
     * Create batch behavior with both size and time-based triggering
     *
     * @param behaviorId unique behavior identifier
     * @param maxBatchSize maximum number of items per batch
     * @param maxWaitTime maximum time to wait before processing partial batch
     */
    protected BatchBehavior(String behaviorId, int maxBatchSize, Duration maxWaitTime) {
        this(behaviorId, maxBatchSize, maxWaitTime, true);
    }

    /**
     * Create batch behavior with full configuration
     *
     * @param behaviorId unique behavior identifier
     * @param maxBatchSize maximum number of items per batch
     * @param maxWaitTime maximum time to wait before processing partial batch (null for size-only)
     * @param flushOnStop whether to flush partial batch when behavior stops
     */
    protected BatchBehavior(String behaviorId, int maxBatchSize, Duration maxWaitTime, boolean flushOnStop) {
        super(behaviorId != null ? behaviorId : "batch-behavior",
              BehaviorType.BATCH,
              maxWaitTime != null ? maxWaitTime.dividedBy(2) : Duration.ofMillis(100));

        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive, got: " + maxBatchSize);
        }

        this.maxBatchSize = maxBatchSize;
        this.maxWaitTime = maxWaitTime;
        this.flushOnStop = flushOnStop;
        this.queue = new LinkedBlockingQueue<>();
        this.currentBatch = new ArrayList<>(maxBatchSize);
        this.batchStartTime = Instant.now();
        this.totalItemsProcessed = new AtomicLong(0);
        this.totalBatchesProcessed = new AtomicLong(0);
        this.partialBatchesProcessed = new AtomicLong(0);

        log.debug("BatchBehavior '{}' created: maxBatchSize={}, maxWaitTime={}, flushOnStop={}",
                 behaviorId, maxBatchSize, maxWaitTime, flushOnStop);
    }

    @Override
    public CompletableFuture<Void> execute() {
        if (!isActive()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Collect items from queue into current batch
                collectItems();

                // Check if batch should be processed
                if (shouldProcessBatch()) {
                    processBatchInternal();
                }

            } catch (Exception e) {
                log.error("Error in batch behavior '{}': {}", getBehaviorId(), e.getMessage(), e);
                onError(e);
            }
        });
    }

    /**
     * Add an item to the batch queue.
     * Non-blocking operation.
     *
     * @param item the item to add
     * @return true if item was added, false if queue is full
     */
    public boolean add(T item) {
        if (item == null) {
            log.warn("Attempted to add null item to batch behavior '{}'", getBehaviorId());
            return false;
        }

        boolean added = queue.offer(item);

        if (added) {
            log.trace("Item added to batch queue '{}', queue size: {}", getBehaviorId(), queue.size());
        } else {
            log.warn("Failed to add item to batch queue '{}', queue full", getBehaviorId());
        }

        return added;
    }

    /**
     * Add an item to the batch queue with timeout.
     * Blocking operation with timeout.
     *
     * @param item the item to add
     * @param timeout how long to wait
     * @return true if item was added within timeout
     */
    public boolean add(T item, Duration timeout) {
        if (item == null) {
            log.warn("Attempted to add null item to batch behavior '{}'", getBehaviorId());
            return false;
        }

        try {
            boolean added = queue.offer(item, timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (added) {
                log.trace("Item added to batch queue '{}' after wait, queue size: {}",
                         getBehaviorId(), queue.size());
            } else {
                log.warn("Timeout adding item to batch queue '{}' after {}", getBehaviorId(), timeout);
            }

            return added;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while adding item to batch queue '{}'", getBehaviorId());
            return false;
        }
    }

    /**
     * Force processing of current batch regardless of size or time.
     * Useful for explicit flush operations.
     *
     * @return CompletableFuture that completes when batch is processed
     */
    public CompletableFuture<Void> flush() {
        log.debug("Flushing batch behavior '{}', current batch size: {}",
                 getBehaviorId(), currentBatch.size());

        return CompletableFuture.runAsync(() -> {
            synchronized (currentBatch) {
                // First collect any pending items from queue
                collectItems();

                if (!currentBatch.isEmpty()) {
                    processBatchInternal();
                }
            }
        });
    }

    /**
     * Collect items from queue into current batch.
     * Drains available items up to batch size limit.
     */
    private void collectItems() {
        synchronized (currentBatch) {
            // If batch is already full, don't collect more
            if (currentBatch.size() >= maxBatchSize) {
                return;
            }

            // Calculate how many items we can take
            int remainingCapacity = maxBatchSize - currentBatch.size();

            // Drain available items from queue
            List<T> items = new ArrayList<>(remainingCapacity);
            queue.drainTo(items, remainingCapacity);

            if (!items.isEmpty()) {
                currentBatch.addAll(items);

                log.trace("Collected {} items from queue, current batch size: {}",
                         items.size(), currentBatch.size());

                // Initialize batch start time on first item
                if (currentBatch.size() == items.size()) {
                    batchStartTime = Instant.now();
                }
            }
        }
    }

    /**
     * Check if batch should be processed based on triggers
     */
    private boolean shouldProcessBatch() {
        synchronized (currentBatch) {
            if (currentBatch.isEmpty()) {
                return false;
            }

            // Size-based trigger
            if (currentBatch.size() >= maxBatchSize) {
                log.trace("Batch size trigger reached: {} >= {}",
                         currentBatch.size(), maxBatchSize);
                return true;
            }

            // Time-based trigger
            if (maxWaitTime != null) {
                Duration waitedTime = Duration.between(batchStartTime, Instant.now());
                if (waitedTime.compareTo(maxWaitTime) >= 0) {
                    log.trace("Batch time trigger reached: {} >= {}", waitedTime, maxWaitTime);
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Process the current batch and reset for next batch
     */
    private void processBatchInternal() {
        List<T> batchToProcess;
        boolean isPartialBatch;

        synchronized (currentBatch) {
            if (currentBatch.isEmpty()) {
                return;
            }

            // Create immutable copy for processing
            batchToProcess = List.copyOf(currentBatch);
            isPartialBatch = currentBatch.size() < maxBatchSize;

            log.debug("Processing batch '{}': size={}, partial={}",
                     getBehaviorId(), batchToProcess.size(), isPartialBatch);

            // Clear current batch
            currentBatch.clear();
            batchStartTime = Instant.now();
        }

        try {
            // Call user-defined processing
            processBatch(batchToProcess);

            // Update metrics
            totalItemsProcessed.addAndGet(batchToProcess.size());
            totalBatchesProcessed.incrementAndGet();
            if (isPartialBatch) {
                partialBatchesProcessed.incrementAndGet();
            }

            log.debug("Batch '{}' processed successfully: {} items",
                     getBehaviorId(), batchToProcess.size());

        } catch (Exception e) {
            log.error("Error processing batch '{}': {}", getBehaviorId(), e.getMessage(), e);

            // Call error handler with the failed batch
            onBatchError(batchToProcess, e);
        }
    }

    /**
     * Process a batch of items.
     * Must be implemented by subclasses.
     *
     * @param batch immutable list of items to process
     */
    protected abstract void processBatch(List<T> batch);

    /**
     * Called when batch processing fails.
     * Override for custom error handling (e.g., dead letter queue, retry).
     *
     * @param failedBatch the batch that failed to process
     * @param error the exception that occurred
     */
    protected void onBatchError(List<T> failedBatch, Exception error) {
        log.error("Batch processing failed for {} items: {}", failedBatch.size(), error.getMessage());
    }

    @Override
    public void stop() {
        log.debug("Stopping batch behavior '{}', flushOnStop={}", getBehaviorId(), flushOnStop);

        // Flush partial batch if configured
        if (flushOnStop) {
            synchronized (currentBatch) {
                // Collect any remaining items from queue
                collectItems();

                if (!currentBatch.isEmpty()) {
                    log.info("Flushing partial batch on stop: {} items", currentBatch.size());
                    processBatchInternal();
                }
            }
        } else {
            int droppedItems = currentBatch.size() + queue.size();
            if (droppedItems > 0) {
                log.warn("Dropping {} items on stop (flushOnStop=false)", droppedItems);
            }
        }

        super.stop();
    }

    // ========== Getters and Statistics ==========

    /**
     * Get current batch size (not yet processed)
     */
    public int getCurrentBatchSize() {
        synchronized (currentBatch) {
            return currentBatch.size();
        }
    }

    /**
     * Get number of items waiting in queue
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Get total items processed across all batches
     */
    public long getTotalItemsProcessed() {
        return totalItemsProcessed.get();
    }

    /**
     * Get total number of batches processed
     */
    public long getTotalBatchesProcessed() {
        return totalBatchesProcessed.get();
    }

    /**
     * Get number of partial batches processed (size {@literal <} maxBatchSize)
     */
    public long getPartialBatchesProcessed() {
        return partialBatchesProcessed.get();
    }

    /**
     * Get average batch size
     */
    public double getAverageBatchSize() {
        long batches = totalBatchesProcessed.get();
        return batches > 0 ? (double) totalItemsProcessed.get() / batches : 0.0;
    }

    /**
     * Get batch fullness rate (percentage of full batches)
     */
    public double getBatchFullnessRate() {
        long total = totalBatchesProcessed.get();
        long partial = partialBatchesProcessed.get();
        long full = total - partial;
        return total > 0 ? (double) full / total : 0.0;
    }

    /**
     * Get configured maximum batch size
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Get configured maximum wait time
     */
    public Duration getMaxWaitTime() {
        return maxWaitTime;
    }

    /**
     * Check if flush on stop is enabled
     */
    public boolean isFlushOnStop() {
        return flushOnStop;
    }

    /**
     * Set whether to flush partial batch on stop
     */
    public void setFlushOnStop(boolean flushOnStop) {
        this.flushOnStop = flushOnStop;
        log.debug("BatchBehavior '{}' flushOnStop set to: {}", getBehaviorId(), flushOnStop);
    }

    @Override
    protected void action() {
        // Not used - execute() is overridden
    }

    // ========== Factory Methods ==========

    /**
     * Create a simple batch behavior from a consumer function
     *
     * @param behaviorId behavior identifier
     * @param maxBatchSize maximum batch size
     * @param processor function to process batches
     * @param <T> item type
     * @return new batch behavior
     */
    public static <T> BatchBehavior<T> simple(String behaviorId, int maxBatchSize,
                                              BatchProcessor<T> processor) {
        return new BatchBehavior<>(behaviorId, maxBatchSize) {
            @Override
            protected void processBatch(List<T> batch) {
                processor.process(batch);
            }
        };
    }

    /**
     * Create batch behavior with time and size triggers
     *
     * @param behaviorId behavior identifier
     * @param maxBatchSize maximum batch size
     * @param maxWaitTime maximum wait time
     * @param processor function to process batches
     * @param <T> item type
     * @return new batch behavior
     */
    public static <T> BatchBehavior<T> withTimeout(String behaviorId, int maxBatchSize,
                                                    Duration maxWaitTime, BatchProcessor<T> processor) {
        return new BatchBehavior<>(behaviorId, maxBatchSize, maxWaitTime) {
            @Override
            protected void processBatch(List<T> batch) {
                processor.process(batch);
            }
        };
    }

    /**
     * Functional interface for batch processing
     */
    @FunctionalInterface
    public interface BatchProcessor<T> {
        void process(List<T> batch);
    }
}
