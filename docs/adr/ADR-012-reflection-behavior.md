# ADR-012: ReflectionStrategy as Core Interface

**Status**: Accepted  
**Date**: 2026-03-12  
**Authors**: Project Team

## Context

LLM-powered agents often produce outputs that benefit from iterative self-critique
before being returned to the caller. A Generate → Critique → Revise loop
(Reflection pattern, Gulli Cap. 4) is a foundational agentic pattern alongside RAG
(ADR-011) and memory management (ADR-010).

The question is where to place the `ReflectionStrategy` abstraction:

1. **`agenor-core` only** — pure interface, maximum reusability.
2. **`agenor-runtime` only** — closer to `LLMAgent`, simpler module graph.
3. **`agenor-core` interface + `agenor-runtime` implementation** — mirrors the
   established `LLMProvider` and `KnowledgeStore` patterns.

## Decision

Place `ReflectionStrategy`, `CritiqueResult`, and `ReflectionConfig` in **`agenor-core`**
as pure interfaces/records with zero external dependencies.

Place `DefaultReflectionStrategy` and `ReflectionBehavior` in **`agenor-runtime`**
as concrete implementations that depend on `LLMProvider`.

This mirrors the `LLMProvider` (ADR-007) and `KnowledgeStore` (ADR-011) patterns exactly.

## Alternatives Considered

- **`agenor-runtime` only**: Simpler module graph but prevents external modules from
  depending on the reflection abstraction without pulling in runtime. Rejected —
  conflicts with ADR-002 (interface-first) and ADR-004 (progressive complexity).
- **`agenor-core` with default implementation**: Avoids a second module but forces
  `agenor-core` to depend on `LLMProvider` (or duplicate it). Rejected — breaks the
  zero-external-dependency invariant of `agenor-core`.

## Consequences

- **Positive**: `ReflectionStrategy` is composable by any module depending only on
  `agenor-core`, consistent with `LLMProvider` and `KnowledgeStore`.
- **Positive**: `DefaultReflectionStrategy` in `agenor-runtime` reuses the existing
  `LLMProvider` abstraction without new dependencies.
- **Positive**: `ReflectionBehavior` wraps `OneShotBehavior` — no breaking changes
  to existing `LLMAgent` usage.
- **Negative**: Two additional packages across two modules (small surface area,
  mitigated by clear naming and consistent pattern).

## Module Layout

```
agenor-core
  dev.agenor.core.reflection
    ReflectionStrategy      (functional interface)
    CritiqueResult          (record: feedback, shouldRevise, score)
    ReflectionConfig        (record: maxIterations, scoreThreshold, critiquePrompt)

agenor-runtime
  dev.agenor.runtime.behavior          (existing package)
    ReflectionBehavior                 (behavior wrapper — lives with its peers)

  dev.agenor.runtime.reflection        (new package)
    DefaultReflectionStrategy          (LLMProvider-backed implementation)
```

`ReflectionBehavior` is placed in `dev.agenor.runtime.behavior` because it is a
first-class behavior (wraps `OneShotBehavior`) and developers expect to find all
behaviors in that package. `DefaultReflectionStrategy` belongs in the `reflection`
package as it is infrastructure, not a behavior.

## Related ADRs

- ADR-002: Interface-First Architecture
- ADR-004: Progressive Complexity Strategy
- ADR-007: LLMProvider as Core Interface
- ADR-011: KnowledgeStore as Core Interface
