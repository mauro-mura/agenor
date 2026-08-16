# ADR-030: `Message.content` Typing Across Transports

**Status**: Accepted
**Date**: 2026-08-16
**Authors**: Project Team
**References**: ADR-005 (JSON Message Format with Records), ADR-021 (Redis MessageTransport),
ADR-002 (Interface-First Architecture), ADR-004 (Progressive Complexity Strategy)

---

## Context

`Message.content` is declared `Object` (ADR-005). Nothing on the wire records what that object
was, and the two transports the framework ships disagree about what the receiver gets:

| Transport | What `content()` returns to the handler |
|---|---|
| `InMemoryMessageDispatcher` | the **same object reference** the sender passed |
| `RedisMessageDispatcher` (ADR-021) | a `LinkedHashMap` (or `String`/`Integer`/`List`, per JSON) |

`MessageCodec` serialises the whole `Message` with a plain `ObjectMapper`
(`agenor-adapters/.../redis/MessageCodec.java:38`) and reads it back with
`MAPPER.readValue(payload, Message.class)` (`:57`). Jackson has no type information for a field
typed `Object`, so it materialises the JSON object as a `Map`. This is correct behaviour for a
language-neutral wire format — the defect is that the receiving API pretends otherwise.

The result is a failure that is **invisible in every test we have and certain in production on a
multi-node deployment**:

```java
// ContractNetExample.java:117 — works in-memory, ClassCastException over Redis
Bid bid = (Bid) p.content();
```

No existing test catches it because every dialogue test uses the in-memory dispatcher. The
exposure is not limited to dialogue: more than twenty call sites in `agenor-examples` do
`(Map<String, Object>) message.content()` (e.g. `LLMDirectMessagingExample.java:83`), which
happens to survive the round trip only because `Map` is what Jackson produces anyway — those
call sites are coincidentally correct, not correct by design.

Three further facts shape the decision.

**1. `Message.getContent(Class<T>)` already exists and does nothing.** It is an unchecked cast
whose Javadoc explicitly documents that it performs no type checking:

```java
@SuppressWarnings("unchecked")
public <T> T getContent(Class<T> type) {
    return (T) content;                      // Message.java:336
}
```

It reads like the answer to this problem and is not. A caller who found it and trusted it gets
the same `ClassCastException`, just deferred to the use site where the cast is materialised.

**2. Cross-runtime dialogue is a stated deployment target**, not a hypothetical. Agents in
different runtimes already exchange messages over Redis messaging and A2A, and the planned
two-runtime demo (shared Postgres + Valkey) is the first place a non-`String` dialogue payload
crosses a process boundary. That moves this from a latent issue to a blocker for that demo.

**3. `MessageFilterBuilder.contentType(Class<?>)` filters with `instanceof`.** Like the cast, it
silently stops matching once the message has crossed a serialising transport. It is not changed
here, but it is the same root cause and is recorded so the next reader does not rediscover it as
a separate bug.

## Decision

### D1 — The conversion lives in `Message`, not in the dialogue layer

`Message.getContent(Class<T>)` is fixed to actually do what its name and Javadoc promise:

```java
public static <T> T convertContent(Object content, Class<T> type) {
    if (content == null) {
        return null;
    }
    if (type.isInstance(content)) {
        return type.cast(content);           // in-memory path: same reference
    }
    return MAPPER.convertValue(content, type);   // post-transport path: Map -> POJO
}
```

`getContent(Class<T>)` delegates to it. The conversion is exposed as a public static so that
`DialogueMessage` — a different package — can delegate to the same implementation without
constructing a throwaway `Message`.

Scoping this as a `Message` decision rather than a dialogue one is deliberate. The exposure is
framework-wide; dialogue is only where it bites hardest, because dialogue payloads are the ones
most likely to be domain records rather than strings and maps. Two implementations of the same
conversion, one in `Message` and one in `DialogueMessage`, would be a defect waiting to happen
the first time they diverge.

`Message.content`'s declared type stays `Object`. Narrowing it is a breaking change to the
central record of the framework and would not fix anything this decision does not.

### D2 — The mapper must be configured identically to `MessageCodec`

