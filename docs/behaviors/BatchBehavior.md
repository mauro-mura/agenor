# BatchBehavior

## Overview

`BatchBehavior` is an advanced behavior pattern that collects items into batches and processes them efficiently in bulk operations. This is essential for optimizing I/O-bound operations, reducing overhead, and improving throughput in multi-agent systems.

## Key Features

✅ **Dual Trigger System**
- **Size-based**: Process when batch reaches maximum size
- **Time-based**: Process partial batches after timeout to prevent stalling

✅ **Thread-Safe Queue**
- Non-blocking item additions
- Concurrent collection handling
- Safe for multi-threaded environments

✅ **Flexible Configuration**
- Configurable batch size
- Optional time trigger
- Flush-on-stop behavior

✅ **Comprehensive Statistics**
- Total items processed
- Batch count tracking
- Partial batch monitoring
- Average batch size calculation
- Fullness rate metrics

✅ **Error Handling**
- Graceful failure recovery
- Custom error handlers
- Failed batch tracking

## Use Cases

### 1. Database Operations
Batch multiple INSERT/UPDATE/DELETE operations into single transactions:
```java
BatchBehavior<DatabaseOperation> dbBatch = 
    BatchBehavior.withTimeout("db-batch", 50, Duration.ofSeconds(2), 
        batch -> executeBatchTransaction(batch));
```

### 2. Log Aggregation
Collect log entries and write in bulk to reduce I/O:
```java
BatchBehavior<LogEntry> logBatch = 
    BatchBehavior.withTimeout("log-batch", 100, Duration.ofSeconds(5),
        batch -> writeLogsToFile(batch));
```

### 3. API Rate Limiting
Batch API calls to respect rate limits:
```java
BatchBehavior<ApiRequest> apiBatch = 
    BatchBehavior.simple("api-batch", 10, 
        batch -> sendBatchedApiRequests(batch));
```

### 4. Message Aggregation
Combine multiple messages before forwarding:
```java
BatchBehavior<Message> msgBatch = 
    BatchBehavior.withTimeout("msg-batch", 20, Duration.ofMillis(500),
        batch -> forwardAggregatedMessages(batch));
```

### 5. Event Streaming
Buffer events before streaming to analytics platforms:
```java
BatchBehavior<Event> eventBatch = 
    BatchBehavior.withTimeout("event-batch", 1000, Duration.ofSeconds(10),
        batch -> streamToAnalytics(batch));
```

## API Reference

### Creation Methods

#### 1. Simple Size-Based Batching
```java
BatchBehavior<T> batch = BatchBehavior.simple(
    String behaviorId,
    int maxBatchSize,
    BatchProcessor<T> processor
);
```

**Parameters:**
- `behaviorId`: Unique identifier for the behavior
- `maxBatchSize`: Maximum items before processing (must be > 0)
- `processor`: Function to process batches

**Example:**
```java
BatchBehavior<String> batch = BatchBehavior.simple("my-batch", 10, items -> {
    System.out.println("Processing " + items.size() + " items");
});
```

#### 2. Size + Time-Based Batching
```java
BatchBehavior<T> batch = BatchBehavior.withTimeout(
    String behaviorId,
    int maxBatchSize,
    Duration maxWaitTime,
    BatchProcessor<T> processor
);
```

**Parameters:**
- `behaviorId`: Unique identifier
- `maxBatchSize`: Maximum items per batch
- `maxWaitTime`: Maximum wait time for partial batches
- `processor`: Batch processing function

**Example:**
```java
BatchBehavior<LogEntry> batch = BatchBehavior.withTimeout(
    "log-batch", 
    50,                          // Process at 50 items
    Duration.ofSeconds(3),       // OR after 3 seconds
    logs -> writeToDisk(logs)
);
```

#### 3. Full Configuration Constructor
```java
BatchBehavior<T> batch = new BatchBehavior<T>(
    String behaviorId,
    int maxBatchSize,
    Duration maxWaitTime,    // null for size-only
    boolean flushOnStop
) {
    @Override
    protected void processBatch(List<T> batch) {
        // Implementation
    }
};
```

