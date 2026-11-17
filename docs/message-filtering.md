# Message Filtering Guide

This guide covers the message filtering API: deciding which messages a subscription receives.

The filtering subsystem spans two packages:
- **`agenor-core` / `dev.agenor.core.filter`** — the `MessageFilter` interface, its combinators, and `MessageFilterBuilder`
- **`agenor-runtime-ext` / `dev.agenor.runtime.filter`** — concrete filter implementations (split out of `agenor-runtime` per ADR-027)

---

## MessageFilter interface

`MessageFilter` extends `Predicate<Message>`, so any lambda `msg -> boolean` is a valid filter.

```java
// Lambda syntax
MessageFilter myFilter = msg -> "orders.created".equals(msg.topic());

// Utilities
MessageFilter all     = MessageFilter.acceptAll();   // always true
MessageFilter none    = MessageFilter.rejectAll();   // always false
MessageFilter wrapped = MessageFilter.of(predicate); // wrap any Predicate<Message>

// Default combinators
MessageFilter combined = filterA.and(filterB);       // both must pass
MessageFilter either   = filterA.or(filterB);        // either passes
MessageFilter inverted = filterA.negate();           // logical NOT
```

### Performance characteristics

| Filter type | Cost |
|-------------|------|
| Topic (exact/prefix) | O(1) — string equality or `startsWith` |
| Header equality | O(1) — map lookup |
| Header/topic regex | O(n) — regex match on each message |
| Content predicate | depends on predicate |
| Composite (AND/OR) | short-circuits on first failure/success |

---

## Concrete filter classes (agenor-runtime-ext)

All classes are in `dev.agenor.runtime.filter` and implement `MessageFilter`.

### TopicFilter

Matches messages by topic pattern.

```java
import dev.agenor.runtime.filter.TopicFilter;

// Exact match
TopicFilter.exact("orders.created");

// Prefix match
TopicFilter.startsWith("orders.");

// Suffix match
TopicFilter.endsWith(".error");

// Wildcard — * expands to any characters
TopicFilter.wildcard("orders.*.created");     // matches "orders.usa.created"
TopicFilter.wildcard("*.notification");       // matches "user.notification"

// Full regex
TopicFilter.regex("orders\\.(created|updated)");
```

### HeaderFilter

Matches messages by header key/value.

```java
import dev.agenor.runtime.filter.HeaderFilter;

// Header must exist (any value)
HeaderFilter.exists("x-trace-id");

// Exact value
HeaderFilter.equals("priority", "HIGH");

// Regex on value
HeaderFilter.matches("region", "us-.*");

// Value in set
HeaderFilter.in("priority", "HIGH", "CRITICAL");

// Value starts with prefix
HeaderFilter.startsWith("content-type", "application/");
```

### ContentFilter

Matches messages by payload content.

```java
import dev.agenor.runtime.filter.ContentFilter;

// Content must be of a specific type
ContentFilter.ofType(OrderData.class);

// Content must be non-null
ContentFilter.notNull();

// Custom predicate on content
ContentFilter.matching(obj ->
    obj instanceof OrderData order && order.amount() > 1000
);
```

### Any predicate

`MessageFilter.of` wraps any `Predicate<Message>`.

```java
import dev.agenor.core.filter.MessageFilter;

MessageFilter fromPartnerAgents = MessageFilter.of(
    msg -> msg.senderId() != null && msg.senderId().startsWith("partner-")
);
```

