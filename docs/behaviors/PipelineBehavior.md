# PipelineBehavior

## Overview

**PipelineBehavior** is an advanced behavior pattern in the Agenor Framework that enables multi-stage sequential data processing with type-safe transformations, comprehensive error handling, and detailed monitoring capabilities.

---

## Table of Contents

1. [Introduction](#introduction)
2. [Core Concepts](#core-concepts)
3. [Features](#features)
4. [API Reference](#api-reference)
5. [Usage Examples](#usage-examples)
6. [Error Handling](#error-handling)
7. [Performance Considerations](#performance-considerations)
8. [Best Practices](#best-practices)
9. [Advanced Patterns](#advanced-patterns)
10. [Troubleshooting](#troubleshooting)

---

## Introduction

PipelineBehavior allows you to build complex data processing workflows by chaining multiple transformation stages. Each stage receives input, processes it, and passes the result to the next stage, forming a data processing pipeline.

### Key Benefits

- **Type Safety**: Compile-time type checking for stage transformations
- **Modularity**: Each stage is independent and reusable
- **Observability**: Built-in callbacks and metrics for monitoring
- **Resilience**: Configurable error handling strategies
- **Flexibility**: Support for various error recovery patterns

---

## Core Concepts

### Pipeline Architecture

```
Input → Stage 1 → Stage 2 → Stage 3 → ... → Stage N → Output
  ↓         ↓         ↓         ↓              ↓         ↓
Type I   Type T1   Type T2   Type T3        Type TN   Type O
```

### Key Components

1. **Pipeline**: Container that orchestrates stage execution
2. **Stage**: Individual processing unit that transforms data
3. **Result**: Outcome of pipeline execution with metrics
4. **Error Handler**: Strategy for handling stage failures

---

## Features

### 1. Sequential Stage Execution

Stages execute one after another, with each stage receiving the output of the previous stage.

```java
pipeline
    .addStage("stage1", data -> transform1(data))
    .addStage("stage2", data -> transform2(data))
    .addStage("stage3", data -> transform3(data));
```

### 2. Type-Safe Transformations

Supports type changes between stages with compile-time safety.

```java
PipelineBehavior<String, Double> pipeline = 
    PipelineBehavior.<String, Double>builder("typed-pipeline", String.class, Double.class)
        .addStage("parse", (String s) -> Integer.parseInt(s))      // String → Integer
        .addStage("square", (Integer i) -> i * i)                  // Integer → Integer
        .addStage("to-double", (Integer i) -> i.doubleValue())     // Integer → Double
        .build();
```

### 3. Error Handling Strategies

Multiple strategies for handling stage failures:

#### FAIL_FAST (Default)
Stop pipeline execution on first error.

```java
.errorStrategy(ErrorHandlingStrategy.FAIL_FAST)
```

#### CONTINUE_ON_ERROR
Skip failed stages and continue with last successful data.

```java
.errorStrategy(ErrorHandlingStrategy.CONTINUE_ON_ERROR)
.failFast(false)
```

#### SKIP_FAILED_STAGES
Continue pipeline execution, skipping failed stages.

```java
.errorStrategy(ErrorHandlingStrategy.SKIP_FAILED_STAGES)
```

#### RETRY_FAILED_STAGES
Automatically retry failed stages before giving up.

```java
.errorStrategy(ErrorHandlingStrategy.RETRY_FAILED_STAGES)
```

### 4. Stage-Level Timeouts

Set maximum execution time for each stage:

```java
.stageTimeout(Duration.ofSeconds(5))
```

### 5. Stage Callbacks

Monitor pipeline execution with callbacks:

```java
.onStageStart(stage -> log.info("Starting: {}", stage.getName()))
.onStageComplete(stage -> log.info("Completed: {}", stage.getName()))
.onStageError((stage, error) -> log.error("Failed: {}", stage.getName(), error))
.onPipelineComplete(result -> log.info("Pipeline finished: {}", result))
```

### 6. Pipeline Metrics

Track execution statistics:

```java
PipelineMetrics metrics = pipeline.getMetrics();
System.out.println("Success rate: " + metrics.getSuccessRate() * 100 + "%");
System.out.println("Total executions: " + metrics.getTotalExecutions());
System.out.println("Average stages per run: " + metrics.getAverageStagesPerExecution());
```

---

## API Reference

### Builder API

```java
PipelineBehavior.<I, O>builder(String behaviorId, Class<I> inputType, Class<O> outputType)
    .addStage(String name, Function<T, R> processor)
    .addStage(PipelineStage<T, R> stage)
    .stageTimeout(Duration timeout)
    .errorStrategy(ErrorHandlingStrategy strategy)
    .failFast(boolean failFast)
    .onStageStart(Consumer<PipelineStage<?, ?>> callback)
    .onStageComplete(Consumer<PipelineStage<?, ?>> callback)
    .onStageError(StageErrorHandler callback)
    .onPipelineComplete(Consumer<PipelineResult<O>> callback)
    .build();
```

### Execution API

```java
// Execute pipeline
CompletableFuture<PipelineResult<O>> result = pipeline.executePipeline(input);

// Synchronous execution
PipelineResult<O> result = pipeline.executePipeline(input).join();

// Handle result
if (result.isSuccess()) {
    O output = result.getOutput();
    // Process output
} else {
    String error = result.getErrorMessage();
    // Handle error
}
```

### Result API

```java
PipelineResult<O> result = ...;

// Check success
boolean success = result.isSuccess();

// Get output (if successful)
O output = result.getOutput();

// Get error message (if failed)
String error = result.getErrorMessage();

// Get stage results
List<StageResult> stageResults = result.getStageResults();

// Get total duration
long durationMs = result.getTotalDurationMs();

// Iterate through stage results
for (StageResult stageResult : result.getStageResults()) {
    System.out.printf("Stage: %s, Success: %s, Duration: %dms%n",
        stageResult.stageName(),
        stageResult.success(),
        stageResult.durationMs());
}
```

### Metrics API

```java
PipelineMetrics metrics = pipeline.getMetrics();

// Get execution counts
int totalExecutions = metrics.getTotalExecutions();
int successful = metrics.successfulExecutions();
int failed = metrics.failedExecutions();

// Get success rate
double successRate = metrics.getSuccessRate(); // 0.0 to 1.0

// Get average stages executed per run
double avgStages = metrics.getAverageStagesPerExecution();

// Reset metrics
pipeline.resetMetrics();
```

---

## Usage Examples

### Example 1: Simple String Processing

```java
PipelineBehavior<String, String> pipeline = 
    PipelineBehavior.<String, String>builder("string-processor", String.class, String.class)
        .addStage("trim", (String s) -> s.trim())
        .addStage("uppercase", (String s) -> s.toUpperCase())
        .addStage("prefix", (String s) -> "PROCESSED: " + s)
        .build();

PipelineResult<String> result = pipeline.executePipeline("  hello world  ").join();
// Output: "PROCESSED: HELLO WORLD"
```

### Example 2: E-Commerce Order Processing

```java
PipelineBehavior<Order, ProcessedOrder> orderPipeline = 
    PipelineBehavior.<Order, ProcessedOrder>builder("order-pipeline", Order.class, ProcessedOrder.class)
        // Stage 1: Validate order
        .addStage("validate", order -> {
            if (order.items().isEmpty()) {
                throw new IllegalStateException("Order has no items");
            }
            if (order.totalAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Invalid order amount");
            }
            log.info("✓ Order validated");
            return order;
        })
        
        // Stage 2: Apply discounts
        .addStage("apply-discount", order -> {
            BigDecimal discount = calculateDiscount(order);
            BigDecimal newTotal = order.totalAmount().subtract(discount);
            log.info("✓ Discount applied: ${}", discount);
            return new Order(order.orderId(), order.customerId(), 
                           order.items(), newTotal, order.createdAt());
        })
        
        // Stage 3: Calculate tax
        .addStage("calculate-tax", order -> {
            BigDecimal tax = order.totalAmount().multiply(new BigDecimal("0.10"));
            log.info("✓ Tax calculated: ${}", tax);
            return new OrderWithTax(order, tax);
        })
        
        // Stage 4: Finalize order
        .addStage("finalize", orderWithTax -> {
            ProcessedOrder processed = new ProcessedOrder(
                orderWithTax.order().orderId(),
                orderWithTax.order().totalAmount(),
                orderWithTax.tax(),
                orderWithTax.order().totalAmount().add(orderWithTax.tax()),
                "CONFIRMED",
                LocalDateTime.now()
            );
            log.info("✓ Order finalized: total = ${}", processed.totalWithTax());
            return processed;
        })
        
        .stageTimeout(Duration.ofSeconds(5))
        .onPipelineComplete(result -> {
            if (result.isSuccess()) {
                log.info("🎉 Order processed in {}ms", result.getTotalDurationMs());
            }
        })
        .build();

// Process order
Order order = new Order("ORD-12345", "CUST-001", items, total, now);
PipelineResult<ProcessedOrder> result = orderPipeline.executePipeline(order).join();

if (result.isSuccess()) {
    ProcessedOrder processed = result.getOutput();
    System.out.println("Order ID: " + processed.orderId());
    System.out.println("Total: $" + processed.totalWithTax());
}
```

### Example 3: Data Transformation Pipeline

```java
PipelineBehavior<String, List<ReportData>> dataPipeline = 
    PipelineBehavior.<String, List<ReportData>>builder(
        "data-pipeline", String.class, List.class)
        
        // Stage 1: Load raw data
        .addStage("load", (String filename) -> {
            return Files.readAllLines(Path.of(filename));
        })
        
        // Stage 2: Parse lines
        .addStage("parse", (List<String> lines) -> {
            return lines.stream()
                .map(line -> line.split(","))
                .collect(Collectors.toList());
        })
        
        // Stage 3: Validate data
        .addStage("validate", (List<String[]> rows) -> {
            return rows.stream()
                .filter(row -> row.length == 5)
                .collect(Collectors.toList());
        })
        
        // Stage 4: Transform to domain objects
        .addStage("transform", (List<String[]> rows) -> {
            return rows.stream()
                .map(row -> new ReportData(
                    row[0], // id
                    row[1], // name
                    Double.parseDouble(row[2]), // value
                    LocalDate.parse(row[3]), // date
                    row[4]  // status
                ))
                .collect(Collectors.toList());
        })
        
        .stageTimeout(Duration.ofSeconds(30))
        .errorStrategy(ErrorHandlingStrategy.FAIL_FAST)
        .onStageComplete(stage -> 
            log.info("Completed stage: {}", stage.getName()))
        .build();

// Execute pipeline
PipelineResult<List<ReportData>> result = 
    dataPipeline.executePipeline("data.csv").join();
```

### Example 4: API Request Pipeline

```java
PipelineBehavior<ApiRequest, ApiResponse> apiPipeline = 
    PipelineBehavior.<ApiRequest, ApiResponse>builder(
        "api-pipeline", ApiRequest.class, ApiResponse.class)
        
        // Stage 1: Validate request
        .addStage("validate-request", request -> {
            if (request.endpoint() == null) {
                throw new IllegalArgumentException("Missing endpoint");
            }
            return request;
        })
        
        // Stage 2: Add authentication
        .addStage("add-auth", request -> {
            String token = authService.getToken();
            return request.withHeader("Authorization", "Bearer " + token);
        })
        
        // Stage 3: Execute HTTP call
        .addStage("execute", request -> {
            HttpResponse<String> response = httpClient.send(
                request.toHttpRequest(),
                HttpResponse.BodyHandlers.ofString()
            );
            return response;
        })
        
        // Stage 4: Parse response
        .addStage("parse-response", (HttpResponse<String> response) -> {
            if (response.statusCode() >= 400) {
                throw new ApiException("HTTP " + response.statusCode());
            }
            return objectMapper.readValue(
                response.body(), 
                ApiResponse.class
            );
        })
        
        .stageTimeout(Duration.ofSeconds(10))
        .errorStrategy(ErrorHandlingStrategy.FAIL_FAST)
        .onStageError((stage, error) -> {
            log.error("API pipeline failed at {}: {}", 
                     stage.getName(), error.getMessage());
            metrics.recordApiFailure(stage.getName());
        })
        .build();

// Execute API call
ApiRequest request = new ApiRequest("https://api.example.com/data", "GET");
PipelineResult<ApiResponse> result = apiPipeline.executePipeline(request).join();
```

---

## Error Handling

### Error Handling Strategies Comparison

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| **FAIL_FAST** | Stop on first error | Critical operations where partial success is unacceptable |
| **CONTINUE_ON_ERROR** | Skip failed stages, continue | Best-effort processing where some failures are acceptable |
| **SKIP_FAILED_STAGES** | Continue with last good data | Graceful degradation scenarios |
| **RETRY_FAILED_STAGES** | Retry before failing | Transient errors, network operations |

### Example: Handling Different Error Scenarios

```java
// Scenario 1: FAIL_FAST (default)
PipelineBehavior<Data, Result> strictPipeline = 
    PipelineBehavior.<Data, Result>builder("strict", Data.class, Result.class)
        .addStage("stage1", data -> process1(data))
        .addStage("stage2", data -> process2(data)) // If this fails, stop here
        .addStage("stage3", data -> process3(data)) // Never executed if stage2 fails
        .errorStrategy(ErrorHandlingStrategy.FAIL_FAST)
        .build();

// Scenario 2: CONTINUE_ON_ERROR
PipelineBehavior<Data, Result> lenientPipeline = 
    PipelineBehavior.<Data, Result>builder("lenient", Data.class, Result.class)
        .addStage("required", data -> requiredProcessing(data))
        .addStage("optional1", data -> optionalProcessing1(data)) // Can fail
        .addStage("optional2", data -> optionalProcessing2(data)) // Can fail
        .addStage("finalize", data -> finalizeProcessing(data))
        .errorStrategy(ErrorHandlingStrategy.CONTINUE_ON_ERROR)
        .failFast(false)
        .build();

// Scenario 3: Custom error handling with callbacks
PipelineBehavior<Data, Result> customPipeline = 
    PipelineBehavior.<Data, Result>builder("custom", Data.class, Result.class)
        .addStage("stage1", data -> process1(data))
        .addStage("stage2", data -> process2(data))
        .addStage("stage3", data -> process3(data))
        .onStageError((stage, error) -> {
            // Custom error handling logic
            if (error instanceof NetworkException) {
                log.warn("Network error in {}, will retry", stage.getName());
                retryService.scheduleRetry(stage);
            } else {
                log.error("Fatal error in {}", stage.getName(), error);
                alertService.sendAlert(error);
            }
        })
        .build();
```

### Timeout Handling

```java
PipelineBehavior<Data, Result> timeoutPipeline = 
    PipelineBehavior.<Data, Result>builder("timeout", Data.class, Result.class)
        .addStage("fast-stage", data -> quickProcess(data))
        .addStage("slow-stage", data -> {
            // This stage might take too long
            return slowProcess(data);
        })
        .stageTimeout(Duration.ofSeconds(5)) // Timeout for ALL stages
        .onStageError((stage, error) -> {
            if (error.getMessage().contains("timeout")) {
                log.warn("Stage {} timed out", stage.getName());
            }
        })
        .build();
```

---

## Performance Considerations

### Memory Usage

- **Pipeline object**: ~1-2 KB per instance
- **Stage results**: ~200 bytes per stage execution
- **Metrics**: ~100 bytes per pipeline
- **Large data**: Stages should stream data when possible

### CPU Usage

- **Sequential execution**: One stage at a time (no parallelism within pipeline)
- **Overhead**: <1ms per stage transition
- **Timeouts**: Use virtual threads, minimal overhead

### Throughput

- **Simple pipeline (3 stages)**: ~10,000 executions/second
- **Complex pipeline (10+ stages)**: ~1,000 executions/second
- **I/O bound stages**: Limited by external services

### Optimization Tips

1. **Avoid creating new pipelines per request** - Reuse pipeline instances
2. **Use stage-level caching** when appropriate
3. **Set realistic timeouts** to avoid hanging
4. **Monitor metrics** to identify slow stages
5. **Consider batch processing** for high volume

```java
// Good: Reuse pipeline
private final PipelineBehavior<Order, Result> orderPipeline = createPipeline();

public Result processOrder(Order order) {
    return orderPipeline.executePipeline(order).join().getOutput();
}

// Bad: Create pipeline per request
public Result processOrder(Order order) {
    PipelineBehavior<Order, Result> pipeline = createPipeline(); // Wasteful
    return pipeline.executePipeline(order).join().getOutput();
}
```

---

## Best Practices

### 1. Design Principles

✅ **DO:**
- Keep stages small and focused (single responsibility)
- Make stages stateless when possible
- Use descriptive stage names
- Add logging at stage boundaries
- Set appropriate timeouts

❌ **DON'T:**
- Create god stages that do everything
- Share mutable state between stages
- Ignore error handling
- Skip timeout configuration for I/O operations

### 2. Stage Design

```java
// Good: Small, focused stage
.addStage("validate-email", (User user) -> {
    if (!EmailValidator.isValid(user.email())) {
        throw new ValidationException("Invalid email");
    }
    return user;
})

// Bad: Stage doing too much
.addStage("validate-and-process", (User user) -> {
    // Validates
    // Transforms
    // Calls external APIs
    // Updates database
    // Sends notifications
    // ...too many responsibilities
})
```

### 3. Error Handling

```java
// Good: Specific error handling
.onStageError((stage, error) -> {
    if (error instanceof ValidationException) {
        metrics.recordValidationError();
    } else if (error instanceof NetworkException) {
        metrics.recordNetworkError();
    }
    log.error("Stage {} failed: {}", stage.getName(), error.getMessage());
})

// Good: Fail fast for critical operations
.errorStrategy(ErrorHandlingStrategy.FAIL_FAST)
.onPipelineComplete(result -> {
    if (!result.isSuccess()) {
        rollbackTransaction();
    }
})
```

### 4. Monitoring

```java
// Add comprehensive monitoring
PipelineBehavior<Data, Result> pipeline = builder(...)
    .onStageStart(stage -> {
        metrics.startTimer(stage.getName());
    })
    .onStageComplete(stage -> {
        long duration = metrics.stopTimer(stage.getName());
        metrics.recordStageSuccess(stage.getName(), duration);
    })
    .onStageError((stage, error) -> {
        metrics.recordStageFailure(stage.getName(), error.getClass());
    })
    .onPipelineComplete(result -> {
        if (result.isSuccess()) {
            metrics.recordPipelineSuccess(result.getTotalDurationMs());
        } else {
            metrics.recordPipelineFailure();
        }
    })
    .build();
```

### 5. Testing

```java
@Test
void testPipelineSuccess() {
    PipelineBehavior<String, Integer> pipeline = createTestPipeline();
    
    PipelineResult<Integer> result = pipeline.executePipeline("test").join();
    
    assertTrue(result.isSuccess());
    assertEquals(4, result.getOutput());
    assertEquals(3, result.getStageResults().size());
    
    // Verify all stages succeeded
    result.getStageResults().forEach(stageResult ->
        assertTrue(stageResult.success())
    );
}

@Test
void testPipelineFailure() {
    PipelineBehavior<String, String> pipeline = createFailingPipeline();
    
    PipelineResult<String> result = pipeline.executePipeline("test").join();
    
    assertFalse(result.isSuccess());
    assertNotNull(result.getErrorMessage());
    assertTrue(result.getErrorMessage().contains("expected error"));
}

@Test
void testStageTimeout() {
    PipelineBehavior<String, String> pipeline = 
        PipelineBehavior.<String, String>builder("timeout-test", String.class, String.class)
            .addStage("slow", s -> {
                Thread.sleep(5000);
                return s;
            })
            .stageTimeout(Duration.ofMillis(100))
            .build();
    
    PipelineResult<String> result = pipeline.executePipeline("test").join();
    
    assertFalse(result.isSuccess());
    assertTrue(result.getErrorMessage().toLowerCase().contains("timeout"));
}
```

---

## Advanced Patterns

### Pattern 1: Conditional Stage Execution

```java
PipelineBehavior<Order, Result> conditionalPipeline = builder(...)
    .addStage("check-vip", order -> {
        if (order.customer().isVip()) {
            return processVipOrder(order);
        }
        return order;
    })
    .addStage("standard-processing", order -> {
        if (!order.customer().isVip()) {
            return processStandardOrder(order);
        }
        return order;
    })
    .build();
```

### Pattern 2: Stage Composition

```java
// Create reusable stage
PipelineStage<Order, Order> validationStage = new PipelineStage<>() {
    @Override
    public Order process(Order order) {
        validateCustomer(order.customer());
        validateItems(order.items());
        validatePayment(order.payment());
        return order;
    }
    
    @Override
    public String getName() {
        return "validation";
    }
};

// Use in multiple pipelines
PipelineBehavior<Order, Result> pipeline1 = builder(...)
    .addStage(validationStage)
    .addStage("process", order -> process(order))
    .build();

PipelineBehavior<Order, Result> pipeline2 = builder(...)
    .addStage(validationStage)
    .addStage("special-process", order -> specialProcess(order))
    .build();
```

### Pattern 3: Pipeline Chaining

```java
// Pipeline 1: Extract data
PipelineBehavior<String, RawData> extractPipeline = builder(...)
    .addStage("load", filename -> loadFile(filename))
    .addStage("parse", content -> parseContent(content))
    .build();

// Pipeline 2: Transform data
PipelineBehavior<RawData, CleanData> transformPipeline = builder(...)
    .addStage("clean", data -> cleanData(data))
    .addStage("validate", data -> validateData(data))
    .build();

// Pipeline 3: Load data
PipelineBehavior<CleanData, LoadResult> loadPipeline = builder(...)
    .addStage("prepare", data -> prepareForLoad(data))
    .addStage("load", data -> loadToDatabase(data))
    .build();

// Chain pipelines
String filename = "data.csv";
RawData raw = extractPipeline.executePipeline(filename).join().getOutput();
CleanData clean = transformPipeline.executePipeline(raw).join().getOutput();
LoadResult result = loadPipeline.executePipeline(clean).join().getOutput();
```

### Pattern 4: Retry with Backoff

```java
PipelineBehavior<Request, Response> retryPipeline = builder(...)
    .addStage("call-api", request -> {
        int attempts = 0;
        Exception lastError = null;
        
        while (attempts < 3) {
            try {
                return apiClient.call(request);
            } catch (TransientException e) {
                lastError = e;
                attempts++;
                Thread.sleep(1000 * attempts); // Linear backoff
            }
        }
        
        throw new RuntimeException("Failed after 3 attempts", lastError);
    })
    .stageTimeout(Duration.ofSeconds(30))
    .build();
```

---

## Troubleshooting

### Common Issues

#### Issue 1: Pipeline hangs indefinitely

**Symptom**: Pipeline execution never completes

**Causes**:
- Missing timeout configuration
- Stage has blocking I/O without timeout
- Deadlock in stage code

**Solution**:
```java
// Add stage timeout
.stageTimeout(Duration.ofSeconds(30))

// Add overall timeout
CompletableFuture<PipelineResult<O>> future = pipeline.executePipeline(input);
PipelineResult<O> result = future.get(60, TimeUnit.SECONDS); // Overall timeout
```

#### Issue 2: Pipeline fails but error message is unclear

**Symptom**: `result.getErrorMessage()` returns null or generic message

**Cause**: Exception doesn't have a message

**Solution**:
```java
// Use error callback for detailed logging
.onStageError((stage, error) -> {
    log.error("Stage {} failed", stage.getName(), error);
    // Log full stack trace
})
```

#### Issue 3: Memory leak with large data

**Symptom**: Memory usage grows over time

**Cause**: Pipeline holds references to large intermediate results

**Solution**:
```java
// Process data in chunks
.addStage("process-chunks", largeData -> {
    return StreamSupport.stream(largeData.spliterator(), false)
        .map(this::processChunk)
        .collect(Collectors.toList());
})

// Or reset metrics periodically
if (pipeline.getMetrics().getTotalExecutions() > 10000) {
    pipeline.resetMetrics();
}
```

#### Issue 4: Stage timeout too aggressive

**Symptom**: Stages frequently timeout

**Cause**: Timeout set too low for actual processing time

**Solution**:
```java
// Increase timeout
.stageTimeout(Duration.ofMinutes(1))

// Or remove timeout for trusted stages
PipelineBehavior pipeline = builder(...)
    .addStage("fast-stage", data -> quickProcess(data))
    // No timeout - let it run
```

#### Issue 5: Pipeline metrics inaccurate

**Symptom**: Success rate doesn't match expected

**Cause**: Mixing CONTINUE_ON_ERROR with metrics

**Solution**:
```java
// With CONTINUE_ON_ERROR, check individual stage results
PipelineResult<O> result = pipeline.executePipeline(input).join();

long failedStages = result.getStageResults().stream()
    .filter(sr -> !sr.success())
    .count();

if (failedStages > 0) {
    log.warn("Pipeline completed with {} failed stages", failedStages);
}
```

### Debug Checklist

- [ ] Stage timeouts configured appropriately
- [ ] Error strategy matches use case
- [ ] All stages have descriptive names
- [ ] Logging enabled for stage callbacks
- [ ] Metrics monitored and reset periodically
- [ ] Exception messages are meaningful
- [ ] Large data processed in chunks
- [ ] Pipeline instances reused (not recreated)

---

## Conclusion

PipelineBehavior provides a powerful and flexible way to build complex data processing workflows in Agenor Framework. By following the patterns and best practices in this guide, you can create robust, maintainable, and efficient pipelines.

### Quick Reference

```java
// Minimal pipeline
PipelineBehavior.<I, O>builder("pipeline", I.class, O.class)
    .addStage("stage1", data -> transform(data))
    .build();

// Production-ready pipeline
PipelineBehavior.<I, O>builder("pipeline", I.class, O.class)
    .addStage("stage1", data -> transform1(data))
    .addStage("stage2", data -> transform2(data))
    .stageTimeout(Duration.ofSeconds(30))
    .errorStrategy(ErrorHandlingStrategy.FAIL_FAST)
    .onStageError((stage, error) -> log.error("Error", error))
    .onPipelineComplete(result -> metrics.record(result))
    .build();
```

---

**Version**: 1.0.0  
**Last Updated**: November 2, 2025  
**Framework**: Agenor v0.2.0  
**Status**: ✅ Production Ready