### Adding Items

#### Non-Blocking Add
```java
boolean added = batch.add(item);
if (!added) {
    // Queue full - handle overflow
}
```

#### Blocking Add with Timeout
```java
boolean added = batch.add(item, Duration.ofMillis(100));
if (!added) {
    // Timeout - queue still full
}
```

### Manual Flush

Force immediate processing of current batch:
```java
CompletableFuture<Void> future = batch.flush();
future.join(); // Wait for completion
```

### Statistics

```java
// Current state
int currentSize = batch.getCurrentBatchSize();    // Items in current batch
int queueSize = batch.getQueueSize();             // Items waiting in queue

// Historical metrics
long totalItems = batch.getTotalItemsProcessed();      // All items processed
long totalBatches = batch.getTotalBatchesProcessed();  // Number of batches
long partialBatches = batch.getPartialBatchesProcessed(); // Incomplete batches

// Calculated metrics
double avgSize = batch.getAverageBatchSize();          // Mean batch size
double fullness = batch.getBatchFullnessRate();        // % of full batches
```

## Configuration Options

### Max Batch Size
```java
int maxSize = batch.getMaxBatchSize();
```
- Must be positive (> 0)
- Determines when size trigger fires
- Affects memory usage

### Max Wait Time
```java
Duration maxWait = batch.getMaxWaitTime();
```
- `null` for size-only triggering
- Prevents starvation of partial batches
- Balance between latency and efficiency

### Flush On Stop
```java
boolean flushes = batch.isFlushOnStop();
batch.setFlushOnStop(true);  // Enable
batch.setFlushOnStop(false); // Disable
```
- `true`: Processes remaining items on shutdown
- `false`: Drops remaining items
- Important for data consistency

## Implementation Patterns

### Pattern 1: Agent with Batch Behavior

```java
@Agent("data-processor")
public class DataProcessorAgent extends BaseAgent {
    
    private BatchBehavior<DataItem> batcher;
    
    @Override
    protected void onStart() {
        batcher = BatchBehavior.withTimeout(
            "data-batch",
            100,
            Duration.ofSeconds(5),
            this::processDataBatch
        );
        batcher.setAgent(this);
        addBehavior(batcher);
    }
    
    @AgenorMessageHandler("data.incoming")
    public void handleData(Message message) {
        DataItem item = message.getContent(DataItem.class);
        batcher.add(item);
    }
    
    private void processDataBatch(List<DataItem> batch) {
        // Bulk processing logic
        log.info("Processing batch of {} items", batch.size());
        
        // Example: Batch database insert
        database.batchInsert(batch);
    }
}
```

### Pattern 2: Error Handling

```java
BatchBehavior<Order> orderBatch = new BatchBehavior<Order>(
    "order-batch", 50, Duration.ofSeconds(2)
) {
    @Override
    protected void processBatch(List<Order> batch) {
        try {
            orderService.processBulkOrders(batch);
        } catch (Exception e) {
            log.error("Batch processing failed", e);
            throw e; // Trigger onBatchError
        }
    }
    
    @Override
    protected void onBatchError(List<Order> failedBatch, Exception error) {
        // Custom error handling
        log.error("Failed to process {} orders", failedBatch.size());
        
        // Send to dead letter queue
        deadLetterQueue.addAll(failedBatch);
        
        // Send alert
        alertService.sendAlert("Order batch failed: " + error.getMessage());
    }
};
```

### Pattern 3: Metrics and Monitoring

```java
@Behavior(type = CYCLIC, interval = "30s")
public void reportBatchMetrics() {
    log.info("Batch Metrics:");
    log.info("  - Queue size: {}", batcher.getQueueSize());
    log.info("  - Current batch: {}", batcher.getCurrentBatchSize());
    log.info("  - Total processed: {}", batcher.getTotalItemsProcessed());
    log.info("  - Average size: {:.2f}", batcher.getAverageBatchSize());
    log.info("  - Fullness rate: {:.1f}%", 
            batcher.getBatchFullnessRate() * 100);
}
```

