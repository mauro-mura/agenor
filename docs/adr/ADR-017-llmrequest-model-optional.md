# ADR-017: LLMRequest model field — optional with provider fallback

**Status**: Accepted  
**Date**: 2026-04-12  
**Authors**: Agenor Team  
**Replaces**: Partial update to ADR-007 (LLMProvider as Core Interface)

---

## Context

`LLMRequest` carries a mandatory `String model` field, enforced by two mechanisms:

1. `LLMRequest.builder(String model)` — model is the required constructor argument
2. `LLMProvider.validateRequest()` default implementation — throws `LLMException` if
   `model` is null or blank

In all three current adapter implementations (`OpenAIProvider`, `AnthropicProvider`,
`OllamaProvider`), the model is configured once at provider construction time via
`Builder.modelName(...)`. The LangChain4j client is instantiated with that fixed model.
When `chat(LLMRequest)` is called, `request.model()` is never forwarded to the
underlying client — the `LLMResponse` is built using the provider's own `modelName`
field, not the value from the request.

This was confirmed by inspecting `OpenAIProvider.chat()`:

```java
// request.model() is validated but never passed to chatModel
ChatResponse response = chatModel.chat(chatRequestBuilder.build());
LLMResponse.Builder builder = LLMResponse.builder(response.id(), modelName); // provider field
```

The consequence: `request.model()` is required, validated, and silently ignored —
dead required data. This inconsistency became evident during the refactoring of
`ModelTokenLimits` ownership, which introduced typed `Models` enums per adapter and
`getDefaultModel()` overrides. These make the provider model identity explicit and
queryable without involving `LLMRequest`.

---

## Decision

`LLMRequest.model` becomes **optional** (`@Nullable`). The field is retained to support
explicit per-request model override but is no longer required.

The provider resolves the effective model using this precedence:

1. `request.model()` — if non-null and non-blank (explicit caller override)
2. `provider.getDefaultModel()` — provider's configured model
3. `LLMException("No model specified")` — neither source provides a model

`validateRequest()` is updated: the null-model check is removed. Model resolution
moves to the provider's `chat()` execution path, where it is passed to the underlying
client.

`LLMRequest.builder(String model)` is deprecated but retained for one release cycle.
A no-argument `LLMRequest.builder()` is added as the preferred factory.

---

## Rationale

### Pros
- Eliminates dead required data — the field is no longer mandatory boilerplate when
  using a configured provider
- Enables cleaner usage in the common case:
  ```java
  // Before — model duplicated from provider configuration
  LLMRequest.builder("gpt-4o")
      .addMessage(LLMMessage.user("Hello"))
      .build();

  // After — model inferred from provider
  LLMRequest.builder()
      .addMessage(LLMMessage.user("Hello"))
      .build();
  ```
- Makes per-request model override explicit and intentional when used:
  ```java
  // Route a specific request to the reasoning model
  LLMRequest.builder()
      .model(OpenAIProvider.Models.O3)
      .addMessage(LLMMessage.user("Solve this complex problem..."))
      .build();
  ```
- Aligns with ADR-004 (Progressive Complexity): simple case requires no boilerplate,
  advanced case (per-request routing) remains possible

### Cons
- Breaking change on `LLMRequest` public API — existing callers pass model in the
  builder constructor. Deprecated `builder(String)` mitigates for one release cycle.
- Adapter `chat()` implementations must be updated to resolve the effective model
  and forward it to the LangChain4j client (currently fixed at construction time)

### Alternatives Considered

- **Keep model mandatory (status quo)**: rejected — required but silently ignored is
  worse than optional; it misleads callers into believing the value affects execution
- **Remove model from LLMRequest entirely**: rejected — per-request model override is
  a legitimate use case (e.g. routing cheap vs. expensive requests via one provider
  instance); removing it would force multiple provider instantiations
- **One provider per model as the only pattern**: rejected as the sole option — valid
  as a usage style and ergonomic with the new enum builder, but should not be forced

---

## Implementation

### LLMRequest changes

```java
// New no-arg factory (preferred)
public static Builder builder() { return new Builder(); }

// Deprecated factory (backward compat)
@Deprecated(since = "0.16.0", forRemoval = true)
public static Builder builder(String model) {
    return new Builder().model(model);
}

// model() accessor now @Nullable
public @Nullable String model() { return model; }
```

### LLMProvider.validateRequest() change

```java
default void validateRequest(LLMRequest request) throws LLMException {
    if (request == null) throw new LLMException("Request cannot be null");
    if (request.messages() == null || request.messages().isEmpty())
        throw new LLMException("Request must contain at least one message");
    // model null check removed — resolved at execution time by the provider
}
```

### Adapter chat() resolution pattern

```java
// In OpenAIProvider, AnthropicProvider, OllamaProvider
private String resolveModel(LLMRequest request) {
    String model = request.model();
    if (model != null && !model.isBlank()) return model;
    return this.modelName; // provider default
}
```

---

## Consequences

### Positive
- Common case (`LLMRequest.builder().addMessage(...).build()`) works without
  specifying model
- Per-request model routing is explicit and supported
- `validateRequest()` is simpler and honest about what it actually checks

### Negative
- `LLMRequest.builder(String)` deprecation requires caller migration
- All three adapter `chat()` and `chatStream()` implementations need the
  `resolveModel()` pattern

### Neutral
- `TokenBudgetManager` callers that use `request.model()` for context window lookup
  should use the resolved model; the resolution can be encapsulated in a provider
  helper method

---

## Compliance

- `LLMRequest.builder(String)` emits deprecation warning at compile time
- `validateRequest()` default in `LLMProvider` must not re-introduce the null-model check
- Each adapter must implement `resolveModel(LLMRequest)` or equivalent
