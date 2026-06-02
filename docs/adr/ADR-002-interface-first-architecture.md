# ADR-002: Interface-First Architecture

**Status**: Accepted  
**Date**: 2025-09-16  
**Last Modified**: 2026-05-17  
**Authors**: Project Team  

### Context

We want Jentic to be extensible and allow evolution from simple implementations to enterprise-grade solutions without breaking changes.

### Decision

We will use an **Interface-First Architecture** where all core components are defined as interfaces, with multiple implementation strategies.

### Rationale

**Benefits:**
- **Extensibility**: Easy to add new implementations (Kafka, Consul, etc.)
- **Testing**: Easy to mock and test components in isolation
- **Evolution**: Can start simple and upgrade implementations
- **Decoupling**: Loose coupling between components

**Trade-offs:**
- **Initial Complexity**: More files and abstractions upfront
- **Learning Curve**: Developers need to understand the abstraction layers

### Implementation

```java
// Core interfaces in agenor-core — each capability is its own interface
public interface TopicPublisher {
    CompletableFuture<Void> publish(Message message);
}

public interface DirectMessenger {
    CompletableFuture<Void> sendTo(Message message);
}

// Composite facade composes all capabilities
public interface MessageDispatcher
        extends TopicPublisher, TopicSubscriber, DirectMessenger, DirectReceiver { }

// Simple implementation in agenor-runtime
public class InMemoryMessageDispatcher implements MessageDispatcher, FilterableSubscriber {
    // In-memory implementation using ConcurrentHashMap
}

// Custom implementations need only implement the relevant capability interfaces
public class CustomTopicPublisher implements TopicPublisher { ... }
```

### Implementation Strategy

1. **Phase 1**: Define interfaces in `jentic-core`
2. **Phase 2**: Implement basic versions in `jentic-runtime`
3. **Phase 3**: Add enterprise implementations in `jentic-adapters`
4. **Phase 4**: Allow custom implementations via SPI

### Consequences

- **Positive**: Clear separation of concerns
- **Positive**: Easy to test and mock
- **Positive**: Smooth migration path to enterprise features
- **Negative**: More initial boilerplate
- **Negative**: Potential over-abstraction
