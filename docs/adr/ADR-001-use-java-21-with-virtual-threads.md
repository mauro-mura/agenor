# ADR-001: Use Java 21 with Virtual Threads

**Status**: Accepted  
**Date**: 2025-09-16  
**Authors**: Project Team  

### Context

Jentic aims to modernize multi-agent systems from the JADE era. We need to choose a Java version that provides modern concurrency features while maintaining reasonable compatibility.

### Decision

We will use **Java 21 LTS with Virtual Threads (Project Loom)** as the baseline for Jentic.

### Rationale

**Pros:**
- **Virtual Threads**: Perfect for agent systems where thousands of lightweight concurrent tasks are common
- **LTS Version**: Long-term support ensures stability
- **Modern Language Features**: Records, pattern matching, improved switch expressions
- **Performance**: Better garbage collection and JVM optimizations
- **Concurrency**: Simplified concurrent programming model

**Cons:**
- **Adoption**: Slower enterprise adoption compared to Java 17
- **Tooling**: Some tools may have limited Java 21 support initially

### Implementation

```java
// Virtual threads make agent behaviors naturally concurrent
@Behavior(type = CYCLIC, interval = "1s")
public void periodicTask() {
    // Each behavior runs in its own virtual thread
    // No need to manage thread pools
}

// Message handling is non-blocking
public CompletableFuture<Void> handleMessage(Message message) {
    return CompletableFuture.runAsync(() -> {
        // Process message
    }, Thread.ofVirtual().factory());
}
```

### Consequences

- **Positive**: Simplified concurrency model for agent behaviors
- **Positive**: Better resource utilization with thousands of agents
- **Positive**: Modern language features improve code quality
- **Negative**: Requires Java 21+ runtime environment
- **Negative**: May limit adoption in conservative environments
