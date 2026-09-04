# Messaging

This document describes Agenor's messaging API introduced in **0.20.0**. It replaces the monolithic `MessageService` with a set of capability-sized interfaces designed to work with both in-memory and distributed backends.

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

### Via AgenorRuntime

```java
AgenorRuntime runtime = AgenorRuntime.builder().build();
runtime.start().join();

MessageDispatcher dispatcher = runtime.getMessageDispatcher();
```

### Via Spring Boot

`MessageDispatcher` is exposed as a Spring bean automatically by `AgenorAutoConfiguration`:

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
- **Fire-and-forget**: `publish` and `sendTo` return `CompletableFuture<Void>` that completes
  when all handler invocations have been dispatched, not when they finish
- **Redelivery depends on the transport**: in-memory delivery is at-most-once and a missed
  message is not replayed. Redis Streams redelivers a message whose handler did not acknowledge
  it, and dead-letters it after `maxDeliveryAttempts`
- **What the framework gives up on does not depend on the transport**: however many attempts
  a transport makes, the message that survives none of them is recorded in the runtime's
  [dead-letter queue](#when-delivery-fails-for-good) rather than lost to a log line

Three consequences reach the code you write.

**Your handlers may overlap.** An agent claims its messages in arrival order, one at a time, but
it does not *handle* them one at a time: each handler runs on its own virtual thread, so
handlers may overlap and finish out of order. Guard shared state exactly as you would without
an agent framework. Up to 64 of one agent's handlers run at once by default; past that the agent
applies backpressure to whatever is delivering to it — and *what* is delivering to it decides
who feels that. On Redis it is the node's consumer loop, so a saturated agent slows delivery
for every agent on its node. In memory it is a virtual thread nobody is waiting on, so the
sender's future has already completed and nothing reaches the publisher: the bound protects the
agent, not the producer. The send side is unbounded on both.

**Make your handlers idempotent.** A handler that throws does not acknowledge its message, so on
a transport with redelivery the same message arrives again — see
[Redis Messaging](adapters/redis.md#failure-modes-and-recovery). If you want a failure contained
rather than retried, catch it in the handler, where the decision is visible.

**Return your future when the work is not done yet.** Everything above applies to what a
handler *returns*, so a handler that starts an LLM call or a database write and returns `void`
is finished as far as the framework is concerned: the message is acknowledged immediately, a
later failure in the chain is invisible, and the work does not count against the 64. Declare the
handler as returning `CompletableFuture<Void>` and return the chain instead.

```java
// Inside the guarantees.
@AgenorMessageHandler("research.request")
public CompletableFuture<Void> onRequest(Message message) {
    return llm.chat(request(message)).thenAccept(this::publishFindings);
}

// Outside them: acknowledged before the call has even started.
@AgenorMessageHandler("research.request")
public void onRequest(Message message) {
    llm.chat(request(message)).thenAccept(this::publishFindings);
}
```

Before 0.30.0 the first form did not register at all — a handler returning anything but `void`
was rejected as an invalid signature — so the second was the only shape an asynchronous handler
could have.

Why the inbound path is shaped this way is
[ADR-032](adr/ADR-032-agent-mailbox-single-inbound-path.md); what a failed handler costs is
[ADR-033](adr/ADR-033-mailbox-delivery-semantics.md).

## When delivery fails for good

A handler that keeps throwing eventually exhausts whatever its transport was willing to try.
What happens then is the same on every transport: the message is recorded in the runtime's
dead-letter queue, with the reason, the recipient, how many attempts were made and when the
framework gave up.

```java
for (DeadLetter dl : runtime.getDeadLetterQueue().recent(20)) {
    log.warn("gave up on {} to {} after {} attempt(s): {}",
            dl.message().id(), dl.recipientId(), dl.attempts(), dl.reason());
}
```

Reading is the point. Without it this would be a logger with more steps — the entry exists so
that an operator can find the payload that has to be re-sent by hand, a test can assert on the
failure, and the console can show it (`GET /api/deadletters`).

What the queue *reaches* differs, and is worth knowing before you rely on it:

| | Default | Attempts before it lands here | How far back `recent` sees |
|---|---|---|---|
| In-memory | `InMemoryDeadLetterQueue`, 256 entries | 1 — there is no retry | the buffer, forgotten on restart |
| Redis | `RedisDeadLetterQueue` over `<stream>:dlq` | `maxDeliveryAttempts`, default 3 | as far as the stream is retained |

The in-memory queue is the default and needs no wiring. On Redis, hand the runtime the
adapter's queue so both the console and your own code read the durable one:

```java
AgenorRuntime.builder()
        .messageDispatcher(factory.messageDispatcher())
        .deadLetterQueue(factory.deadLetterQueue())
        .build();
```

Recording is not retrying, and it is not supervision: nothing restarts an agent or backs off on
its behalf. The failure becomes visible; deciding what to do about it is still yours.

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
2. Register it as a Spring bean or pass it to `AgenorRuntime.Builder.messageDispatcher()`.

```java
// Custom Redis-backed dispatcher (example)
@Bean
public MessageDispatcher redisMessageDispatcher(RedisTemplate<String, Message> template) {
    return new RedisMessageDispatcher(template);
}
```

The runtime will use your implementation instead of the default `InMemoryMessageDispatcher`.