`agenor-core` gains a private static `ObjectMapper` configured exactly as the Redis codec's:
`findAndRegisterModules()`, `disable(WRITE_DATES_AS_TIMESTAMPS)`,
`disable(FAIL_ON_UNKNOWN_PROPERTIES)`.

This is a correctness requirement, not tidiness. The codec is what *produced* the `Map` being
converted; if the two configurations diverge — most visibly on `java.time` handling, where
`findAndRegisterModules()` is what makes `Instant` round-trip — a payload the codec wrote
successfully fails to convert back. jackson-databind is already a declared dependency of
`agenor-core`, so no new dependency is introduced.

The duplication of configuration between the two modules is accepted: `agenor-core` must not
depend on `agenor-adapters`, and hoisting a shared mapper into core purely for the codec's
benefit would invert the module direction. A comment on each side names the other.

### D3 — Failure is `IllegalArgumentException`, and conversion stays lenient

When the content is neither an instance of the requested type nor convertible to it, the caller
gets an `IllegalArgumentException` naming both the actual runtime type and the requested one.

A bare `ClassCastException` thrown from inside Jackson tells the developer nothing about which
message, which payload, or which of the two paths failed — and the whole point of this ADR is
that the in-memory and post-transport paths look identical at the call site.

This is a behaviour change on a public method. It is strictly a widening: the set of inputs that
previously produced a working result is unchanged (an in-instance content still returns the same
reference), inputs that previously produced a deferred `ClassCastException` now either convert
successfully or fail immediately with a better message. Nothing that worked stops working.

**Conversion stays lenient, and this bounds what the accessor promises.** Because the mapper
ignores unknown properties (D2), reading a post-transport payload as an *unrelated* record does
not throw — it succeeds and yields an object whose fields are null or default, since a `Map` has
no identity with which to contradict the request. Only a payload with an incompatible *shape*
(a scalar read as a record, say) fails.

Strictness was considered and rejected: a receiver must keep reading a payload whose sender has
added a field, which is the ordinary way a message schema evolves across independently deployed
runtimes, and rejecting unknown properties would turn every additive change into a break. The
consequence is stated rather than hidden: **this accessor removes the `ClassCastException`, not
the need to ask for the right type.** The `content-class` header (D4) is what lets a receiver
check the sender's claim when it cares; a test pins the lenient behaviour so it stays a decision
rather than a surprise.

### D4 — The type hint travels in a header named `content-class`

`DialogueMessage.toMessage()` writes the payload's fully-qualified class name into a header when
the content is non-null:

```
content-class: com.example.Bid
```

**Not `content-type`.** That name is already spoken for, twice and inconsistently: ADR-005's own
message example uses it for a domain label (`"content-type": "weather-data"`), and
`docs/message-filtering.md:93` documents it as an HTTP-style media type
(`HeaderFilter.startsWith("content-type", "application/")`). Writing a Java class name into it
would silently redefine a documented example and break that filter for dialogue traffic. A Java
FQCN is not a media type; it deserves its own key.

The header is **informational**. Conversion is driven by the type the caller asks for, never by
the header — a peer written in another language neither writes nor understands it, and honouring
it would make the wire format Java-specific, which is exactly what ADR-005 set out to avoid.
What it buys is diagnostics: when a conversion fails, the error can say what the sender claimed
to be sending. It also gives a Java peer enough information to route on payload type without
deserialising, which is what the broken `contentType(Class)` filter wanted.

No change to `MessageCodec` is needed: it serialises the entire `Message`, headers included, so
the hint crosses the wire for free.

### D5 — `DialogueMessage.contentAs(Class<T>)` is the dialogue-side accessor

`DialogueMessage` is a record, not a `Message`, so it needs its own entry point. It delegates to
`Message.convertContent(...)` and enriches the failure message with the `content-class` hint when
one is present. This is the method example and adopter code should call; `p.content()` followed
by a cast remains possible and remains a bug over any serialising transport.

## Options considered

1. **Jackson polymorphic typing (`@JsonTypeInfo` on `content`).** Rejected. It embeds Java
   fully-qualified class names in the wire format, so a Python or Go peer receives `@class`
   fields it cannot use and cannot produce — a direct contradiction of ADR-005's
   "Interoperability: JSON works with any language". It also makes deserialisation a
   class-loading operation driven by remote input, which is a security posture no one asked for.
