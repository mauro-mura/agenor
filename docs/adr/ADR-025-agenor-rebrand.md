# ADR-025: Agenor Rebrand — Annotation Naming, Backward Compatibility, and Versioning

**Status**: Accepted  
**Date**: 2026-05-28  
**Authors**: Project Team  
**References**: ADR-002 (Interface-First Architecture), ADR-003 (Maven Multi-Module Structure),
ADR-006 (Annotation-Based Agent Configuration), ADR-016 (Spring Boot Starter),
ADR-020 (Core API Refactor)

---

## Context

The project was developed under the name **Jentic** with Maven groupId `dev.jentic`. Before
the first public release on Maven Central, the decision was made to rebrand to **Agenor**
(`dev.agenor`). The rename touches every layer of the stack: package declarations, class names,
annotation names, Maven coordinates, Spring Boot auto-configuration keys, and documentation.

Three cross-cutting decisions must be made consistently and documented canonically before
the migration executes:

1. **Annotation naming** — which annotations drop their `Jentic`/`Agenor` prefix and which keep it.
2. **Backward compatibility** — whether to provide a migration bridge for existing consumers.
3. **Versioning** — how the rename maps to the public version sequence and what gates `1.0.0`.

No artifact under `dev.jentic` has been published to Maven Central. There are no external
consumers to break.

---

## Decision

### D1 — Annotation Naming

Annotations fall into two categories based on collision risk with common framework annotations.

**Structural annotations** — names are unambiguous in the Java ecosystem; the `Agenor` prefix
is dropped to reduce verbosity at the call site:

| Old name | New name |
|---|---|
| `@JenticAgent` | `@Agent` |
| `@JenticBehavior` | `@Behavior` |
| `@JenticPersist` | `@Persist` |
| `@JenticPersistenceConfig` | `@PersistenceConfig` |

**Collision-risk annotation** — `@MessageHandler` conflicts concretely with Spring's
`@MessageMapping` and related annotations in projects that import both frameworks. The
`Agenor` prefix is retained as a namespace guard:

| Old name | New name |
|---|---|
| `@JenticMessageHandler` | `@AgenorMessageHandler` |

**Unchanged annotations** — already unprefixed and conflict-free:

| Annotation | Reason |
|---|---|
| `@DialogueHandler` | Domain-specific name, no known framework conflict |
| `@WithGuardrails` | Domain-specific name, no known framework conflict |
| `@RequiresApproval` | Domain-specific name, no known framework conflict |

Java resolves annotation ambiguity by fully-qualified name. The risk of collision for
`@Agent` and `@Behavior` is accepted as low: these names do not appear in Spring,
Jakarta EE, MicroProfile, or other mainstream frameworks as top-level annotations in
the same compilation unit.

### D2 — Backward Compatibility

**Clean cut. No migration bridge.**

No shim jar, no deprecated re-exports, no `@Deprecated`-annotated aliases are provided.
Rationale:

- No artifact under `dev.agenor` has been published to Maven Central; there are no external
  consumers.
- A deprecation bridge would impose ongoing maintenance cost, pollute the public API surface,
  and signal instability to new adopters from day one.
- The rename is a single, well-scoped operation with a clear upgrade path documented in the
  `v0.24.0` release notes.

Consumer migration path (documented in the GitHub Release `v0.24.0`):
- Update Maven coordinates: `dev.agenor:jentic-* → dev.agenor:agenor-*`
- Update Spring Boot properties: `jentic.* → agenor.*`
- Update imports and annotation names per the D1 table above.

### D3 — Versioning

The rebrand **continues the existing version sequence**. The first public release under
`dev.agenor` is **`0.24.0`**, immediately following `0.23.0` (the last release under
`dev.jentic`).

Rationale: the version number carries a maturity signal. Restarting at `0.1.0` would
discard the signal accumulated across 23 prior releases. Jumping to `1.0.0` at rebrand
time would be premature — the distributed backend story is not yet validated in production.

**Promotion criteria for `1.0.0`** (observable, not time-based):

1. At least one distributed backend (Redis transport, JDBC directory, or JDBC HITL gate)
   validated in a real deployment outside of the project's own test suite.
2. No breaking change to any core interface (`Agent`, `MessageDispatcher`, `AgentDirectory`,
   `BehaviorScheduler`, `LLMProvider`, `MemoryStore`, `Condition`) in the last two or three
   consecutive releases.
3. No ADR in the pipeline that requires a modification to a public interface or a change to
   Maven coordinates.

Until these criteria are met, the project signals active development via the `0.x` prefix.

---

## Consequences

### Positive

- The public API launches under a clean brand with no legacy naming debt.
- Annotation names at the call site are shorter for the common case (`@Agent`, `@Behavior`)
  and namespaced where collision risk is real (`@AgenorMessageHandler`).
- Version continuity (`0.23.0 → 0.24.0`) preserves the maturity signal for adopters
  evaluating the library.
- The `1.0.0` gate is defined by observable criteria, not by a calendar date, removing
  ambiguity about API stability commitments.

### Negative / trade-offs

- Any internal early adopter of `dev.agenor` artifacts must update coordinates, imports, and
  property keys. The `v0.24.0` release notes provide a complete migration table.
- `@Agent` and `@Behavior` are short, generic names. In projects that import multiple
  frameworks, developers must verify there is no annotation shadowing at the import level.
  The risk is assessed as low (see D1 rationale) but is not zero.

