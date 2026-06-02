# EventDrivenBehavior - Message-Reactive Execution

## Overview

`EventDrivenBehavior` reacts to **incoming messages** on a topic instead of running on a schedule. It implements `MessageHandler` and is invoked by the agent's `MessageDispatcher` when a message arrives on the subscribed topic.

**Since**: v0.1.0 | **Type**: `BehaviorType.EVENT_DRIVEN` | **Package**: `dev.agenor.runtime.behavior`

---

## Usage

### Subclass

```java
public class OrderReceiverBehavior extends EventDrivenBehavior {

    public OrderReceiverBehavior() {
        super("order-receiver", "orders.incoming");
    }

    @Override
    protected void handleMessage(Message message) {
        Order order = (Order) message.content();
        orderService.process(order);
    }
}
agent.addBehavior(new OrderReceiverBehavior());
```

### Factory

```java
agent.addBehavior(EventDrivenBehavior.from("orders.incoming", message -> {
    Order order = (Order) message.content();
    return orderService.processAsync(order);
}));
```

---

## Constructors

```java
protected EventDrivenBehavior(String topic)
protected EventDrivenBehavior(String behaviorId, String topic)
```

## Factory Methods

```java
static EventDrivenBehavior from(String topic, MessageHandler handler)
```

## Topic

```java
String topic = behavior.getTopic();
```

---

## Notes

- `execute()` is a no-op; all work happens through `handle(Message)`.
- The behavior respects `isActive()`: messages are silently dropped when stopped.
- Exceptions in `handleMessage()` are caught and routed to `onError()`.

---

## See Also

- [CyclicBehavior](CyclicBehavior.md) - Schedule-driven execution
- [Behavior Overview](README.md)
