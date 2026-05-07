# Messaging

This document describes Jentic's messaging API introduced in **0.20.0**. It replaces the monolithic `MessageService` with a set of capability-sized interfaces designed to work with both in-memory and distributed backends.

## Overview

The messaging API is split into five focused interfaces, each representing a single capability:

| Interface | Capability | Method |
|-----------|-----------|--------|
| `TopicPublisher` | Publish to a topic | `publish(msg)` |
| `TopicSubscriber` | Subscribe to a topic | `subscribeTopic(topic, handler)` |
| `DirectMessenger` | Send to a named agent | `sendTo(msg)` |
| `DirectReceiver` | Receive messages addressed to self | `subscribeRecipient(agentId, handler)` |
| `FilterableSubscriber` | Subscribe with a predicate | `subscribeFiltered(filter, handler)` |

All subscribe methods return a `Subscription` object. Call `subscription.unsubscribe()` to cancel.

`MessageDispatcher` is the composite interface bundling the four core capabilities:

```java
interface MessageDispatcher extends TopicPublisher, TopicSubscriber, DirectMessenger, DirectReceiver {}
```

`FilterableSubscriber` is kept separate because not all distributed backends can support arbitrary in-process predicates efficiently.

## Getting the Dispatcher

### Via JenticRuntime

```java
JenticRuntime runtime = JenticRuntime.builder().build();
runtime.start().join();

MessageDispatcher dispatcher = runtime.getMessageDispatcher();
```

### Via Spring Boot

`MessageDispatcher` is exposed as a Spring bean automatically by `JenticAutoConfiguration`:

```java
@Service
public class MyService {
    private final MessageDispatcher dispatcher;

    public MyService(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }
}
```

### Standalone (no runtime)

```java
InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
InMemoryMessageDispatcher dispatcher = new InMemoryMessageDispatcher(directory);
```

## Publishing

```java
Message msg = Message.builder()
    .topic("order.created")
    .content(new Order("ORD-001", BigDecimal.valueOf(99.99)))
    .header("region", "us-east-1")
    .build();

dispatcher.publish(msg);  // routing reads msg.topic()
```

## Topic Subscriptions

```java
Subscription sub = dispatcher.subscribeTopic("order.created", msg -> {
    Order order = msg.getContent(Order.class);
    log.info("New order: {}", order.id());
    return CompletableFuture.completedFuture(null);
});

// Later:
sub.unsubscribe();
```

## Direct (Point-to-Point) Messaging

Direct messages are routed to a specific agent by ID. The agent must be registered in the `AgentDirectory`.

**Send side:**

```java
Message msg = Message.builder()
    .receiverId("inventory-agent")
    .content(payload)
    .build();

dispatcher.sendTo(msg)  // routing reads msg.receiverId()
    .exceptionally(ex -> {
        if (ex.getCause() instanceof AgentNotFoundException) {
            log.warn("Agent not found");
        }
        return null;
    });
```

**Receive side:**

```java
Subscription sub = dispatcher.subscribeRecipient("inventory-agent", msg -> {
    log.info("Direct message from {}: {}", msg.senderId(), msg.content());
    return CompletableFuture.completedFuture(null);
});
```

If the recipient agent ID is not found in the directory, `sendTo` completes exceptionally with `AgentNotFoundException`.

## Predicate Filtering

For fine-grained subscription logic, use `FilterableSubscriber`:

```java
InMemoryMessageDispatcher dispatcher = new InMemoryMessageDispatcher(directory);
// InMemoryMessageDispatcher also implements FilterableSubscriber

MessageFilter highPriorityOrders = MessageFilter.builder()
    .topicStartsWith("order.")
    .headerEquals("priority", "HIGH")
    .build();

Subscription sub = dispatcher.subscribeFiltered(highPriorityOrders, msg -> {
    // Only fires for high-priority order messages
    return CompletableFuture.completedFuture(null);
});
```

See [Message Filtering](message-filtering.md) for the full filter DSL reference.

## Subscription Lifecycle

Every subscription returns a `Subscription` object:

```java
Subscription sub = dispatcher.subscribeTopic("my.topic", handler);

String id = sub.subscriptionId(); // Unique identifier
sub.unsubscribe();                // Cancel and clean up
```

Subscriptions are not automatically cleaned up on agent stop — call `unsubscribe()` in `onStop()` or use a try-with-resources pattern via a custom `AutoCloseable` wrapper.

## Delivery Semantics

- **Virtual threads**: each handler invocation runs on a new virtual thread (Java 21)
- **At-most-once**: delivery is not persistent; missed messages are not replayed
- **Fire-and-forget**: `publish` and `sendTo` return `CompletableFuture<Void>` that completes when all handler invocations have been dispatched, not when they finish
- **No backpressure**: the in-memory implementation does not throttle publishers

## Observability

Every `publish` and `sendTo` call creates an OpenTelemetry span named `message.send` with the following attributes:

| Attribute | Source |
|-----------|--------|
| `message.topic` | `msg.topic()` (publish) |
| `message.recipient` | `msg.receiverId()` (sendTo) |
| `message.id` | `msg.id()` |
| `agent.sender` | `msg.senderId()` |
| `endpoint.type` | resolved transport type (sendTo only) |

## Migration from MessageService (0.19.x → 0.20.0)

`MessageService` is deprecated in 0.20.0 and will be removed in 0.22.0. The table below shows the mapping:

| Old API | New API |
|---------|---------|
| `messageService.send(msg)` | `dispatcher.publish(msg)` or `dispatcher.sendTo(msg)` |
| `messageService.subscribe(topic, handler)` | `dispatcher.subscribeTopic(topic, handler)` |
| `messageService.subscribe(filter, handler)` | `dispatcher.subscribeFiltered(filter, handler)` |
| `messageService.unsubscribe(id)` | `subscription.unsubscribe()` |
| `runtime.getMessageService()` | `runtime.getMessageDispatcher()` |
| `new InMemoryMessageService()` | `new InMemoryMessageDispatcher(directory)` |

Existing code that uses `MessageService` continues to compile without changes via backward-compat bridge methods. Migrate at your own pace before 0.22.0.

## Custom Backends

To plug in a custom messaging backend (Redis Streams, Kafka, etc.):

1. Implement `MessageDispatcher` (and optionally `FilterableSubscriber`).
2. Register it as a Spring bean or pass it to `JenticRuntime.Builder.messageDispatcher()`.

```java
// Custom Redis-backed dispatcher (example)
@Bean
public MessageDispatcher redisMessageDispatcher(RedisTemplate<String, Message> template) {
    return new RedisMessageDispatcher(template);
}
```

The runtime will use your implementation instead of the default `InMemoryMessageDispatcher`.