> This routes; it does not authorise. `senderId` is a field the sender wrote, and no transport
> verifies it — on Redis in particular, anything that can reach the broker can name itself
> whatever it likes. A filter on `senderId` is a naming convention, useful for keeping traffic
> apart and useless against anyone who does not want to be kept apart. See
> [Trust model](adapters/redis.md#trust-model-the-transport-authenticates-nothing).

### Combining filters

Every `MessageFilter` combines with any other — no separate composite type is involved.

```java
MessageFilter topic  = TopicFilter.startsWith("orders.");
MessageFilter urgent = HeaderFilter.equals("priority", "HIGH");
MessageFilter region = HeaderFilter.in("region", "eu-west", "eu-central");

// AND — both must pass
MessageFilter both = topic.and(urgent);

// OR — either may pass
MessageFilter either = urgent.or(region);

// NOT — inverts
MessageFilter notInternal = TopicFilter.startsWith("internal.").negate();
```

`MessageFilter.acceptAll()` and `MessageFilter.rejectAll()` are the identity and empty filters.

---

## MessageFilterBuilder (fluent API)

`MessageFilterBuilder` (in `agenor-core`) provides a single chainable builder that produces an `AND`-combined `MessageFilter` by default.

```java
import dev.agenor.core.filter.MessageFilter;

MessageFilter filter = MessageFilter.builder()
    .topicStartsWith("orders.")          // topic prefix
    .topicMatches("orders\\..*")         // regex (alternative)
    .headerEquals("priority", "HIGH")    // exact header value
    .headerExists("x-trace-id")          // header presence
    .headerMatches("region", "us-.*")    // regex on header value
    .headerIn("priority", "HIGH", "CRITICAL")  // value in set
    .contentType(OrderData.class)        // content instanceof
    .contentPredicate(obj ->
        obj instanceof OrderData o && o.amount() > 500)  // content predicate
    .customPredicate(msg ->
        msg.senderId() != null)          // full message predicate
    .build();
```

Switch to OR mode:

```java
import dev.agenor.core.filter.FilterOperator;

MessageFilter filter = MessageFilter.builder()
    .operator(FilterOperator.OR)
    .topicStartsWith("orders.")
    .topicStartsWith("payments.")
    .build();
```

Additional routing fields:

```java
MessageFilter.builder()
    .senderId("producer-agent")
    .receiverId("consumer-agent")
    .correlationId("req-42")
    .build();
```

---

## Registering filters on subscriptions

> **`subscribeFiltered` works in memory only.** It belongs to `FilterableSubscriber`, which only
> `InMemoryMessageDispatcher` implements: a Java predicate cannot run inside a broker. On Redis,
> `getMessageDispatcher()` is not a `FilterableSubscriber`, so every cast to it on this page
> throws `ClassCastException` when the agent starts. Code that may move to Redis subscribes by
> exact topic and applies the filter in the handler, as in
> [Filtering on every transport](#filtering-on-every-transport). The Redis side of this is in
> [the Redis adapter page](adapters/redis.md), section *FilterableSubscriber — not supported*.

### Filtering on every transport

A `MessageFilter` is a `Predicate<Message>`, so the same filter that `subscribeFiltered` takes can
guard a handler subscribed by exact topic. This runs unchanged on the in-memory dispatcher and on
Redis:

```java
@Agent("order-processor")
public class OrderProcessorAgent extends BaseAgent {

    private final MessageFilter urgent = HeaderFilter.equals("priority", "HIGH");

    @Override
    protected void onStart() {
        registerTopicSubscription(getMessageDispatcher().subscribeTopic("orders.created",
                MessageHandler.sync(msg -> {
                    if (urgent.test(msg)) {
                        processUrgentOrder(msg.getContent(OrderData.class));
                    }
                })));
    }
}
```

`registerTopicSubscription` releases the subscription when the agent stops. What this cannot
express is a topic *pattern*: on Redis a subscription names one topic, so an agent that needs
`orders.*` subscribes to each topic it handles.

### Programmatic subscription, in memory

Pass a `MessageFilter` (or any `Predicate<Message>`) to `FilterableSubscriber.subscribeFiltered()`. Only the in-memory dispatcher implements this capability:

```java
// Using concrete filter classes
MessageFilter filter = TopicFilter.startsWith("orders.")
    .and(HeaderFilter.equals("priority", "HIGH"));

FilterableSubscriber filterable = (FilterableSubscriber) dispatcher;
filterable.subscribeFiltered(filter, message -> {
    OrderData order = message.getContent(OrderData.class);
    processUrgentOrder(order);
});
```

### Inside an agent's onStart(), in memory

```java
@Agent("order-processor")
public class OrderProcessorAgent extends BaseAgent {

    @Override
    protected void onStart() {
        MessageFilter filter = MessageFilter.builder()
            .topicStartsWith("orders.")
            .headerIn("status", "pending", "confirmed")
            .build();

        ((FilterableSubscriber) getMessageDispatcher()).subscribeFiltered(filter, MessageHandler.sync(this::handleOrder));
    }

    private void handleOrder(Message msg) {
        // Only receives orders with status = pending or confirmed
    }
}
```

### With @AgenorMessageHandler and inline filtering

`@AgenorMessageHandler` routes by **exact** topic — it takes no pattern, and a value containing
`*` or `#` is rejected when the agent is registered. For anything else, combine it with a guard
inside the handler, which works on every transport, or use programmatic subscription with a
filter as above, which works in memory only:

```java
@AgenorMessageHandler("orders.created")
public void handleOrder(Message msg) {
    // Topic matched exactly by the annotation; everything else is checked here
    if (!"HIGH".equals(msg.headers().get("priority"))) {
        return;
    }
    processOrder(msg.getContent(OrderData.class));
}
```

To route on a topic *pattern*, subscribe programmatically with `TopicFilter.wildcard(...)`.
The annotation has no equivalent, and neither has Redis: this works in memory only.

```java
((FilterableSubscriber) getMessageDispatcher())
    .subscribeFiltered(TopicFilter.wildcard("orders.*"), this::handleOrder);
```

---

## Complete example: a filtered agent, in memory

```java
@Agent("order-enricher")
public class OrderEnricherAgent extends BaseAgent {

    @Override
    protected void onStart() {
        // Only handle urgent orders from external sources
        MessageFilter filter = TopicFilter.startsWith("orders.")
            .and(HeaderFilter.equals("priority", "HIGH"))
            .and(TopicFilter.startsWith("orders.internal.").negate());

        ((FilterableSubscriber) getMessageDispatcher())
            .subscribeFiltered(filter, MessageHandler.sync(this::enrichOrder));
    }

    private void enrichOrder(Message msg) {
        // Call the enrichment API
    }
}
```

---

## See Also

- [Agent Development Guide](agent-development.md) — `@AgenorMessageHandler`, behaviors
- [Architecture Guide](architecture.md) — module overview
- [Behaviors](behaviors/README.md) — where rate limiting belongs now