## Performance Considerations

### Memory Usage
- **Queue size**: Unbounded `BlockingQueue` - monitor queue growth
- **Batch size**: Balance between memory and efficiency
- **Recommendation**: Use time trigger to prevent queue buildup

### Throughput Optimization
```java
// High throughput - larger batches, longer wait
BatchBehavior.withTimeout("high-throughput", 1000, Duration.ofSeconds(10), ...);

// Low latency - smaller batches, shorter wait
BatchBehavior.withTimeout("low-latency", 10, Duration.ofMillis(100), ...);
```

### Thread Safety
- All public methods are thread-safe
- Internal synchronization on batch collection
- Safe concurrent item additions from multiple threads

## Testing

### Unit Test Example
```java
@Test
void testBatchProcessing() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<String> processed = new ArrayList<>();
    
    BatchBehavior<String> batch = BatchBehavior.simple("test", 5, items -> {
        processed.addAll(items);
        latch.countDown();
    });
    batch.setAgent(testAgent);
    
    // Add items
    for (int i = 0; i < 5; i++) {
        batch.add("item" + i);
    }
    
    // Trigger processing
    batch.execute().get();
    
    // Verify
    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertEquals(5, processed.size());
}
```

## Best Practices

✅ **DO:**
- Set appropriate batch sizes for your use case
- Use time triggers for responsive systems
- Monitor batch statistics
- Handle errors gracefully
- Flush on stop for critical data

❌ **DON'T:**
- Use excessively large batch sizes (memory)
- Forget time triggers (starvation risk)
- Ignore queue growth (memory leak potential)
- Process batches synchronously in high-throughput scenarios
- Forget to test flush-on-stop behavior

## Comparison with Other Patterns

| Feature | BatchBehavior | CyclicBehavior | EventDrivenBehavior |
|---------|---------------|----------------|---------------------|
| **Triggering** | Size/Time | Time only | Event only |
| **Buffering** | Built-in queue | None | None |
| **Bulk Processing** | ✅ Yes | ❌ No | ❌ No |
| **Memory Efficient** | ✅ Yes | ✅ Yes | ⚠️ Depends |
| **Latency** | Higher | Lower | Lowest |
| **Throughput** | Highest | Medium | Variable |

## Migration from Other Patterns

### From CyclicBehavior
**Before:**
```java
@Behavior(type = CYCLIC, interval = "5s")
public void processItems() {
    for (Item item : queue) {
        processItem(item);
    }
}
```

**After:**
```java
@Override
protected void onStart() {
    BatchBehavior<Item> batch = BatchBehavior.withTimeout(
        "item-batch", 20, Duration.ofSeconds(5),
        items -> processItemsBatch(items)
    );
    addBehavior(batch);
}
```

**Benefits:**
- Fewer invocations (overhead reduction)
- Better resource utilization
- Automatic queuing

## Troubleshooting

### Queue Growing Unbounded
**Problem:** `getQueueSize()` keeps increasing

**Solution:**
1. Reduce item production rate
2. Increase batch size
3. Reduce max wait time
4. Add backpressure

### Batches Too Small
**Problem:** `getAverageBatchSize()` much smaller than `maxBatchSize`

**Solution:**
1. Increase max wait time
2. Reduce max batch size
3. Check item production rate

### High Latency
**Problem:** Items wait too long before processing

**Solution:**
1. Reduce max wait time
2. Reduce max batch size
3. Use manual flush for high-priority items

## Examples Repository

See complete working examples:
- `/agenor-examples/src/main/java/dev/jentic/examples/batching/BatchProcessingExample.java`
- Log aggregation system
- Database batch operations
- Multi-agent batch coordination

## Version History

- **v0.2.0** (October 2025): Initial BatchBehavior implementation
  - Size and time-based triggering
  - Thread-safe queue operations
  - Comprehensive statistics
  - Error handling support
  - Factory methods

---

**Next Steps:**
- See `RetryBehavior` for fault-tolerant batching
- See `ThrottledBehavior` for rate-limited processing
- See `PipelineBehavior` for multi-stage batch processing
