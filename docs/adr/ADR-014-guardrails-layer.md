# ADR-014 — Guardrails Layer

**Status**: Accepted  
**Date**: 2026-03-23  
**Deciders**: Project team

---

## Context

`LLMAgent` has no built-in mechanism to validate or transform inputs before they reach the LLM,
nor outputs before they reach the consumer. This creates risks:

- PII leakage in both directions (user input → LLM, LLM response → consumer)
- Content policy violations passing through undetected
- Structurally invalid LLM outputs consumed without validation
- No declarative way to attach validation rules to an agent class

Constraints:
- `jentic-core` must remain free of external dependencies (ADR-002)
- `LLMAgent` without guardrails must behave identically to current behaviour (backward compat)
- Must be composable: multiple guardrails chained together
- Must integrate with the existing annotation-based configuration pattern (ADR-006)
- Java 21 target (ADR-001) — sealed interfaces and records are available

---

## Decision

Introduce a **declarative interceptor chain** (`GuardrailChain`) inserted into `LLMAgent`
at two hook points: **pre-input** (before `callLLM()`) and **post-output** (after `callLLM()`).

```
User input
  → InputGuardrailChain   (PII redaction, token limit, content policy)
  → LLMProvider.chat()
  → OutputGuardrailChain  (schema validation, safety filter)
  → Consumer
```

Configurable via fluent builder **or** the `@WithGuardrails` annotation,
wired automatically by `AgenorRuntime`.

---

## Alternatives Considered

### Option A — `GuardrailResult` as sealed interface (Java 21) ✅ Chosen

```java
public sealed interface GuardrailResult
    permits GuardrailResult.Passed,
            GuardrailResult.Modified,
            GuardrailResult.Blocked {
    record Passed()                    implements GuardrailResult {}
    record Modified(String newContent) implements GuardrailResult {}
    record Blocked(String reason)      implements GuardrailResult {}
}
```

**Pros**:
- Exhaustive `switch` — compiler enforces handling all cases, no default needed
- Type-safe payload per variant (`newContent`, `reason`) — no unchecked casts
- Idiomatic Java 21; consistent with existing use of records (ADR-005)
- Enables future variant addition (e.g. `Warned`) without breaking existing code

**Cons**:
- Slightly more verbose than an enum for callers that only check `Passed`

---

### Option B — `enum GuardrailStatus` + `Map<String, Object>` payload ❌ Rejected

**Pros**:
- Simpler declaration

**Cons**:
- Payload access requires unchecked casts or `instanceof` + cast chains
- No compiler enforcement of payload presence per status
- Less readable; deviates from project's records-first style (ADR-005)

---

## Architecture

### Module Boundary

```
jentic-core
  └── dev.agenor.core.guardrail
        ├── GuardrailResult.java          (sealed interface — no external deps)
        ├── InputGuardrail.java           (functional interface)
        ├── OutputGuardrail.java          (functional interface)
        ├── GuardrailContext.java         (record: agentId, topic, metadata)
        ├── GuardrailViolationException.java
        └── WithGuardrails.java           (@interface annotation)

jentic-adapters (or jentic-runtime)
  └── dev.agenor.runtime.guardrail
        ├── GuardrailChain.java           (builder + sequential execution)
        ├── PiiRedactionGuardrail.java    (Input + Output)
        ├── ContentPolicyGuardrail.java   (Input + Output)
        ├── JsonSchemaOutputGuardrail.java (Output)
        └── MaxTokensInputGuardrail.java  (Input)
```

### Hook Position in `LLMAgent`

`LLMAgent` provides two protected hook methods that subclasses call around their LLM interactions.
This "utility hook" pattern ensures that guardrails are enforced consistently without forcing 
a specific LLM provider implementation in the base class.

```java
// Subclass implementation example:
public String chat(String rawInput) {
    GuardrailContext ctx = GuardrailContext.of(getAgentId());
    
    // 1. Pre-input hook
    String safeInput = applyInputGuardrails(rawInput, ctx); // throws GuardrailViolationException
    
    // 2. LLM Call
    String rawOutput = provider.chat(safeInput);
    
    // 3. Post-output hook
    return applyOutputGuardrails(rawOutput, ctx);
}
```

### Chain Execution Semantics

| Result     | Behaviour                                                          |
|------------|--------------------------------------------------------------------|
| `Passed`   | Content unchanged, next guardrail in chain receives same content   |
| `Modified` | Content replaced with `newContent`, chain continues with new value |
| `Blocked`  | Chain short-circuits, `GuardrailViolationException` thrown         |

### `@WithGuardrails` Wiring

```java
@WithGuardrails(
    input  = { PiiRedactionGuardrail.class, MaxTokensInputGuardrail.class },
    output = { PiiRedactionGuardrail.class, ContentPolicyGuardrail.class }
)
public class FinanceAgent extends LLMAgent { ... }
```

`AgenorRuntime` reads the annotation at agent registration time, instantiates guardrails via
reflection (no-arg constructor), builds and injects a `GuardrailChain`.  
Programmatic chain configuration and `@WithGuardrails` can be combined: the annotation-derived
chain is **prepended** to the programmatic guardrails (annotation guardrails run first).

---

## Consequences

### Positive
- PII, content policy, and schema validation enforced at the framework level — no per-agent boilerplate
- Backward compatible: `LLMAgent` without a configured chain is unaffected
- Exhaustive `switch` on `GuardrailResult` prevents silent mishandling of variants
- Composable: custom guardrails implement a single-method functional interface

### Negative
- Each guardrail in the chain executes synchronously and sequentially; complex chains add latency
- `@WithGuardrails` requires no-arg constructors — guardrails needing configuration must use the builder API

### Neutral
- `GuardrailViolationException` extends `AgenorException` (unchecked, consistent with the Jentic exception hierarchy); callers may catch it explicitly but are not required to declare it
- `JsonSchemaOutputGuardrail` re-prompt logic interacts with the LLM indirectly via `Modified` content instructions, not direct LLM calls

## Compliance

- `jentic-core` package `dev.agenor.core.guardrail` must have zero external dependencies (verified by `mvn dependency:analyze`)
- Coverage ≥ 80% on `dev.agenor.core.guardrail` and `dev.agenor.runtime.guardrail` (enforced by JaCoCo in `mvn verify`)
- `LLMAgent` backward-compatibility verified by existing test suite passing unchanged

## Notes

- Related: ADR-002 (Interface-First), ADR-005 (Records), ADR-006 (Annotations), ADR-012 (ReflectionBehavior — optional quality guardrail)
- `ReflectionBehavior` (ADR-012) may be used as an `OutputGuardrail` implementation in a future extension