2. **Document that payloads are JSON-shaped and let callers handle maps.** Rejected. It is the
   status quo with a paragraph attached: every adopter reimplements the same `convertValue` call,
   the existing `getContent(Class)` stays a trap, and the twenty-plus example call sites keep
   casting. "Progressive complexity" (ADR-004) means the simple path works and the advanced path
   is available — not that the simple path silently breaks on the second rung of the ladder.
3. **Put the conversion only on `DialogueMessage`, leave `Message.getContent(Class)` alone.**
   Rejected: fully additive and zero-risk, but it leaves a public method in `agenor-core` that
   looks like the solution and is not, ships two methods that appear to do the same thing, and
   helps none of the non-dialogue call sites. The exposure is framework-wide, so the fix belongs
   where the exposure is.
4. **Narrow `Message.content` to a sealed payload type or `JsonNode`.** Rejected: a breaking
   change to the framework's central record, and it would force every in-memory sender to
   serialise something that never needed serialising — a real cost paid by the majority path to
   fix the minority one.

## Consequences

### Positive

- A dialogue payload that is not a `String` survives the Redis transport and is usable by the
  receiving handler without a raw cast. This is what makes cross-runtime dialogue actually
  usable rather than nominally supported.
- The in-memory path is unchanged and allocation-free: `type.isInstance(content)` short-circuits
  before Jackson is involved, and the caller gets the identical reference back.
- `Message.getContent(Class)` stops being a trap.
- The same call works on both transports, so a test written against the in-memory dispatcher now
  says something about the Redis one.

### Negative / trade-offs

- **Behaviour change on a public method** (D3). Documented in the 0.26.0 migration guide.
- Converting a `Map` to a POJO is not free — one Jackson `convertValue` per call, on the
  post-transport path only. Handlers that call `contentAs` repeatedly on the same message should
  hold the result. Not cached inside the record: `DialogueMessage` is immutable and a cache
  keyed by requested type would be the first mutable field on it.
- **Asking for the wrong record type fails silently** rather than loudly (D3): the caller gets an
  object with empty fields. This is the price of the leniency that lets payload schemas evolve,
  and it is the one sharp edge this ADR leaves in place.
- The `content-class` hint is Java-specific by nature. It is optional, absent from
  non-Java senders, and nothing depends on its presence — but it is one more header on the wire.
- `MessageFilterBuilder.contentType(Class<?>)` remains broken over serialising transports. It is
  named here rather than fixed, because fixing it means deciding whether a filter may consult a
  header written only by dialogue senders — a smaller decision that should be taken when someone
  needs it, not bundled into this one.

## Implementation and verification

The assertion that carries this ADR is a **transport** test, not another in-memory one — the
absence of exactly that test is why the defect survived this long:

- In `agenor-adapters`, round-trip a `Message` carrying a POJO through `MessageCodec.encode` /
  `decode`, then assert `getContent(Pojo.class)` returns an **equal** instance. Assert in the
  same test that raw `content()` is a `Map` — that is the documentation of the bug, and it is
  what makes the test fail if someone "simplifies" the conversion away.
- The in-memory counterpart asserts the **identical reference** is returned, so the two paths are
  pinned to their respective contracts.
- A negative test: requesting an incompatible type yields `IllegalArgumentException` naming both
  types, not a `ClassCastException` at the use site.

## Related ADRs

- **ADR-005** (JSON Message Format with Records): established `Object content` and the
  language-neutral JSON wire format. Amended by this ADR with a scoped note; the format decision
  itself is unchanged — this ADR adds the receiving-side accessor ADR-005 never specified.
- **ADR-021** (Redis MessageTransport): the transport on which the defect manifests.
- **ADR-004** (Progressive Complexity Strategy): moving from the in-memory dispatcher to Redis is
  supposed to require no user-code change. Before this ADR it silently did.
- **ADR-002** (Interface-First Architecture): the conversion lives on the `Message` record, which
  both transports already share, rather than in either implementation.
