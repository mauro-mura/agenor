# ADR-005: JSON Message Format with Records

**Status**: Accepted  
**Date**: 2025-09-16  
**Last Modified**: 2026-08-16 (see Amendment below)  
**Authors**: Project Team  

### Context

We need a message format that is human-readable, language-agnostic, performant, and leverages modern Java features.

### Decision

We will use **JSON as the message serialization format** with **Java Records** for message representation.

### Message Structure

```java
public record Message(
    String id,                    // Unique message identifier
    String topic,                 // Message topic/channel
    String senderId,              // Sending agent ID
    String receiverId,            // Target agent ID (optional)
    String correlationId,         // For request/response patterns
    Object content,               // Message payload
    Map<String, String> headers,  // Additional metadata
    Instant timestamp             // Message creation time
) {
    // Builder pattern and utility methods
}
```

### Serialization

- **Library**: Jackson (widely adopted, excellent performance)
- **Format**: JSON (human-readable, debugging-friendly)
- **Types**: Full support for Java time types, collections
- **Compatibility**: Cross-language interoperability

### Benefits

- **Immutability**: Records are immutable by default
- **Conciseness**: Less boilerplate compared to traditional classes
- **Performance**: Jackson handles records efficiently
- **Debugging**: JSON is human-readable
- **Interoperability**: JSON works with any language

### Message Examples

```json
{
  "id": "msg-12345",
  "topic": "weather.data",
  "senderId": "weather-agent-1",
  "content": {
    "location": "Rome",
    "temperature": 22.5,
    "humidity": 65
  },
  "headers": {
    "content-type": "weather-data",
    "priority": "normal"
  },
  "timestamp": "2024-03-15T10:30:00Z"
}
```

### Consequences

- **Positive**: Modern, immutable, concise message representation
- **Positive**: Human-readable format aids debugging
- **Positive**: Cross-language compatibility
- **Positive**: Excellent tooling support
- **Negative**: Slightly larger than binary formats
- **Negative**: JSON parsing overhead (acceptable for most use cases)

---

### Amendment (2026-08-16) — receiving-side typing, per ADR-030

This ADR fixed the wire format but never specified what the **receiver** gets back. Because
`content` is declared `Object`, a serialising transport materialises a JSON object as a `Map`,
while the in-memory dispatcher hands over the original reference — so the same handler code
works on one transport and throws `ClassCastException` on the other.

[ADR-030](ADR-030-message-content-typing-across-transports.md) closes that gap without changing
anything decided here: `Object content` stays, JSON stays, the format stays language-neutral. It
adds `Message.getContent(Class<T>)` as a real converting accessor and an optional `content-class`
header carrying the payload's Java type as a hint.

Two details of this document are superseded in scope:

- The `"content-type": "weather-data"` header in the example above is a **domain label**, not a
  type hint and not an HTTP media type. ADR-030 deliberately does not reuse that key.
- "Types: Full support for Java time types, collections" describes what Jackson can serialise,
  not what survives as a typed object on the receiving end. For a payload that is a domain
  record, only ADR-030's accessor gives it back.
